package ai.pleos.assistant.ux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Memory-only draft client. UI invalidation never waits for network cancellation. */
public final class PreviewController implements AutoCloseable {
  public static final String ROOT = "/experimental/place-previews";
  private static final Set<String> RESULT_STATES = Set.of("RESULTS", "EMPTY", "FILTERED");
  private static final Set<String> TERMINAL =
      Set.of(
          "FAILED",
          "CANCELLED",
          "SUPERSEDED",
          "TIMED_OUT",
          "STALE_GENERATION",
          "REQUEST_CONFLICT",
          "ALREADY_FINISHED",
          "CLOSED");

  public interface Transport {
    JSONObject exchange(String path, JSONObject body, String key) throws Exception;
  }

  public record Input(
      int adults,
      int children,
      int maxWaitMinutes,
      boolean parking,
      boolean childFriendly,
      String cuisines,
      int destination,
      int radiusMeters) {
    public JSONObject body(long generation) throws Exception {
      if (adults < 1
          || adults > 20
          || children < 0
          || children > 10
          || maxWaitMinutes < 0
          || maxWaitMinutes > 120
          || destination < 0
          || destination > 1
          || radiusMeters < 1
          || radiusMeters > 20000) throw new IllegalArgumentException("INVALID_INPUT");
      JSONArray preferences = new JSONArray();
      Set<String> seen = new java.util.HashSet<>();
      if (!cuisines.trim().isEmpty())
        for (String part : cuisines.split(",", -1)) {
          String value = part.trim();
          if (value.isEmpty() || value.length() > 30 || !seen.add(value) || seen.size() > 8)
            throw new IllegalArgumentException("INVALID_INPUT");
          preferences.put(value);
        }
      JSONArray features = new JSONArray();
      if (parking) features.put("parking");
      if (childFriendly) features.put("childFriendly");
      return new JSONObject()
          .put("generation", generation)
          .put(
              "query",
              new JSONObject()
                  .put("latitude", destination == 0 ? 37.544 : 37.545)
                  .put("longitude", destination == 0 ? 127.055 : 127.043)
                  .put("radiusMeters", radiusMeters)
                  .put("limit", 3))
          .put(
              "requirements",
              new JSONObject()
                  .put("party", new JSONObject().put("adults", adults).put("children", children))
                  .put("maxWaitMinutes", maxWaitMinutes)
                  .put("requiredFeatures", features)
                  .put("preferredCuisines", preferences));
    }
  }

  public record View(
      String state, JSONObject result, boolean cleanupFailed, boolean cleanupPending) {}

  private static final class Ticket {
    final long generation;
    volatile String id;
    boolean cleanupScheduled;

    Ticket(long generation) {
      this.generation = generation;
    }
  }

  private final Transport transport;
  private final Runnable changed;
  private final Clock clock;
  private final LongSupplier elapsed;
  private final ExecutorService requests = Executors.newFixedThreadPool(2);
  private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
  private Ticket current;
  private long generation;
  private String state = "IDLE";
  private JSONObject result;
  private Instant receivedAt;
  private long receivedTick, validNanos;
  private boolean allowed, closed, cleanupFailed;
  private int cleaning;

  public PreviewController(Transport transport, Runnable changed) {
    this(transport, changed, Clock.systemUTC(), System::nanoTime);
  }

  public PreviewController(
      Transport transport, Runnable changed, Clock clock, LongSupplier elapsed) {
    this.transport = transport;
    this.changed = changed;
    this.clock = clock;
    this.elapsed = elapsed;
  }

  public synchronized void allow(boolean value) {
    allowed = value;
    if (!value && current != null) invalidate("RESTRICTED");
  }

  public synchronized View view() {
    expire();
    try {
      return new View(
          state,
          result == null ? null : new JSONObject(result.toString()),
          cleanupFailed,
          cleaning > 0);
    } catch (Exception invalid) {
      throw new IllegalStateException("INVALID_RESPONSE");
    }
  }

  public synchronized void tick() {
    if (expire()) changed.run();
  }

  private boolean expire() {
    if (result == null) return false;
    Instant now = clock.instant();
    if (now.isBefore(receivedAt.minusSeconds(2))) {
      invalidate("CLOCK_UNCERTAIN");
      return true;
    }
    if (!now.isBefore(Instant.parse(result.optString("expiresAt")))
        || elapsed.getAsLong() - receivedTick >= validNanos) {
      invalidate("EXPIRED");
      return true;
    }
    return false;
  }

  public synchronized void invalidate(String reason) {
    Ticket previous = current;
    current = null;
    result = null;
    state = reason;
    scheduleCleanup(previous);
    changed.run();
  }

