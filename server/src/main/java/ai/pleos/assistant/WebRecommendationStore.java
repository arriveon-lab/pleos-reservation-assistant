package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Deliberately has no method that accepts a web Result/Candidate/Evidence. */
@Repository
@ConditionalOnProperty(name = "app.web-recommendations.enabled", havingValue = "true")
public class WebRecommendationStore {
  public record Created(Saved saved, boolean newRequest) {}

  private final JdbcTemplate jdbc;
  private final Store codec;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public WebRecommendationStore(
      JdbcTemplate jdbc, Store codec, TransactionTemplate transactions, Clock clock) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.transactions = transactions;
    this.clock = clock;
  }

  public Created create(RequestActor actor, String key, Request request) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}"))
      throw ApiError.bad("INVALID_KEY", "Idempotency-Key가 필요합니다.");
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              codec.lock();
              var user =
                  jdbc.queryForList(
                      "SELECT id FROM app_users WHERE subject=?", String.class, actor.subject());
              if (user.isEmpty())
                throw new ApiError(
                    HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "유효한 앱 인증이 필요합니다.");
              String userId = user.getFirst(),
                  hash =
                      codec.hash(
                          java.util.List.of(
                              request.viewId().toString(),
                              request.generation(),
                              request.area(),
                              request.requiredNeeds().stream().sorted().map(Enum::name).toList(),
                              request.personalizationConsent()));
              var previous =
                  jdbc.queryForList(
                      "SELECT id,request_hash,deleted FROM web_recommendation_requests WHERE user_id=? AND mode='MOCK' AND idempotency_key=?",
                      userId,
                      key);
              if (!previous.isEmpty()) {
                var row = previous.getFirst();
                if (!hash.equals(row.get("REQUEST_HASH")))
                  throw ApiError.conflict("KEY_REUSED", "같은 키에 다른 요청을 보낼 수 없습니다.");
                if (Boolean.TRUE.equals(row.get("DELETED")))
                  throw ApiError.conflict("REQUEST_DELETED", "삭제한 요청은 재실행하지 않습니다.");
                return new Created(get(actor, row.get("ID").toString()), false);
              }
              Long latest =
                  jdbc.queryForObject(
                      "SELECT MAX(generation) FROM web_recommendation_requests WHERE user_id=? AND mode='MOCK' AND view_id=?",
                      Long.class,
                      userId,
                      request.viewId().toString());
              if (latest != null && request.generation() <= latest)
                throw ApiError.conflict("STALE_GENERATION", "새 조건은 더 높은 화면 세대로 요청하세요.");
              String id = UUID.randomUUID().toString();
              jdbc.update(
                  "INSERT INTO web_recommendation_requests (id,user_id,mode,idempotency_key,request_hash,view_id,generation,request_json,created_at) VALUES (?,?,'MOCK',?,?,?,?,?,?)",
                  id,
                  userId,
                  key,
                  hash,
                  request.viewId().toString(),
                  request.generation(),
                  codec.encode(request),
                  OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
              return new Created(get(actor, id), true);
            }));
  }

  public Saved get(RequestActor actor, String id) {
    var rows =
        jdbc.query(
            "SELECT r.id,r.request_json,r.feedback_json,r.created_at,r.deleted FROM web_recommendation_requests r JOIN app_users u ON u.id=r.user_id WHERE r.id=? AND u.subject=? AND r.mode='MOCK'",
            (rs, n) ->
                new Saved(
                    rs.getString(1),
                    Mode.MOCK,
                    rs.getString(2) == null ? null : codec.decode(rs.getString(2), Request.class),
                    rs.getString(3) == null ? null : codec.decode(rs.getString(3), Feedback.class),
                    rs.getObject(4, OffsetDateTime.class).toInstant(),
                    rs.getBoolean(5)),
            id,
            actor.subject());
    if (rows.isEmpty()) throw new ApiError(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.");
    return rows.getFirst();
  }

  public Saved feedback(RequestActor actor, String id, Feedback feedback) {
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              codec.lock();
              if (get(actor, id).deleted())
                throw ApiError.conflict("REQUEST_DELETED", "삭제한 요청입니다.");
              jdbc.update(
                  "UPDATE web_recommendation_requests SET feedback_json=? WHERE id=?",
                  codec.encode(feedback),
                  id);
              return get(actor, id);
            }));
  }

  public Saved delete(RequestActor actor, String id) {
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              codec.lock();
              get(actor, id);
              // Minimal command tombstone prevents a deleted key/generation from automatically
              // searching again.
              jdbc.update(
                  "UPDATE web_recommendation_requests SET request_json=NULL,feedback_json=NULL,deleted=TRUE WHERE id=?",
                  id);
              return get(actor, id);
            }));
  }
}
