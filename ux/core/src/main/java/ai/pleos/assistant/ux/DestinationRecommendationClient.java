package ai.pleos.assistant.ux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Memory-only caller. Route changes clear the view before best-effort server cancellation. */
public final class DestinationRecommendationClient implements AutoCloseable {
  public static final String ROOT = "/experimental/destination-recommendations";

  public interface Transport {
    JSONObject exchange(String path, JSONObject body, String key) throws Exception;
  }

  public record View(String state, JSONObject result) {}

  private record RequestTicket(DestinationRecommendationState.Ticket destination, UUID viewId) {}

  private final Transport transport;
  private final DestinationRecommendationState destination;
  private final Runnable changed;
  private final Clock clock;
  private final LongSupplier elapsed;
  private final ExecutorService requests = Executors.newSingleThreadExecutor();
  private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
  private final Set<UUID> closeQueued = ConcurrentHashMap.newKeySet();
  private RequestTicket active;
  private String state = "IDLE";
  private JSONObject result;
  private Instant receivedAt;
  private long receivedTick;
  private long validNanos;
  private boolean closed;

  public DestinationRecommendationClient(
      Transport transport,
      DestinationRecommendationState destination,
      Runnable changed,
      Clock clock,
      LongSupplier elapsed) {
    this.transport = transport;
    this.destination = destination;
    this.changed = changed;
    this.clock = clock;
    this.elapsed = elapsed;
  }

  public synchronized View view() {
    expire();
    return new View(state, result == null ? null : new JSONObject(result.toString()));
  }

  public synchronized void tick() {
    expire();
  }

  public synchronized void search(String userRequest, int adults, int children) {
    if (closed || userRequest == null || userRequest.isBlank() || userRequest.length() > 300) {
      state = "INVALID_INPUT";
      changed.run();
      return;
    }
    var observed = destination.beginRecommendation();
    if (observed == null) {
      state = "NO_DESTINATION";
      changed.run();
      return;
    }
    invalidate("LOADING");
    var ticket = new RequestTicket(observed, UUID.randomUUID());
    active = ticket;
    JSONObject body =
        new JSONObject()
            .put("viewId", ticket.viewId().toString())
            .put("generation", ticket.destination().requestGeneration())
            .put("userRequest", userRequest)
            .put(
                "destination",
                new JSONObject()
                    .put("name", ticket.destination().destination().name())
                    .put("latitude", ticket.destination().destination().latitude())
                    .put("longitude", ticket.destination().destination().longitude()))
            .put("party", new JSONObject().put("adults", adults).put("children", children))
            .put("maxWaitMinutes", 15)
            .put("requiredFeatures", new JSONArray())
            .put("preferredCuisines", new JSONArray());
    requests.execute(() -> run(ticket, body));
  }

  public synchronized void invalidate(String reason) {
    if (active != null) scheduleClose(active);
    active = null;
    result = null;
    state = reason;
    changed.run();
  }

  private void run(RequestTicket ticket, JSONObject body) {
    try {
      if (!current(ticket)) return;
      JSONObject started = transport.exchange(ROOT, body, null);
      String id = UUID.fromString(started.getString("taskId")).toString();
      if (!current(ticket)) {
        scheduleClose(ticket);
        return;
      }
      for (int attempt = 0; attempt < 60 && current(ticket); attempt++) {
        JSONObject snapshot = transport.exchange(ROOT + "/" + id, null, null);
        String jobState = snapshot.getString("state");
        if ("RESULTS".equals(jobState)) {
          accept(ticket, snapshot);
          return;
        }
        if (!"QUEUED".equals(jobState) && !"RUNNING".equals(jobState)) {
          fail(ticket, jobState);
          return;
        }
        Thread.sleep(500);
      }
      fail(ticket, "TIMED_OUT");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception failure) {
      fail(
          ticket,
          failure instanceof AuthSession.HttpFailure ? "NETWORK_FAILED" : "INVALID_RESPONSE");
    }
  }

  private synchronized void accept(RequestTicket ticket, JSONObject snapshot) {
    if (!current(ticket)) {
      scheduleClose(ticket);
      return;
    }
    JSONObject recommendation = snapshot.getJSONObject("result");
    JSONObject listing = recommendation.getJSONObject("listing");
    JSONArray candidates = listing.getJSONArray("candidates");
    if (candidates.length() > 3 || !"RESULTS".equals(listing.getString("state"))) {
      fail(ticket, "INVALID_RESPONSE");
      return;
    }
    for (int i = 0; i < candidates.length(); i++) {
      JSONObject candidate = candidates.getJSONObject(i);
      if (candidate.getBoolean("bookable") || candidate.getBoolean("inquiryDispatchAllowed")) {
        fail(ticket, "INVALID_RESPONSE");
        return;
      }
    }
    Instant until = Instant.parse(snapshot.getString("validUntil"));
    if (listing.has("displayValidUntil") && !listing.isNull("displayValidUntil")) {
      Instant listingUntil = Instant.parse(listing.getString("displayValidUntil"));
      if (listingUntil.isBefore(until)) until = listingUntil;
    }
    if (!clock.instant().isBefore(until)) {
      fail(ticket, "EXPIRED");
      return;
    }
    receivedAt = clock.instant();
    receivedTick = elapsed.getAsLong();
    validNanos = Duration.between(receivedAt, until).toNanos();
    result = snapshot;
    state = "RESULTS";
    changed.run();
  }

  private synchronized boolean current(RequestTicket ticket) {
    return !closed && active == ticket && destination.isCurrent(ticket.destination());
  }

  private synchronized void fail(RequestTicket ticket, String code) {
    if (!current(ticket)) return;
    result = null;
    state = code;
    scheduleClose(ticket);
    changed.run();
  }

  private boolean expire() {
    if (result == null) return false;
    if (clock.instant().isBefore(receivedAt.minusSeconds(2))
        || elapsed.getAsLong() - receivedTick >= validNanos) {
      invalidate("EXPIRED");
      return true;
    }
    return false;
  }

  private void scheduleClose(RequestTicket ticket) {
    if (closed || !closeQueued.add(ticket.viewId())) return;
    cleanup.execute(
        () -> {
          try {
            transport.exchange(
                ROOT + "/views/" + ticket.viewId() + "/close", new JSONObject(), null);
          } catch (Exception ignored) {
            // Local result was already discarded. Server jobs expire independently.
          }
        });
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    invalidate("CLOSED");
    closed = true;
    requests.shutdownNow();
    cleanup.shutdown();
  }
}
