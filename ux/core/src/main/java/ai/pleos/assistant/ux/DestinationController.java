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

/** Transient #53 client. Never writes reservation journals or promotes a candidate to a booking. */
public final class DestinationController implements AutoCloseable {
  public static final String ROOT = "/experimental/destination-recommendations";

  public record Input(
      int destination,
      int adults,
      int children,
      boolean highChair,
      boolean stepFree,
      java.util.List<String> preferredCuisines) {
    public Input {
      preferredCuisines = java.util.List.copyOf(preferredCuisines);
    }

    public Input(int destination, int adults, int children, boolean highChair, boolean stepFree) {
      this(destination, adults, children, highChair, stepFree, java.util.List.of());
    }

    JSONObject body(String view, long generation) throws Exception {
      if (destination < 0
          || destination > 1
          || adults < 1
          || adults > 20
          || children < 0
          || children > 10) throw new IllegalArgumentException("INVALID_INPUT");
      if (preferredCuisines.size() > 8
          || new java.util.HashSet<>(preferredCuisines).size() != preferredCuisines.size())
        throw new IllegalArgumentException("INVALID_INPUT");
      for (String cuisine : preferredCuisines) {
        if (cuisine.isBlank()
            || cuisine.length() > 30
            || cuisine.codePoints().anyMatch(Character::isISOControl))
          throw new IllegalArgumentException("INVALID_INPUT");
      }
      JSONArray features = new JSONArray().put("parking");
      if (highChair) features.put("highChair");
      if (stepFree) features.put("stepFreeAccess");
      return new JSONObject()
          .put("viewId", view)
          .put("generation", generation)
          .put("userRequest", "주차 가능한 식당 추천")
          .put(
              "destination",
              new JSONObject()
                  .put("name", destination == 0 ? "가상 성수" : "가상 서울숲")
                  .put("latitude", destination == 0 ? 37.544 : 37.545)
                  .put("longitude", destination == 0 ? 127.055 : 127.043))
          .put("party", new JSONObject().put("adults", adults).put("children", children))
          .put("maxWaitMinutes", 15)
          .put("requiredFeatures", features)
          .put("preferredCuisines", new JSONArray(preferredCuisines));
    }
  }

  public record View(String state, JSONArray candidates, boolean cleanupFailed) {}

  private final PreviewController.Transport wire;
  private final Runnable changed;
  private final Clock clock;
  private final LongSupplier nano;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
  private long epoch, generation, expiresNanos;
  private String viewId, taskId, state = "IDLE";
  private Instant expires;
  private JSONArray candidates = new JSONArray();
  private boolean allowed, closed, polling, cleanupFailed;

  public DestinationController(
      PreviewController.Transport wire, Runnable changed, Clock clock, LongSupplier nano) {
    this.wire = wire;
    this.changed = changed;
    this.clock = clock;
    this.nano = nano;
  }