  public synchronized void search(Input input) {
    if (closed || !allowed || "LOADING".equals(state)) return;
    JSONObject body;
    try {
      body = input.body(generation + 1);
    } catch (Exception invalid) {
      invalidate("INVALID_INPUT");
      return;
    }
    invalidate("LOADING");
    Ticket ticket = new Ticket(++generation);
    current = ticket;
    requests.execute(() -> query(ticket, body));
  }

  private void query(Ticket ticket, JSONObject body) {
    try {
      JSONObject status = transport.exchange("/status", null, null);
      if (!"1.1.0".equals(status.optString("contractVersion"))
          || !"MOCK".equals(status.optString("mode"))
          || status.getBoolean("externalCallsEnabled"))
        throw new IllegalStateException("INVALID_RESPONSE");
      synchronized (this) {
        if (current != ticket || !allowed) return;
      }
      JSONObject session = transport.exchange(ROOT, new JSONObject(), null);
      if (!"MOCK".equals(session.getString("mode")))
        throw new IllegalStateException("INVALID_RESPONSE");
      ticket.id = UUID.fromString(session.getString("previewId")).toString();
      synchronized (this) {
        if (current != ticket || !allowed) {
          scheduleCleanup(ticket);
          return;
        }
      }
      JSONObject response = transport.exchange(ROOT + "/" + ticket.id + "/queries", body, null);
      synchronized (this) {
        if (current != ticket || !allowed || closed) {
          scheduleCleanup(ticket);
          return;
        }
        validate(response, ticket, body.getJSONObject("requirements"));
        state = response.getString("state");
        if (RESULT_STATES.contains(state)) {
          receivedAt = clock.instant();
          receivedTick = elapsed.getAsLong();
          validNanos =
              Duration.between(receivedAt, Instant.parse(response.getString("expiresAt")))
                  .toNanos();
          result = response;
          expire();
        } else result = null;
        changed.run();
      }
    } catch (Exception failure) {
      synchronized (this) {
        if (current == ticket && !closed) {
          result = null;
          state =
              failure instanceof AuthSession.HttpFailure http
                  ? switch (http.status) {
                    case 400 -> "INVALID_INPUT";
                    case 401, 403 -> "AUTH_REQUIRED";
                    case 404 -> "UNAVAILABLE";
                    case 429 -> "RATE_LIMITED";
                    default -> "NETWORK_FAILED";
                  }
                  : failure instanceof java.io.IOException ? "NETWORK_FAILED" : "INVALID_RESPONSE";
          changed.run();
        }
      }
    } finally {
      synchronized (this) {
        if (current != ticket) scheduleCleanup(ticket);
      }
    }
  }

