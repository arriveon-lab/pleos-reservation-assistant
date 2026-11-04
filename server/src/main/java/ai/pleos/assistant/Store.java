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
      String scope, String key, Object request, Class<T> type, Supplier<T> action) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}"))
      throw ApiError.bad("INVALID_KEY", "Idempotency-Key가 필요합니다(영문/숫자/._:-, 100자 이하).");
    lock();
    var cached =
        jdbc.queryForList(
            "SELECT request_hash, response_json FROM commands WHERE scope=? AND idempotency_key=?",
            scope,
            key);
    String fingerprint = hash(request);
    if (!cached.isEmpty()) {
      var row = cached.getFirst();
      if (!fingerprint.equals(row.get("REQUEST_HASH")))
        throw ApiError.conflict("KEY_REUSED", "같은 키에 다른 요청을 보낼 수 없습니다.");
      return decode(
          jdbc.queryForObject(
              "SELECT response_json FROM commands WHERE scope=? AND idempotency_key=?",
              String.class,
              scope,
              key),
          type);
    }
    T result = action.get();
    jdbc.update("INSERT INTO commands VALUES (?,?,?,?)", scope, key, fingerprint, encode(result));
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

  public void insertSearch(Model.Search s) {
    jdbc.update("INSERT INTO searches VALUES (?,?)", s.id, encode(s));
  }

  public void insertReservation(Model.Reservation r) {
    jdbc.update(
        "INSERT INTO reservations (id,search_id,payload,trip_id) VALUES (?,?,?,?)",
        r.id,
        r.searchId,
        encode(r),
        r.tripId);
  }

  public List<Model.Reservation> byTrip(String tripId) {
    return jdbc.query(
        "SELECT payload FROM reservations WHERE trip_id=?",
        (rs, n) -> decode(rs.getString(1), Model.Reservation.class),
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

  private void checkTable(String table) {
    if (!Set.of("searches", "reservations", "calls").contains(table))
      throw new IllegalArgumentException("Invalid table");
  }
}