  public synchronized View view() {
    try {
      return new View(state, new JSONArray(candidates.toString()), cleanupFailed);
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public synchronized void allow(boolean value) {
    allowed = value;
    if (!value && (viewId != null || candidates.length() > 0)) invalidate("BLOCKED");
  }

  public synchronized void search(Input input) {
    if (closed || !allowed || Set.of("LOADING", "QUEUED", "RUNNING").contains(state)) return;
    JSONObject body;
    String view = UUID.randomUUID().toString();
    try {
      body = input.body(view, ++generation);
    } catch (Exception invalid) {
      invalidate("INVALID_INPUT");
      return;
    }
    invalidate("IDLE");
    viewId = view;
    long token = epoch;
    expires = clock.instant().plusSeconds(300);
    expiresNanos = nano.getAsLong() + Duration.ofMinutes(5).toNanos();
    state = "LOADING";
    polling = true;
    changed.run();
    io.execute(
        () -> {
          try {
            JSONObject start = wire.exchange(ROOT, body, null);
            String id = UUID.fromString(start.getString("taskId")).toString();
            synchronized (this) {
              if (!current(token)) {
                closeRemote(view);
                return;
              }
              taskId = id;
              polling = false;
              state = "QUEUED";
              changed.run();
            }
            tick();
          } catch (Exception error) {
            fail(token, error);
            closeRemote(view);
          }
        });
  }

  public synchronized void tick() {
    if (closed) return;
    if (expires != null
        && (!clock.instant().isBefore(expires) || nano.getAsLong() >= expiresNanos)) {
      invalidate("EXPIRED");
      return;
    }
    if (taskId == null || polling || !Set.of("QUEUED", "RUNNING").contains(state)) return;
    long token = epoch;
    String id = taskId;
    polling = true;
    io.execute(
        () -> {
          try {
            JSONObject response = wire.exchange(ROOT + "/" + id, null, null);
            synchronized (this) {
              if (!current(token)) return;
              if (!id.equals(response.getString("id")))
                throw new IllegalArgumentException("REFERENCE_MISMATCH");
              tighten(Instant.parse(response.getString("validUntil")));
              String next = response.getString("state");
              if (!Set.of("QUEUED", "RUNNING", "RESULTS", "FAILED", "CANCELLED").contains(next))
                throw new IllegalArgumentException();
              JSONArray found = new JSONArray();
              if ("RESULTS".equals(next)) {
                if (!"WEB_SEARCH".equals(response.getJSONObject("source").getString("provider")))
                  throw new IllegalArgumentException();
                JSONObject result = response.getJSONObject("result");
                next = result.getString("webState");
                if (!Set.of(
                        "RESULTS",
                        "EMPTY",
                        "FILTERED",
                        "FAILED",
                        "CANCELLED",
                        "EXPIRED",
                        "APPROVAL_REQUIRED",
                        "ALREADY_REQUESTED")
                    .contains(next)) throw new IllegalArgumentException();
                if ("RESULTS".equals(next)) {
                  found = result.getJSONArray("webRecommendations");
                  validate(found);
                  if (found.length() == 0) next = "EMPTY";
                }
              }
              if (!clock.instant().isBefore(expires) || nano.getAsLong() >= expiresNanos) {
                invalidate("EXPIRED");
                return;
              }
              candidates = found;
              state = next;
              polling = false;
              changed.run();
            }
          } catch (Exception error) {
            fail(token, error);
          }
        });
  }

  private void validate(JSONArray found) throws Exception {
    if (found.length() > 3) throw new IllegalArgumentException();
    Set<Integer> seen = new java.util.HashSet<>();
    for (int i = 0; i < found.length(); i++) {
      JSONObject c = found.getJSONObject(i);
      int ordinal = c.getInt("ordinal");
      if (ordinal < 1
          || ordinal > 3
          || !seen.add(ordinal)
          || c.getBoolean("identityVerified")
          || c.getBoolean("bookable")
          || c.getBoolean("inquiryDispatchAllowed")) throw new IllegalArgumentException();
      text(c, "name", 200);
      text(c, "address", 300);
      url(c.getString("identitySourceUrl"));
      if (!Set.of("POTENTIAL_MATCH", "NEEDS_CONFIRMATION").contains(c.getString("fit")))
        throw new IllegalArgumentException();
      JSONArray assessments = c.getJSONArray("assessments");
      if (assessments.length() > 6) throw new IllegalArgumentException();
      Set<String> needs = new java.util.HashSet<>();
      for (int j = 0; j < assessments.length(); j++) {
        JSONObject a = assessments.getJSONObject(j);
        if (!Set.of(
                    "PARKING",
                    "CHILD_FRIENDLY",
                    "HIGH_CHAIR",
                    "STEP_FREE_ACCESS",
                    "PET_FRIENDLY",
                    "INDOOR_PET_FRIENDLY")
                .contains(a.getString("need"))
            || !Set.of("YES", "NO", "UNKNOWN", "CONFLICT").contains(a.getString("verdict")))
          throw new IllegalArgumentException();
        if (!needs.add(a.getString("need"))) throw new IllegalArgumentException();
        if (Set.of("YES", "NO", "CONFLICT").contains(a.getString("verdict"))
            && a.getJSONArray("evidence").length() == 0) throw new IllegalArgumentException();
        evidence(a.getJSONArray("evidence"));
      }
      if (!needs.contains("PARKING")) throw new IllegalArgumentException();
      JSONArray questions = c.getJSONArray("remainingQuestions");
      if (questions.length() > 6) throw new IllegalArgumentException();
      for (int j = 0; j < questions.length(); j++) {
        if (!Set.of(
                "PARKING",
                "CHILD_FRIENDLY",
                "HIGH_CHAIR",
                "STEP_FREE_ACCESS",
                "PET_FRIENDLY",
                "INDOOR_PET_FRIENDLY")
            .contains(questions.getString(j))) throw new IllegalArgumentException();
      }
      evidence(c.getJSONArray("reviews"));
    }
  }

  private void evidence(JSONArray list) throws Exception {
    if (list.length() > 12) throw new IllegalArgumentException();
    for (int i = 0; i < list.length(); i++) {
      JSONObject e = list.getJSONObject(i);
      url(e.getString("url"));
      text(e, "title", 200);
      text(e, "condition", 240);
      if (!Set.of("MERCHANT_PAGE", "VISITOR_REVIEW", "OTHER").contains(e.getString("sourceType")))
        throw new IllegalArgumentException();
      if (!e.isNull("sourceUpdatedAt")) Instant.parse(e.getString("sourceUpdatedAt"));
      if (!"GENERAL".equals(e.getString("scope"))
          || !e.isNull("appliesAt")
          || !"REPORTED_WEB_NOT_MERCHANT_CONFIRMATION".equals(e.getString("certainty")))
        throw new IllegalArgumentException();
      Instant observed = Instant.parse(e.getString("observedAt"));
      Instant valid = Instant.parse(e.getString("validUntil"));
      if (!valid.isAfter(observed) || observed.isAfter(clock.instant().plusSeconds(5)))
        throw new IllegalArgumentException();
      tighten(valid);
    }
  }

  private static void text(JSONObject o, String key, int max) throws Exception {
    String s = o.getString(key);
    if (s.isBlank() || s.length() > max || s.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException();
  }

  private static void url(String value) {
    java.net.URI u = java.net.URI.create(value);
    if (!Set.of("http", "https").contains(u.getScheme())
        || u.getHost() == null
        || u.getUserInfo() != null) throw new IllegalArgumentException();
  }

  private void tighten(Instant value) {
    if (value.isBefore(expires)) {
      expires = value;
      expiresNanos =
          Math.min(
              expiresNanos,
              nano.getAsLong() + Math.max(0, Duration.between(clock.instant(), value).toNanos()));
    }
  }

  private synchronized boolean current(long token) {
    return !closed && token == epoch && allowed;
  }

  private synchronized void fail(long token, Exception error) {
    if (!current(token)) return;
    String next =
        error instanceof AuthSession.HttpFailure h && h.status == 404 ? "NOT_FOUND" : "FAILED";
    invalidate(next);
  }

  public synchronized void invalidate(String reason) {
    String old = viewId;
    ++epoch;
    viewId = null;
    taskId = null;
    expires = null;
    candidates = new JSONArray();
    polling = false;
    state = reason;
    changed.run();
    if (old != null) closeRemote(old);
  }

  private void closeRemote(String view) {
    if (cleanup.isShutdown()) return;
    cleanup.execute(
        () -> {
          try {
            wire.exchange(ROOT + "/views/" + view + "/close", new JSONObject(), null);
          } catch (Exception ignored) {
            synchronized (this) {
              cleanupFailed = true;
              changed.run();
            }
          }
        });
  }

  @Override
  public synchronized void close() {
    invalidate("CLOSED");
    closed = true;
    io.shutdown();
    cleanup.shutdown();
  }
}