  private void validate(JSONObject value, Ticket ticket, JSONObject requirements) throws Exception {
    for (String field : new String[] {"mode", "placeSource", "reservationMode"})
      if (!"MOCK".equals(value.getString(field))) throw new IllegalStateException();
    if (!ticket.id.equals(value.getString("previewId"))
        || ticket.generation != value.getLong("generation")) throw new IllegalStateException();
    String status = value.getString("state");
    JSONArray places = value.getJSONArray("places");
    if (!RESULT_STATES.contains(status)) {
      if (!TERMINAL.contains(status) || places.length() != 0 || !value.isNull("recommendationPlan"))
        throw new IllegalStateException();
      return;
    }
    Instant fetched = Instant.parse(value.getString("fetchedAt")),
        expires = Instant.parse(value.getString("expiresAt"));
    if (fetched.isAfter(clock.instant().plusSeconds(2))
        || !expires.isAfter(fetched)
        || Duration.between(fetched, expires).compareTo(Duration.ofMinutes(5)) > 0)
      throw new IllegalStateException();
    if (places.length() > 3
        || (status.equals("RESULTS") ? places.length() == 0 : places.length() != 0))
      throw new IllegalStateException();
    for (int i = 0; i < places.length(); i++) {
      JSONObject place = places.getJSONObject(i);
      if (place.getBoolean("bookable")) throw new IllegalStateException();
      for (String field : new String[] {"parking", "childFriendly", "reservationAvailability"})
        if (!"UNKNOWN".equals(place.getString(field))) throw new IllegalStateException();
    }
    JSONObject plan = value.optJSONObject("recommendationPlan");
    if (plan == null) {
      if (status.equals("FILTERED")) throw new IllegalStateException();
      return;
    }
    if (!equivalent(requirements, plan.getJSONObject("requirements"))
        || !status.equals(plan.getString("state"))
        || !"MOCK".equals(plan.getString("provider"))
        || !fetched.equals(Instant.parse(plan.getString("listingFetchedAt")))
        || !expires.equals(Instant.parse(plan.getString("displayValidUntil"))))
      throw new IllegalStateException();
    JSONArray candidates = plan.getJSONArray("candidates");
    if (candidates.length() != places.length()) throw new IllegalStateException();
    for (int i = 0; i < candidates.length(); i++) {
      JSONObject c = candidates.getJSONObject(i), p = places.getJSONObject(i);
      if (c.getInt("ordinal") != i + 1
          || !c.getString("name").equals(p.getString("name"))
          || c.getBoolean("bookable")
          || c.getBoolean("inquiryDispatchAllowed")
          || !"NEEDS_CONFIRMATION".equals(c.getString("qualification")))
        throw new IllegalStateException();
      if (!java.util.Objects.equals(c.opt("cuisine"), p.opt("cuisine"))
          || !java.util.Objects.equals(c.opt("address"), p.opt("address"))
          || !Double.isFinite(c.getDouble("straightLineDistanceMeters"))
          || c.getDouble("straightLineDistanceMeters") < 0
          || Math.abs(c.getDouble("straightLineDistanceMeters") - p.getDouble("distanceMeters"))
              > 0.01) throw new IllegalStateException();
      allowedValues(
          c.getJSONArray("reasons"), Set.of("WITHIN_SEARCH_WINDOW", "PREFERRED_CUISINE_MATCH"));
      allowedValues(c.getJSONArray("missingInformation"), Set.of("ADDRESS", "CUISINE", "PHONE"));
      JSONArray questions = c.getJSONArray("questions");
      if (questions.length() < 2 || questions.length() > 6) throw new IllegalStateException();
      for (int n = 0; n < questions.length(); n++) {
        JSONObject q = questions.getJSONObject(n);
        String field = q.getString("field");
        if (!Set.of(
                    "parking",
                    "childFriendly",
                    "highChair",
                    "stepFreeAccess",
                    "arrivalWaitMinutes",
                    "reservationAvailable")
                .contains(field)
            || !"UNKNOWN".equals(q.getString("certainty"))) throw new IllegalStateException();
        boolean arrival =
            field.equals("arrivalWaitMinutes") || field.equals("reservationAvailable");
        if (!q.getString("scope").equals(arrival ? "ARRIVAL" : "GENERAL")
            || q.getBoolean("arrivalTimeRequired") != arrival) throw new IllegalStateException();
      }
    }
  }

  private static void allowedValues(JSONArray values, Set<String> allowed) throws Exception {
    for (int i = 0; i < values.length(); i++)
      if (!allowed.contains(values.getString(i))) throw new IllegalStateException();
  }

  // Android's org.json has no JSONObject.similar.
  private static boolean equivalent(Object a, Object b) throws Exception {
    if (a instanceof JSONObject x && b instanceof JSONObject y) {
      if (x.length() != y.length()) return false;
      var keys = x.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if (!y.has(key) || !equivalent(x.get(key), y.get(key))) return false;
      }
      return true;
    }
    if (a instanceof JSONArray x && b instanceof JSONArray y) {
      if (x.length() != y.length()) return false;
      // Requirements arrays are unique sets; the server canonicalizes their order.
      Set<String> left = new java.util.HashSet<>(), right = new java.util.HashSet<>();
      for (int i = 0; i < x.length(); i++) {
        left.add(x.getString(i));
        right.add(y.getString(i));
      }
      return left.size() == x.length() && right.size() == y.length() && left.equals(right);
    }
    return java.util.Objects.equals(a, b);
  }

  private void scheduleCleanup(Ticket ticket) {
    if (ticket == null || ticket.id == null || ticket.cleanupScheduled) return;
    ticket.cleanupScheduled = true;
    cleaning++;
    cleanup.execute(
        () -> {
          boolean failed = false;
          try {
            transport.exchange(
                ROOT + "/" + ticket.id + "/cancel",
                new JSONObject().put("generation", ticket.generation),
                null);
          } catch (Exception error) {
            failed = true;
          }
          try {
            transport.exchange(ROOT + "/" + ticket.id + "/close", new JSONObject(), null);
          } catch (Exception error) {
            failed = true;
          }
          synchronized (this) {
            cleaning--;
            cleanupFailed |= failed;
            changed.run();
          }
        });
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    allowed = false;
    invalidate("CLOSED");
    requests.shutdown();
    // Cleanup must also accept a session whose creation completes after close.
    Thread finisher =
        new Thread(
            () -> {
              try {
                // All IO is bounded by the transport. Keep cleanup available until even a
                // late create/401 refresh has finished; a fixed grace period can leak sessions.
                while (!requests.awaitTermination(1, java.util.concurrent.TimeUnit.MINUTES)) {}
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
              }
              synchronized (this) {
                cleanup.shutdown();
              }
            },
            "preview-cleanup");
    finisher.setDaemon(true);
    finisher.start();
  }
}
