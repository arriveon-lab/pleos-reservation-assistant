package ai.pleos.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class Store {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public Store(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  // One durable lock serializes this single-server MVP's commands within a transaction.
  // Replace with per-aggregate locks before multi-tenant production deployment.
  public void lock() {
    jdbc.queryForObject("SELECT id FROM command_mutex WHERE id=1 FOR UPDATE", Integer.class);
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public <T> T decode(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encode(value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public <T> T command(
      RequestActor actor,
      String scope,
      String key,
      Object request,
      Class<T> type,
      Supplier<T> action) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}"))
      throw ApiError.bad("INVALID_KEY", "Idempotency-Key가 필요합니다(영문/숫자/._:-, 100자 이하).");
    lock();
    String userId = userId(actor);
    var cached =
        jdbc.queryForList(
            "SELECT request_hash, response_json FROM commands WHERE user_id=? AND mode='MOCK' AND scope=? AND idempotency_key=?",
            userId,
            scope,
            key);
    String fingerprint = hash(request);
    if (!cached.isEmpty()) {
      var row = cached.getFirst();
      if (!fingerprint.equals(row.get("REQUEST_HASH")))
        throw ApiError.conflict("KEY_REUSED", "같은 키에 다른 요청을 보낼 수 없습니다.");
      return decode(
          jdbc.queryForObject(
              "SELECT response_json FROM commands WHERE user_id=? AND mode='MOCK' AND scope=? AND idempotency_key=?",
              String.class,
              userId,
              scope,
              key),
          type);
    }
    T result = action.get();
    jdbc.update(
        "INSERT INTO commands (user_id,mode,scope,idempotency_key,request_hash,response_json) VALUES (?,'MOCK',?,?,?,?)",
        userId,
        scope,
        key,
        fingerprint,
        encode(result));
    return result;
  }

  public <T> T get(String table, String id, Class<T> type) {
    checkTable(table);
    var values =
        jdbc.query("SELECT payload FROM " + table + " WHERE id=?", (rs, n) -> rs.getString(1), id);
    if (values.isEmpty()) throw new ApiError(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.");
    return decode(values.getFirst(), type);
  }

  public <T> List<T> bySearch(String table, String searchId, Class<T> type) {
    checkTable(table);
    return jdbc.query(
        "SELECT payload FROM " + table + " WHERE search_id=?",
        (rs, n) -> decode(rs.getString(1), type),
        searchId);
  }

  public void insertSearch(RequestActor actor, Model.Search s) {
    String user = userId(actor);
    String trip = s.request.navigation().tripId();
    var existing =
        jdbc.queryForList(
            "SELECT id FROM journeys WHERE user_id=? AND mode='MOCK' AND client_trip_id=?",
            String.class,
            user,
            trip);
    String journey = existing.isEmpty() ? UUID.randomUUID().toString() : existing.getFirst();
    if (existing.isEmpty())
      jdbc.update(
          "INSERT INTO journeys (id,user_id,mode,client_trip_id) VALUES (?,?,'MOCK',?)",
          journey,
          user,
          trip);
    jdbc.update(
        "INSERT INTO searches (id,payload,journey_id) VALUES (?,?,?)", s.id, encode(s), journey);
  }

  public void insertReservation(Model.Reservation r) {
    jdbc.update(
        "INSERT INTO reservations (id,search_id,payload,trip_id) VALUES (?,?,?,?)",
        r.id,
        r.searchId,
        encode(r),
        r.tripId);
  }

  public List<Model.Reservation> byTrip(RequestActor actor, String tripId) {
    return jdbc.query(
        "SELECT r.payload FROM reservations r JOIN searches s ON s.id=r.search_id JOIN journeys j ON j.id=s.journey_id JOIN app_users u ON u.id=j.user_id WHERE u.subject=? AND j.mode='MOCK' AND j.client_trip_id=?",
        (rs, n) -> decode(rs.getString(1), Model.Reservation.class),
        actor.subject(),
        tripId);
  }

  public void insertCall(Model.CallTask c) {
    jdbc.update("INSERT INTO calls VALUES (?,?,?)", c.id, c.searchId, encode(c));
  }

  public void save(String table, String id, Object object) {
    checkTable(table);
    jdbc.update("UPDATE " + table + " SET payload=? WHERE id=?", encode(object), id);
  }

  public boolean recordEvent(String callId, Model.OutcomeRequest event) {
    String fingerprint = hash(List.of(callId, event));
    var hashes =
        jdbc.query(
            "SELECT request_hash FROM events WHERE event_id=?",
            (rs, n) -> rs.getString(1),
            event.eventId());
    if (!hashes.isEmpty()) {
      if (!hashes.getFirst().equals(fingerprint))
        throw ApiError.conflict("EVENT_REUSED", "같은 이벤트 ID의 내용이 다릅니다.");
      return false;
    }
    jdbc.update(
        "INSERT INTO events VALUES (?,?,?,?)", event.eventId(), fingerprint, callId, encode(event));
    return true;
  }

  // Called under the command mutex inside the workflow transaction; read paths never create users.
  private String userId(RequestActor actor) {
    var existing =
        jdbc.queryForList(
            "SELECT id FROM app_users WHERE subject=?", String.class, actor.subject());
    if (!existing.isEmpty()) return existing.getFirst();
    String id = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO app_users (id,subject) VALUES (?,?)", id, actor.subject());
    return id;
  }

  public void requireOwner(RequestActor actor, String table, String id) {
    checkTable(table);
    String target = table.equals("searches") ? "s.id" : "t.id";
    String join = table.equals("searches") ? "" : " JOIN " + table + " t ON t.search_id=s.id";
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM searches s JOIN journeys j ON j.id=s.journey_id JOIN app_users u ON u.id=j.user_id"
                + join
                + " WHERE "
                + target
                + "=? AND u.subject=? AND j.mode='MOCK'",
            Integer.class,
            id,
            actor.subject());
    if (count == null || count == 0)
      throw new ApiError(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.");
  }

  private void checkTable(String table) {
    if (!Set.of("searches", "reservations", "calls").contains(table))
      throw new IllegalArgumentException("Invalid table");
  }
}
