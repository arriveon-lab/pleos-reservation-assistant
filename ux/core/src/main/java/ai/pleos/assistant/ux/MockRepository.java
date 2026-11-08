package ai.pleos.assistant.ux;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Serial worker-owned MOCK API client. Durable intent is saved before any command is sent. */
public final class MockRepository {
  public interface Transport {
    JSONObject exchange(String path, JSONObject body, String key) throws Exception;
  }

  public interface Journal {
    String read();

    void save(String value) throws Exception;
  }

  public enum Restriction {
    UNKNOWN,
    RESTRICTED,
    SIMULATED_PARKED
  }

  private final Transport transport;
  private final Journal journal;
  private JSONObject state;
  private boolean verified;
  private volatile Restriction restriction = Restriction.UNKNOWN;

  public MockRepository(Transport transport, Journal journal) throws Exception {
    this.transport = transport;
    this.journal = journal;
    String saved = journal.read();
    // Corrupt recovery state must fail closed, never silently create a new trip.
    state =
        saved == null
            ? new JSONObject().put("tripId", UUID.randomUUID().toString())
            : new JSONObject(saved);
    UUID.fromString(state.getString("tripId"));
  }

  public void restrict(Restriction value) {
    restriction = value;
  }

  public Restriction restriction() {
    return restriction;
  }

  public JSONObject snapshot() throws Exception {
    return new JSONObject(state.toString());
  }

  public void connect() throws Exception {
    verified = false;
    JSONObject status = transport.exchange("/status", null, null);
    if (!"MOCK".equals(status.getString("mode"))
        || status.getBoolean("externalCallsEnabled")
        || !"1.0.0".equals(status.getString("contractVersion"))) {
      throw new IllegalStateException("INCOMPATIBLE_SERVER");
    }
    verified = true;
    refresh();
  }

  private void requireWrite() {
    if (!verified || restriction != Restriction.SIMULATED_PARKED)
      throw new IllegalStateException("INPUT_BLOCKED");
    if (state.has("pending")) throw new IllegalStateException("PENDING_COMMAND");
  }

  private JSONObject read(String path) throws Exception {
    if (!verified) throw new IllegalStateException("NOT_CONNECTED");
    JSONObject result = transport.exchange(path, null, null);
    checkMode(result);
    return result;
  }

  private void checkMode(JSONObject result) throws Exception {
    if (!"MOCK".equals(result.getString("mode"))) {
      verified = false;
      throw new IllegalStateException("INCOMPATIBLE_SERVER");
    }
  }

  public void refresh() throws Exception {
    if (state.has("searchId"))
      state.put("search", read("/searches/" + state.getString("searchId")));
    if (state.has("reservationId")) {
      JSONObject reservation = read("/reservations/" + state.getString("reservationId"));
      state.put("reservation", reservation);
      String callId =
          reservation.isNull("pendingChangeCallId")
              ? reservation.getString("bookingCallId")
              : reservation.getString("pendingChangeCallId");
      state.put("call", read("/calls/" + callId));
    } else if (state.has("callId")) state.put("call", read("/calls/" + state.getString("callId")));
    persist();
  }

  private void persist() throws Exception {
    // Do not persist complete candidate/fact responses. Only recovery references and pending
    // intent.
    JSONObject durable = new JSONObject().put("tripId", state.getString("tripId"));
    for (String key : new String[] {"searchId", "reservationId", "callId", "pending"})
      if (state.has(key)) durable.put(key, state.get(key));
    journal.save(durable.toString());
  }

  private void command(String path, JSONObject body, String target) throws Exception {
    requireWrite();
    state.put(
        "pending",
        new JSONObject()
            .put("path", path)
            .put("body", body)
            .put("key", UUID.randomUUID().toString())
            .put("target", target));
    persist();
    retry();
  }

  public void retry() throws Exception {
    if (!verified || restriction != Restriction.SIMULATED_PARKED)
      throw new IllegalStateException("INPUT_BLOCKED");
    JSONObject pending = state.getJSONObject("pending");
    // Also retry a previously failed disk write before contacting the server.
    persist();
    JSONObject result =
        transport.exchange(
            pending.getString("path"), pending.getJSONObject("body"), pending.getString("key"));
    checkMode(result);
    String target = pending.getString("target");
    if (!target.isEmpty()) state.put(target, result.getString("id"));
    state.remove("pending");
    try {
      persist();
    } catch (Exception failure) {
      // Keep memory and durable intent aligned if saving the acknowledgement fails.
      state.put("pending", pending);
      throw failure;
    }
    refresh();
  }

