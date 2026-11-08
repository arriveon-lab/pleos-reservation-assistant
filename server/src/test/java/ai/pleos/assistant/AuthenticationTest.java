package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:authentication;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AuthenticationTest {
  @Autowired AuthService auth;
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired TestClock clock;
  static final Instant START = Instant.parse("2026-11-08T01:00:00Z");
  static final String BASE = "/api/v1/anonymous-sessions";

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    TestClock authClock() {
      return new TestClock();
    }
  }

  static class TestClock extends Clock {
    Instant now = START;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  @BeforeEach
  void resetClock() {
    clock.now = START;
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  void unauthorized(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApiError.class, e -> assertThat(e.status).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  Model.SearchRequest request() {
    return new Model.SearchRequest(
        new Model.Navigation(
            key(),
            new Model.Destination("가상 목적지", 37.544, 127.055),
            clock.instant().plusSeconds(3600),
            clock.instant(),
            Model.NavigationSource.MOCK),
        new Model.Party(2, 1),
        15,
        Set.of("parking"),
        Set.of("한식"),
        false);
  }

  @Test
  void issueOverHttpAndUseTokenWithoutPrincipalInjection() throws Exception {
    var result =
        http.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    var tokens =
        json.readValue(result.getResponse().getContentAsByteArray(), AuthService.Tokens.class);
    var search =
        http.perform(
                post("/api/v1/searches")
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(request())))
            .andExpect(status().isOk())
            .andReturn();
    String id = json.readTree(search.getResponse().getContentAsByteArray()).get("id").asText();
    http.perform(
            get("/api/v1/searches/" + id).header("Authorization", "bearer " + tokens.accessToken()))
        .andExpect(status().isOk());
    var other = auth.issue();
    http.perform(
            get("/api/v1/searches/" + id).header("Authorization", "Bearer " + other.accessToken()))
        .andExpect(status().isNotFound());
    var refreshed = auth.refresh(tokens.refreshToken(), key());
    http.perform(
            get("/api/v1/searches/" + id)
                .header("Authorization", "Bearer " + refreshed.accessToken()))
        .andExpect(status().isOk());
  }

  @Test
  void issueRejectsUserAndRoleFields() throws Exception {
    for (String body : List.of("{\"subject\":\"victim\"}", "{\"role\":\"MOCK_OPERATOR\"}"))
      http.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
  }

  @Test
  void issuedTokenCannotObtainOperatorRoleFromHeadersOrAmbientPrincipal() throws Exception {
    var tokens = auth.issue();
    http.perform(
            post("/api/v1/mock/calls/missing/outcomes")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .principal(() -> "operator")
                .with(
                    req -> {
                      req.addUserRole("MOCK_OPERATOR");
                      return req;
                    })
                .header("X-Role", "MOCK_OPERATOR")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MOCK_OPERATOR_REQUIRED"));
  }

  @Test
  void invalidAuthorizationDoesNotFallBackToAnInjectedPrincipal() throws Exception {
    http.perform(
            get("/api/v1/searches/missing")
                .principal(() -> "trusted")
                .header("Authorization", "Bearer forged"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    var token = auth.issue();
    http.perform(
            get("/api/v1/searches/missing")
                .header(
                    "Authorization",
                    "Bearer " + token.accessToken(),
                    "Bearer " + token.accessToken()))
        .andExpect(status().isUnauthorized());
    http.perform(
            get("/api/v1/searches/missing")
                .header("Authorization", "Bearer " + token.refreshToken()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tokensAreDistinctAndOnlyHashesAreStored() {
    var a = auth.issue();
    var b = auth.issue();
    assertThat(a.accessToken()).matches("pa_[A-Za-z0-9_-]{43}").isNotEqualTo(b.accessToken());
    assertThat(a.refreshToken()).matches("pr_[A-Za-z0-9_-]{43}").isNotEqualTo(b.refreshToken());
    assertThat(auth.authenticate(a.accessToken()).subject())
        .isNotEqualTo(auth.authenticate(b.accessToken()).subject());
    assertThat(jdbc.queryForList("SELECT token_hash FROM auth_tokens", String.class))
        .contains(AuthService.hash(a.accessToken()), AuthService.hash(a.refreshToken()))
        .doesNotContain(a.accessToken(), a.refreshToken());
    assertThat(a.toString()).doesNotContain(a.accessToken(), a.refreshToken());
  }

  @Test
  void refreshPreservesOwnerAndOldAccessUntilItsOriginalExpiry() {
    var a = auth.issue();
    String subject = auth.authenticate(a.accessToken()).subject();
    clock.now = START.plusSeconds(1800);
    var next = auth.refresh(a.refreshToken(), key());
    assertThat(next.accessToken()).isNotEqualTo(a.accessToken());
    assertThat(auth.authenticate(next.accessToken()).subject()).isEqualTo(subject);
    assertThat(auth.authenticate(a.accessToken()).subject()).isEqualTo(subject);
    clock.now = START.plusSeconds(3600);
    unauthorized(() -> auth.authenticate(a.accessToken()));
    assertThat(auth.authenticate(next.accessToken()).subject()).isEqualTo(subject);
  }

  @Test
  void lostResponseCanBeReplayedWithSameKeyButNotDifferentKey() {
    var a = auth.issue();
    String key = key();
    var b = auth.refresh(a.refreshToken(), key);
    clock.now = START.plusSeconds(119);
    assertThat(auth.refresh(a.refreshToken(), key)).isEqualTo(b);
    assertThatThrownBy(() -> auth.refresh(a.refreshToken(), key()))
        .isInstanceOfSatisfying(
            ApiError.class, e -> assertThat(e.code).isEqualTo("REFRESH_ALREADY_USED"));
    clock.now = START.plusSeconds(120);
    assertThatThrownBy(() -> auth.refresh(a.refreshToken(), key)).isInstanceOf(ApiError.class);
  }

  @Test
  void refreshRetryReturnsIdenticalExpirationsWithNanosecondClock() {
    clock.now = START.plusNanos(123_456_789);
    var issued = auth.issue();
    String key = key();
    var refreshed = auth.refresh(issued.refreshToken(), key);

    assertThat(auth.refresh(issued.refreshToken(), key)).isEqualTo(refreshed);
    assertThat(refreshed.accessTokenExpiresAt().getNano() % 1_000).isZero();
    assertThat(refreshed.refreshTokenExpiresAt().getNano() % 1_000).isZero();
  }

  @Test
  void previousRetryCannotRollBackASecondRotation() {
    var a = auth.issue();
    String key = key();
    var b = auth.refresh(a.refreshToken(), key);
    auth.refresh(b.refreshToken(), key());
    assertThatThrownBy(() -> auth.refresh(a.refreshToken(), key))
        .isInstanceOfSatisfying(
            ApiError.class, e -> assertThat(e.code).isEqualTo("REFRESH_ALREADY_USED"));
  }

  @Test
  void concurrentSameKeyRefreshReturnsExactlyOnePair() throws Exception {
    var a = auth.issue();
    String key = key();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<AuthService.Tokens>>();
      for (int i = 0; i < 4; i++)
        futures.add(pool.submit(() -> auth.refresh(a.refreshToken(), key)));
      Set<AuthService.Tokens> pairs = new HashSet<>();
      for (var f : futures) pairs.add(f.get(10, TimeUnit.SECONDS));
      assertThat(pairs).hasSize(1);
    }
  }

  @Test
  void concurrentDifferentKeysCannotRotateTwice() throws Exception {
    var a = auth.issue();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        futures.add(
            pool.submit(
                () -> {
                  try {
                    auth.refresh(a.refreshToken(), key());
                    return true;
                  } catch (ApiError e) {
                    assertThat(e.code).isEqualTo("REFRESH_ALREADY_USED");
                    return false;
                  }
                }));
      int successes = 0;
      for (var f : futures) if (f.get(10, TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(1);
    }
  }

  @Test
  void expiredAndUnknownTokensAreRejected() {
    var a = auth.issue();
    clock.now = START.plus(Duration.ofDays(90));
    unauthorized(() -> auth.refresh(a.refreshToken(), key()));
    unauthorized(() -> auth.authenticate(a.accessToken()));
    unauthorized(() -> auth.authenticate("pa_" + "x".repeat(43)));
    unauthorized(() -> auth.refresh(a.accessToken(), key()));
  }

  @Test
  void revokeInvalidatesEveryGenerationAndRetry() throws Exception {
    var a = auth.issue();
    String key = key();
    var b = auth.refresh(a.refreshToken(), key);
    http.perform(delete(BASE + "/current").header("Authorization", "Bearer " + b.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("REVOKED"));
    unauthorized(() -> auth.authenticate(a.accessToken()));
    unauthorized(() -> auth.authenticate(b.accessToken()));
    unauthorized(() -> auth.refresh(a.refreshToken(), key));
    unauthorized(() -> auth.refresh(b.refreshToken(), key()));
  }

  @Test
  void refreshHttpValidatesKeyAndReturnsNoCache() throws Exception {
    var a = auth.issue();
    String body = json.writeValueAsString(new AuthController.RefreshRequest(a.refreshToken()));
    http.perform(post(BASE + "/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
    http.perform(
            post(BASE + "/refresh")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void issuanceIsLimitedByDirectPeerNotSpoofedForwardingHeader() throws Exception {
    for (int i = 0; i < 11; i++) {
      http.perform(
              post(BASE)
                  .with(
                      req -> {
                        req.setRemoteAddr("192.0.2.40");
                        return req;
                      })
                  .header("X-Forwarded-For", "192.0.2." + i)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().is(i < 10 ? 200 : 429));
    }
  }

  @Test
  void globalIssuanceLimitIsBoundedAndWindowResets() {
    var limiter = new AuthRateLimiter(clock);
    for (int i = 0; i < 100; i++) limiter.acquire("peer-" + i);
    assertThatThrownBy(() -> limiter.acquire("new-peer"))
        .isInstanceOfSatisfying(
            ApiError.class, e -> assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    clock.now = START.plusSeconds(60);
    limiter.acquire("new-peer");
  }
}
