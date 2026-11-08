package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
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
