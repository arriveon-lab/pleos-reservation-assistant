package ai.pleos.assistant.ux;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Separate durable approval journal; never mutates the existing reservation journal. */
public final class MockPhoneController implements AutoCloseable {
  public interface Store {
    String read();

    void save(String value) throws Exception;
  }

  private final PreviewController.Transport wire;
  private final Store store;
  private final Runnable changed;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private JSONObject journal = new JSONObject();
  private boolean busy, allowed, closed, storageFailed;
  private String state = "IDLE";

  public MockPhoneController(PreviewController.Transport wire, Store store, Runnable changed) {
    this.wire = wire;
    this.store = store;
    this.changed = changed;
    try {
      String saved = store.read();
      if (saved != null) {
        journal = new JSONObject(saved);
        UUID.fromString(journal.getString("taskId"));
        journal.getJSONObject("approval");
        journal.getString("key");
        if (journal.has("receipt")) validate(journal.getJSONObject("receipt"));
        state =
            journal.has("receipt")
                ? journal.getJSONObject("receipt").getString("status")
                : "UNKNOWN";
      }
    } catch (Exception invalid) {
      storageFailed = true;
      state = "STORAGE_ERROR";
    }
  }

  public synchronized void allow(boolean value) {
    allowed = value;
  }

  public synchronized String state() {
    return state;
  }

  public synchronized boolean busy() {
    return busy;
  }

  public synchronized boolean hasRequest() {
    return journal.has("taskId");
  }

  public synchronized JSONObject receipt() {
    return copy(journal.optJSONObject("receipt"));
  }

  public synchronized JSONObject approval() {
    return copy(journal.optJSONObject("approval"));
  }

  public synchronized boolean canApprove(String task, int ordinal) {
    if (!allowed || busy || closed || storageFailed || task == null) return false;
    if (!hasRequest()) return true;
    JSONObject receipt = journal.optJSONObject("receipt");
    JSONObject next = receipt == null ? null : receipt.optJSONObject("nextCandidate");
    return task.equals(journal.optString("taskId"))
        && "DECLINED".equals(state)
        && next != null
        && next.optInt("ordinal") == ordinal;
  }

  public synchronized void approve(String task, JSONObject body, int partySize) {
    int ordinal = body.optInt("candidateOrdinal", -1);
    if (!canApprove(task, ordinal) || !Boolean.TRUE.equals(body.opt("approved"))) return;
    try {
      UUID.fromString(task);
      if (ordinal < 1 || ordinal > 3 || partySize < 1 || partySize > 30) return;
      Instant time = Instant.parse(body.getString("reservationAt"));
      if (time.getNano() != 0 || time.getEpochSecond() % 60 != 0 || !time.isAfter(Instant.now()))
        return;
      JSONObject old = journal.optJSONObject("receipt");
      if (old != null) {
        JSONObject original = journal.getJSONObject("approval");
        if (!original.getString("reservationAt").equals(body.getString("reservationAt"))
            || !original.getString("reservationName").equals(body.getString("reservationName")))
          return;
        body = copy(body).put("previousAttemptId", old.getString("attemptId"));
      }
      JSONObject pending =
          new JSONObject()
              .put("taskId", task)
              .put("key", UUID.randomUUID().toString())
              .put("approval", copy(body))
              .put("partySize", partySize);
      if (hasRequest()) pending.put("previous", copy(journal));
      store.save(pending.toString()); // Persist exact approval/key before any POST.
      journal = pending;
      busy = true;
      state = "SENDING";
      changed.run();
      JSONObject sent = copy(body);
      String key = journal.getString("key");
      io.execute(() -> send(task, sent, key));
    } catch (Exception failure) {
      storageFailed = true;
      state = "STORAGE_ERROR";
      changed.run();
    }
  }

  public synchronized void refresh() {
    if (closed || busy || storageFailed || !hasRequest()) return;
    busy = true;
    changed.run();
    String task = journal.optString("taskId");
    io.execute(() -> send(task, null, null));
  }

  private void send(String task, JSONObject body, String key) {
    try {
      // UI restrictions can change after enqueue. A GET is always recovery-only.
      synchronized (this) {
        if (body != null && (!allowed || closed)) {
          busy = false;
          state = "UNKNOWN";
          changed.run();
          return;
        }
      }
      JSONObject result =
          wire.exchange(DestinationController.ROOT + "/" + task + "/phone-reservations", body, key);
      synchronized (this) {
        validate(result);
        JSONObject next = copy(journal).put("receipt", copy(result));
        try {
          store.save(next.toString());
        } catch (Exception e) {
          storageFailed = true;
          state = "STORAGE_ERROR";
          busy = false;
          changed.run();
          return;
        }
        journal = next;
        state = result.getString("status");
        busy = false;
        changed.run();
      }
    } catch (Exception failure) {
      synchronized (this) {
        // Keep the exact request and any prior receipt, including on 404/timeout.
        state =
            journal.has("receipt")
                ? journal.optJSONObject("receipt").optString("status", "UNKNOWN")
                : "UNKNOWN";
        busy = false;
        changed.run();
      }
    }
  }

  private void validate(JSONObject r) throws Exception {
    JSONObject a = journal.getJSONObject("approval");
    String status = r.getString("status");
    var time = Instant.parse(a.getString("reservationAt")).atZone(ZoneId.of("Asia/Seoul"));
    String requested = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(time);
    if (!"MOCK".equals(r.getString("mode"))
        || r.getInt("externalCalls") != 0
        || !r.getString("attemptId").matches("[a-f0-9]{32}")
        || r.getInt("candidateOrdinal") != a.getInt("candidateOrdinal")
        || r.getInt("partySize") != journal.getInt("partySize")
        || !r.getString("date").equals(time.toLocalDate().toString())
        || !r.getString("requestedTime").equals(requested)
        || !Set.of("CONFIRMED", "DECLINED", "UNKNOWN", "FAILED").contains(status))
      throw new IllegalArgumentException();
    if ("CONFIRMED".equals(status)
        && (!r.getBoolean("bookingRequested")
            || !"MET".equals(r.getString("conditionStatus"))
            || !"AVAILABLE".equals(r.getString("availabilityStatus"))
            || !requested.equals(r.getString("confirmedTime"))))
      throw new IllegalArgumentException();
    if (!"DECLINED".equals(status) && r.optJSONObject("nextCandidate") != null)
      throw new IllegalArgumentException();
  }

  private static JSONObject copy(JSONObject value) {
    try {
      return value == null ? new JSONObject() : new JSONObject(value.toString());
    } catch (Exception invalid) {
      throw new IllegalArgumentException(invalid);
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    allowed = false;
    io.shutdown();
  }
}
