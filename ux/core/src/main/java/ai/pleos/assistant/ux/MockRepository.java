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

    default void ensureSession() throws Exception {}

    default void createIdentity() throws Exception {
      throw new IllegalStateException("UNSUPPORTED");
    }

    default void renewIdentity() throws Exception {
      throw new IllegalStateException("UNSUPPORTED");
    }

    default String binding() {
      return "";
    }
  }

  public interface Journal {
    String read();

    void save(String value) throws Exception;
  }

  public enum Restriction {
    UNKNOWN,
    RESTRICTED,
    SIMULATED_PARKED,
    PLATFORM_UNRESTRICTED;

    public boolean allowsInput() {
      return this == SIMULATED_PARKED || this == PLATFORM_UNRESTRICTED;
    }
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

  public boolean isVerified() {
    return verified;
  }

  public void connect() throws Exception {
    verified = false;
    JSONObject status = transport.exchange("/status", null, null);
    if (!"MOCK".equals(status.getString("mode"))
        || status.getBoolean("externalCallsEnabled")
        || !"1.1.0".equals(status.getString("contractVersion"))) {
      throw new IllegalStateException("INCOMPATIBLE_SERVER");
    }
    transport.ensureSession();
    String binding = transport.binding();
    if (!binding.isEmpty()) {
      if (state.has("authBinding") && !binding.equals(state.getString("authBinding")))
        throw new IllegalStateException("IDENTITY_MISMATCH");
      if (!state.has("authBinding")
          && (state.has("searchId")
              || state.has("pending")
              || state.has("reservationId")
              || state.has("callId"))) throw new IllegalStateException("LEGACY_RECOVERY_REQUIRED");
      state.put("authBinding", binding);
    }
    verified = true;
    refresh();
  }

  public boolean canCreateIdentity() {
    return transport instanceof AuthSession session
        && session.canCreate()
        && !state.has("searchId")
        && !state.has("reservationId")
        && !state.has("callId")
        && !state.has("pending")
        && !state.has("authBinding");
  }

  public void createIdentity() throws Exception {
    if (!restriction.allowsInput() || !canCreateIdentity())
      throw new IllegalStateException("AUTH_CREATE_BLOCKED");
    JSONObject status = transport.exchange("/status", null, null);
    if (!"1.1.0".equals(status.optString("contractVersion"))
        || !"MOCK".equals(status.optString("mode"))
        || status.getBoolean("externalCallsEnabled"))
      throw new IllegalStateException("INCOMPATIBLE_SERVER");
    if (!restriction.allowsInput()) throw new IllegalStateException("INPUT_BLOCKED");
    transport.createIdentity();
    connect();
  }

  public void renewIdentity() throws Exception {
    if (!restriction.allowsInput()) throw new IllegalStateException("INPUT_BLOCKED");
    transport.renewIdentity();
    connect();
  }

  private void requireWrite() {
    if (!verified || !restriction.allowsInput()) throw new IllegalStateException("INPUT_BLOCKED");
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
    persist(state);
  }

  private void persist(JSONObject source) throws Exception {
    JSONObject durable = new JSONObject().put("tripId", source.getString("tripId"));
    for (String key :
        new String[] {
          "searchId", "reservationId", "callId", "pending", "authBinding", "searchReconciliations"
        }) if (source.has(key)) durable.put(key, source.get(key));
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
    if (!verified || !restriction.allowsInput()) throw new IllegalStateException("INPUT_BLOCKED");
    JSONObject pending = state.getJSONObject("pending");
    // Also retry a previously failed disk write before contacting the server.
    persist();
    if (!restriction.allowsInput()) throw new IllegalStateException("INPUT_BLOCKED");
    JSONObject result;
    try {
      result =
          transport.exchange(
              pending.getString("path"), pending.getJSONObject("body"), pending.getString("key"));
    } catch (AuthSession.HttpFailure failure) {
      if (failure.status == 400
          && "/searches".equals(pending.getString("path"))
          && java.util.Set.of("INVALID_TIME", "STALE_NAVIGATION").contains(failure.code)) {
        // Preserve the original identity, key and intent. Rejection is not reconciliation.
        pending.put("recoveryCode", failure.code);
        persist();
      }
      throw failure;
    }
    checkMode(result);
    String target = pending.getString("target");
    if ("searchId".equals(target) && !result.getString("id").equals(state.optString("searchId"))) {
      state.remove("callId");
      state.remove("call");
    }
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

  /**
   * Explicit same-subject reconciliation; never changes the original command or starts a search.
   */
  public void reconcileSearch() throws Exception {
    if (!verified || !restriction.allowsInput()) throw new IllegalStateException("INPUT_BLOCKED");
    JSONObject pending = state.optJSONObject("pending");
    if (pending == null
        || !"/searches".equals(pending.optString("path"))
        || !"searchId".equals(pending.optString("target")))
      throw new IllegalStateException("RECONCILIATION_BLOCKED");
    persist();
    if (!restriction.allowsInput()) throw new IllegalStateException("INPUT_BLOCKED");
    JSONObject response =
        transport.exchange(
            "/searches/reconcile", pending.getJSONObject("body"), pending.getString("key"));
    checkMode(response);
    String outcome = response.getString("state");
    Instant.parse(response.getString("resolvedAt"));
    JSONObject next = new JSONObject(state.toString());
    if ("COMPLETED".equals(outcome)) {
      String id = response.getString("searchId");
      UUID.fromString(id);
      if (!response.has("reason") || !response.isNull("reason"))
        throw new IllegalStateException("INVALID_RECONCILIATION");
      JSONObject search = read("/searches/" + id);
      if (!id.equals(search.getString("id")))
        throw new IllegalStateException("INVALID_RECONCILIATION");
      next.put("searchId", id).put("search", search);
      if (!id.equals(state.optString("searchId"))) {
        next.remove("callId");
        next.remove("call");
      }
    } else if ("REJECTED_CLOSED".equals(outcome)) {
      if (!response.has("searchId")
          || !response.isNull("searchId")
          || !java.util.Set.of("INVALID_TIME", "STALE_NAVIGATION")
              .contains(response.getString("reason")))
        throw new IllegalStateException("INVALID_RECONCILIATION");
    } else throw new IllegalStateException("INVALID_RECONCILIATION");
    // Persist acknowledgement and original intent together before releasing the write barrier.
    JSONArray history = next.optJSONArray("searchReconciliations");
    if (history == null) history = new JSONArray();
    JSONObject receipt =
        new JSONObject()
            .put("state", outcome)
            .put("mode", "MOCK")
            .put("resolvedAt", response.getString("resolvedAt"))
            .put("searchId", response.get("searchId"))
            .put("reason", response.get("reason"));
    history.put(
        new JSONObject().put("command", new JSONObject(pending.toString())).put("result", receipt));
    next.put("searchReconciliations", history);
    next.remove("pending");
    persist(next);
    state = next;
  }

  public void search(int adults, int children, boolean parking) throws Exception {
    requireWrite();
    if (hasUnresolvedCall()) throw new IllegalStateException("CALL_PENDING");
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
    if (hasUnresolvedCall()) throw new IllegalStateException("CALL_PENDING");
    command(
        "/searches/" + state.getString("searchId") + "/inquiries",
        new JSONObject().put("candidateId", candidateId),
        "callId");
  }

  private boolean hasUnresolvedCall() throws Exception {
    return state.has("call")
        && ("PENDING".equals(state.getJSONObject("call").getString("status"))
            || "UNKNOWN".equals(state.getJSONObject("call").getString("status")));
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

  /** Server contract: changes must be at least ten minutes apart; server remains authoritative. */
  public static Instant nextChangeAt(JSONObject reservation) {
    return reservation.isNull("lastChangeRequestedAt")
        ? null
        : Instant.parse(reservation.getString("lastChangeRequestedAt")).plusSeconds(600);
  }

  public void change(boolean explicitApproval) throws Exception {
    JSONObject reservation = state.getJSONObject("reservation");
    if (!explicitApproval
        || !"CONFIRMED".equals(reservation.getString("status"))
        || !reservation.isNull("pendingChangeCallId"))
      throw new IllegalStateException("CHANGE_BLOCKED");
    Instant next = nextChangeAt(reservation);
    if (next != null && Instant.now().isBefore(next))
      throw new IllegalStateException("CHANGE_COOLDOWN");
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
