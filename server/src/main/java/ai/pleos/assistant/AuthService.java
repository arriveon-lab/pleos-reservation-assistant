package ai.pleos.assistant;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final AuthStore store;
  private final Clock clock;
  private final Duration accessTtl;
  private final Duration refreshTtl;
  private final SecureRandom random = new SecureRandom();
  private static final Duration RETRY_WINDOW = Duration.ofMinutes(2);

  public AuthService(
      AuthStore store,
      Clock clock,
      @Value("${app.auth.access-ttl:PT1H}") Duration accessTtl,
      @Value("${app.auth.refresh-ttl:P90D}") Duration refreshTtl) {
    if (accessTtl.compareTo(RETRY_WINDOW) <= 0 || refreshTtl.compareTo(accessTtl) <= 0)
      throw new IllegalArgumentException(
          "Authentication TTLs must exceed retry window and access TTL respectively");
    this.store = store;
    this.clock = clock;
    this.accessTtl = accessTtl;
    this.refreshTtl = refreshTtl;
  }

  public record Tokens(
      String tokenType,
      String accessToken,
      Instant accessTokenExpiresAt,
      String refreshToken,
      Instant refreshTokenExpiresAt,
      String mode) {
    @Override
    public String toString() {
      return "Tokens[REDACTED]";
    }
  }

  @Transactional
  public Tokens issue() {
    store.lock();
    String session = UUID.randomUUID().toString();
    store.createSession(session, UUID.randomUUID().toString(), UUID.randomUUID().toString());
    return savePair(session, randomToken("pa_"), randomToken("pr_"));
  }

  @Transactional(readOnly = true)
  public AuthStore.Token authenticate(String accessToken) {
    requireToken(accessToken, "pa_");
    AuthStore.Token token = store.find(hash(accessToken)).orElseThrow(AuthService::unauthorized);
    if (!token.kind().equals("ACCESS")
        || token.revoked()
        || !token.expiresAt().isAfter(clock.instant())) throw unauthorized();
    return token;
  }

  @Transactional
  public Tokens refresh(String refreshToken, String key) {
    requireToken(refreshToken, "pr_");
    if (key == null || !key.matches("[A-Za-z0-9._:-]{16,100}"))
      throw ApiError.bad("INVALID_KEY", "갱신 요청의 Idempotency-Key는 16~100자여야 합니다.");
    store.lock();
    AuthStore.Token old = store.find(hash(refreshToken)).orElseThrow(AuthService::unauthorized);
    if (old.revoked() || !old.kind().equals("REFRESH")) throw unauthorized();
    String access = derive(refreshToken, key, "pa_");
    String refresh = derive(refreshToken, key, "pr_");
    if (old.retryKeyHash() != null) {
      if (!hash(key).equals(old.retryKeyHash()) || !old.retryUntil().isAfter(clock.instant()))
        throw ApiError.conflict("REFRESH_ALREADY_USED", "이미 사용한 갱신 토큰입니다.");
      var nextAccess = store.find(old.nextAccessHash()).orElseThrow(AuthService::unauthorized);
      var nextRefresh = store.find(old.nextRefreshHash()).orElseThrow(AuthService::unauthorized);
      if (nextRefresh.retryKeyHash() != null
          || nextRefresh.revoked()
          || !nextAccess.expiresAt().isAfter(clock.instant()))
        throw ApiError.conflict("REFRESH_ALREADY_USED", "이 갱신 응답은 더 이상 재전송할 수 없습니다.");
      return new Tokens(
          "Bearer", access, nextAccess.expiresAt(), refresh, nextRefresh.expiresAt(), "MOCK");
    }
    if (!old.expiresAt().isAfter(clock.instant())) throw unauthorized();
    Tokens pair = savePair(old.sessionId(), access, refresh);
    store.consume(
        old.hash(), hash(key), clock.instant().plus(RETRY_WINDOW), hash(access), hash(refresh));
    return pair;
  }

  @Transactional
  public void revoke(String accessToken) {
    store.lock();
    store.revoke(authenticate(accessToken).sessionId());
  }

  private Tokens savePair(String session, String access, String refresh) {
    Instant now = clock.instant();
    store.insertToken(hash(access), session, "ACCESS", now.plus(accessTtl));
    store.insertToken(hash(refresh), session, "REFRESH", now.plus(refreshTtl));
    return new Tokens("Bearer", access, now.plus(accessTtl), refresh, now.plus(refreshTtl), "MOCK");
  }

  private String randomToken(String prefix) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  // A retry derives the same pair from the old secret and request key; no plaintext snapshot or
  // persistent encryption key is needed. Prefixes domain-separate access and refresh tokens.
  private String derive(String secret, String key, String prefix) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] bytes =
          mac.doFinal(("pleos-auth-v1:" + prefix + ":" + key).getBytes(StandardCharsets.UTF_8));
      return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Token generation unavailable", e);
    }
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Token hashing unavailable", e);
    }
  }

  private void requireToken(String token, String prefix) {
    if (token == null || !token.matches(prefix + "[A-Za-z0-9_-]{43}")) throw unauthorized();
  }

  static ApiError unauthorized() {
    return new ApiError(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "유효한 인증 정보가 필요합니다.");
  }
}