  public void search(int adults, int children, boolean parking) throws Exception {
    requireWrite();
    if (state.has("reservationId") || adults < 1 || adults > 8 || children < 0 || children > 6)
      throw new IllegalArgumentException("INVALID_PARTY");
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    JSONObject body =
        new JSONObject()
            .put(
                "navigation",
                new JSONObject()
                    .put("tripId", state.getString("tripId"))
                    .put(
                        "destination",
                        new JSONObject()
                            .put("name", "가상 성수 목적지")
                            .put("latitude", 37.544)
                            .put("longitude", 127.055))
                    .put("eta", now.plus(1, ChronoUnit.HOURS).toString())
                    .put("observedAt", now.toString())
                    .put("source", "MOCK"))
            .put("party", new JSONObject().put("adults", adults).put("children", children))
            .put("maxWaitMinutes", 15)
            .put("requiredFeatures", parking ? new JSONArray().put("parking") : new JSONArray())
            .put("preferredCuisines", new JSONArray().put("한식"))
            .put("learningConsent", false);
    command("/searches", body, "searchId");
  }

  public void inquire(String candidateId) throws Exception {
    if (!candidate(candidateId).getBoolean("eligible"))
      throw new IllegalStateException("INELIGIBLE");
    if (state.has("call")
        && ("PENDING".equals(state.getJSONObject("call").getString("status"))
            || "UNKNOWN".equals(state.getJSONObject("call").getString("status"))))
      throw new IllegalStateException("CALL_PENDING");
    command(
        "/searches/" + state.getString("searchId") + "/inquiries",
        new JSONObject().put("candidateId", candidateId),
        "callId");
  }

  public JSONObject candidate(String id) throws Exception {
    JSONArray candidates = state.getJSONObject("search").getJSONArray("candidates");
    for (int i = 0; i < candidates.length(); i++)
      if (id.equals(candidates.getJSONObject(i).getString("id")))
        return candidates.getJSONObject(i);
    throw new IllegalArgumentException("NO_CANDIDATE");
  }

  public String eta() throws Exception {
    return state
        .getJSONObject("search")
        .getJSONObject("request")
        .getJSONObject("navigation")
        .getString("eta");
  }

  public void book(String candidateId, boolean explicitApproval) throws Exception {
    if (!explicitApproval
        || state.has("reservationId")
        || !candidate(candidateId).getBoolean("bookable"))
      throw new IllegalStateException("BOOKING_BLOCKED");
    JSONObject policy =
        new JSONObject()
            .put("enabled", true)
            .put("latestTime", Instant.parse(eta()).plus(1, ChronoUnit.HOURS).toString())
            .put("maxAdditionalCostKrw", 0);
    command(
        "/searches/" + state.getString("searchId") + "/reservations",
        new JSONObject()
            .put("candidateId", candidateId)
            .put("requestedTime", eta())
            .put("allowBooking", true)
            .put("changePolicy", policy),
        "reservationId");
  }

  public void change(boolean explicitApproval) throws Exception {
    JSONObject reservation = state.getJSONObject("reservation");
    if (!explicitApproval
        || !"CONFIRMED".equals(reservation.getString("status"))
        || !reservation.isNull("pendingChangeCallId"))
      throw new IllegalStateException("CHANGE_BLOCKED");
    command(
        "/reservations/" + state.getString("reservationId") + "/changes",
        new JSONObject()
            .put(
                "requestedTime",
                Instant.parse(reservation.getString("confirmedTime"))
                    .plus(15, ChronoUnit.MINUTES)
                    .toString())
            .put("additionalCostKrw", 0)
            .put("approveAdditionalCost", false),
        "reservationId");
  }

  /** Explicit synthetic operator action, never called automatically after a user command. */
  public void simulate(String outcome) throws Exception {
    JSONObject call = state.getJSONObject("call");
    if (!"PENDING".equals(call.getString("status")))
      throw new IllegalStateException("CALL_FINISHED");
    boolean inquiry = "INQUIRY".equals(call.getString("purpose"));
    if (!outcome.equals("UNKNOWN")
        && !outcome.equals("REJECTED")
        && !outcome.equals(inquiry ? "ANSWERED" : "ACCEPTED"))
      throw new IllegalArgumentException("INVALID_OUTCOME");
    JSONObject body =
        new JSONObject()
            .put("eventId", UUID.randomUUID().toString())
            .put("outcome", outcome)
            .put("observedAt", Instant.now().toString())
            .put("explicitAcceptance", outcome.equals("ACCEPTED"))
            .put("additionalCostKrw", call.getInt("additionalCostKrw"));
    if (outcome.equals("ACCEPTED")) body.put("acceptedTime", call.getString("requestedTime"));
    if (outcome.equals("ANSWERED"))
      body.put(
          "facts",
          new JSONObject()
              .put("childFriendly", true)
              .put("parking", true)
              .put("seatsAvailable", true)
              .put("reservationAvailable", true)
              .put("nowWaitMinutes", 25)
              .put("arrivalWaitMinutes", 10)
              .put("arrivalAppliesAt", eta()));
    command("/mock/calls/" + call.getString("id") + "/outcomes", body, "");
  }
}
