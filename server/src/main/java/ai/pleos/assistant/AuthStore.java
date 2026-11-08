package ai.pleos.assistant;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuthStore {
  private final JdbcTemplate jdbc;

  public AuthStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Token(
      String hash,
      String sessionId,
      String subject,
      String kind,
      Instant expiresAt,
      boolean revoked,
      String retryKeyHash,
      Instant retryUntil,
      String nextAccessHash,
      String nextRefreshHash) {}

  public void lock() {
    jdbc.queryForObject("SELECT id FROM command_mutex WHERE id=1 FOR UPDATE", Integer.class);
  }

  public void createSession(String sessionId, String userId, String subject) {
    jdbc.update("INSERT INTO app_users (id,subject) VALUES (?,?)", userId, subject);
    jdbc.update(
        "INSERT INTO auth_sessions (id,user_id,revoked) VALUES (?,?,FALSE)", sessionId, userId);
  }

  public void insertToken(String hash, String sessionId, String kind, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO auth_tokens (token_hash,session_id,token_kind,expires_at) VALUES (?,?,?,?)",
        hash,
        sessionId,
        kind,
        Timestamp.from(expiresAt));
  }

  public Optional<Token> find(String hash) {
    return jdbc
        .query(
            "SELECT t.*,s.revoked,u.subject FROM auth_tokens t JOIN auth_sessions s ON s.id=t.session_id JOIN app_users u ON u.id=s.user_id WHERE t.token_hash=?",
            (rs, n) ->
                new Token(
                    rs.getString("token_hash"),
                    rs.getString("session_id"),
                    rs.getString("subject"),
                    rs.getString("token_kind"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getBoolean("revoked"),
                    rs.getString("retry_key_hash"),
                    rs.getTimestamp("retry_until") == null
                        ? null
                        : rs.getTimestamp("retry_until").toInstant(),
                    rs.getString("next_access_hash"),
                    rs.getString("next_refresh_hash")),
            hash)
        .stream()
        .findFirst();
  }

  public void consume(
      String hash, String keyHash, Instant retryUntil, String accessHash, String refreshHash) {
    jdbc.update(
        "UPDATE auth_tokens SET retry_key_hash=?,retry_until=?,next_access_hash=?,next_refresh_hash=? WHERE token_hash=?",
        keyHash,
        Timestamp.from(retryUntil),
        accessHash,
        refreshHash,
        hash);
  }

  public void revoke(String sessionId) {
    jdbc.update("UPDATE auth_sessions SET revoked=TRUE WHERE id=?", sessionId);
  }
}
