package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.json.*;
import org.junit.jupiter.api.*;

class PreviewControllerTest {
  // Exact PR #19 285087b example, with request identity and test clock replaced.
  static final Instant NOW = Instant.parse("2026-11-09T01:00:00Z");
  static final PreviewController.Input INPUT =
      new PreviewController.Input(2, 1, 15, true, false, "한식", 0, 1000);
  final AtomicReference<Instant> now = new AtomicReference<>(NOW);
  final AtomicLong elapsed = new AtomicLong();
  final List<PreviewController> controllers = new ArrayList<>();

  static JSONObject fixture(String id, long generation) throws Exception {
    try (var stream =
        PreviewControllerTest.class.getResourceAsStream("/preview/recommendation-result.json")) {
      return new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          .put("previewId", id)
          .put("generation", generation);
    }
  }

  class Wire implements PreviewController.Transport {
    final AtomicInteger queries = new AtomicInteger(),
        creates = new AtomicInteger(),
        closes = new AtomicInteger(),
        cancels = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(0);
    boolean holdCreate, cleanupFailure;
    AuthSession.HttpFailure cleanupHttp;
    Consumer<JSONObject> mutation = v -> {};

    public JSONObject exchange(String path, JSONObject body, String key) throws Exception {
      if (path.equals("/status"))
        return new JSONObject()
            .put("contractVersion", "1.1.0")
            .put("mode", "MOCK")
            .put("externalCallsEnabled", false);
      if (path.equals(PreviewController.ROOT)) {
        creates.incrementAndGet();
        if (holdCreate) {
          entered.countDown();
          assertTrue(release.await(5, TimeUnit.SECONDS));
        }
        return new JSONObject().put("previewId", UUID.randomUUID().toString()).put("mode", "MOCK");
      }
      if (path.endsWith("/cancel")) {
        cancels.incrementAndGet();
        if (cleanupHttp != null) throw cleanupHttp;
        if (cleanupFailure) throw new IOException();
        return new JSONObject();
      }
      if (path.endsWith("/close")) {
        closes.incrementAndGet();
        if (cleanupHttp != null) throw cleanupHttp;
        return new JSONObject();
      }
      CountDownLatch waiting = release;
      queries.incrementAndGet();
      entered.countDown();
      assertTrue(waiting.await(5, TimeUnit.SECONDS));
      String[] parts = path.split("/");
      JSONObject result = fixture(parts[parts.length - 2], body.getLong("generation"));
      result
          .getJSONObject("recommendationPlan")
          .put("requirements", new JSONObject(body.getJSONObject("requirements").toString()));
      mutation.accept(result);
      return result;
    }
  }

  PreviewController controller(Wire wire) {
    Clock clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return now.get();
          }
        };
    PreviewController c = new PreviewController(wire, () -> {}, clock, elapsed::get);
    controllers.add(c);
    c.allow(true);
    return c;
  }

  static void await(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    assertTrue(condition.getAsBoolean(), "condition timed out");
  }

  @AfterEach
  void stop() {
    controllers.forEach(PreviewController::close);
  }

  @Test
  void displaysThreeCandidatesAndUnknownQuestions() throws Exception {
    Wire w = new Wire();
    var c = controller(w);
    c.search(INPUT);
    await(() -> c.view().result() != null);
    assertEquals("RESULTS", c.view().state());
    assertEquals(3, c.view().result().getJSONArray("places").length());
    assertEquals(
        "UNKNOWN",
        c.view()
            .result()
            .getJSONObject("recommendationPlan")
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONArray("questions")
            .getJSONObject(0)
            .getString("certainty"));
  }

  @Test
  void canonicalRequirementsOrderIsAccepted() throws Exception {
    Wire w = new Wire();
    w.mutation =
        v ->
            v.getJSONObject("recommendationPlan")
                .getJSONObject("requirements")
                .put("requiredFeatures", new JSONArray().put("childFriendly").put("parking"))
                .put("preferredCuisines", new JSONArray().put("양식").put("한식"));
    var c = controller(w);
    c.search(new PreviewController.Input(2, 1, 15, true, true, "한식,양식", 0, 1000));
    await(() -> !c.view().state().equals("LOADING"));
    assertEquals("RESULTS", c.view().state());
  }

  @Test
  void duplicateClickJoinsNothingAndSendsOneQuery() throws Exception {
    Wire w = new Wire();
    w.release = new CountDownLatch(1);
    var c = controller(w);
    c.search(INPUT);
    assertTrue(w.entered.await(2, TimeUnit.SECONDS));
    c.search(INPUT);
    assertEquals(1, w.queries.get());
    w.release.countDown();
    await(() -> c.view().result() != null);
    assertEquals(1, w.creates.get());
  }

  @Test
  void editHidesLateResultEvenWhenCancellationFails() throws Exception {
    Wire w = new Wire();
    w.release = new CountDownLatch(1);
    w.cleanupFailure = true;
    var c = controller(w);
    c.search(INPUT);
    assertTrue(w.entered.await(2, TimeUnit.SECONDS));
    c.invalidate("EDITING");
    await(() -> w.closes.get() == 1 && !c.view().cleanupPending());
    assertTrue(c.view().cleanupFailed());
    assertNull(c.view().result());
    w.release.countDown();
    Thread.sleep(100);
    assertEquals("EDITING", c.view().state());
    assertNull(c.view().result());
    assertEquals(1, w.queries.get());
  }

  @Test
  void lateSessionCreationIsClosedWithoutQuery() throws Exception {
    Wire w = new Wire();
    w.holdCreate = true;
    w.release = new CountDownLatch(1);
    var c = controller(w);
    c.search(INPUT);
    assertTrue(w.entered.await(2, TimeUnit.SECONDS));
    c.close();
    w.release.countDown();
    await(() -> w.closes.get() == 1);
    assertEquals(0, w.queries.get());
    assertNull(c.view().result());
  }

  @Test
  void obsoleteGenerationCannotReplaceNewResult() throws Exception {
    Wire w = new Wire();
    w.release = new CountDownLatch(1);
    var c = controller(w);
    c.search(INPUT);
    assertTrue(w.entered.await(2, TimeUnit.SECONDS));
    c.invalidate("EDITING");
    CountDownLatch oldRelease = w.release;
    // The first worker is already waiting on the old latch; the next request is independent.
    w.release = new CountDownLatch(0);
    c.search(new PreviewController.Input(3, 0, 20, false, false, "양식", 1, 500));
    await(() -> c.view().result() != null);
    long generation = c.view().result().getLong("generation");
    assertEquals(2, generation);
    oldRelease.countDown();
    Thread.sleep(100);
    assertEquals(generation, c.view().result().getLong("generation"));
    assertEquals(
        3,
        c.view()
            .result()
            .getJSONObject("recommendationPlan")
            .getJSONObject("requirements")
            .getJSONObject("party")
            .getInt("adults"));
  }

  @Test
  void restrictionInvalidatesAndBlocksNewRequests() throws Exception {
    Wire w = new Wire();
    var c = controller(w);
    c.search(INPUT);
    await(() -> c.view().result() != null);
    c.allow(false);
    c.search(INPUT);
    assertEquals("RESTRICTED", c.view().state());
    assertNull(c.view().result());
    assertEquals(1, w.queries.get());
  }

  @Test
  void wallClockExpiryHidesWithoutAutomaticResearch() throws Exception {
    Wire w = new Wire();
    var c = controller(w);
    c.search(INPUT);
    await(() -> c.view().result() != null);
    now.set(NOW.plusSeconds(300));
    c.tick();
    assertEquals("EXPIRED", c.view().state());
    assertNull(c.view().result());
    assertEquals(1, w.queries.get());
  }

  @Test
  void elapsedTimeExpiresWhileWallClockIsUnchanged() throws Exception {
    Wire w = new Wire();
    var c = controller(w);
    c.search(INPUT);
    await(() -> c.view().result() != null);
    elapsed.set(TimeUnit.MINUTES.toNanos(5));
    c.tick();
    assertEquals("EXPIRED", c.view().state());
  }

  @Test
  void backwardClockIsUncertain() throws Exception {
    Wire w = new Wire();
    var c = controller(w);
    c.search(INPUT);
    await(() -> c.view().result() != null);
    now.set(NOW.minusSeconds(3));
    assertEquals("CLOCK_UNCERTAIN", c.view().state());
    assertNull(c.view().result());
  }

  @Test
  void distinguishesEmptyFilteredAndFailed() throws Exception {
    for (String state : List.of("EMPTY", "FILTERED", "FAILED")) {
      Wire w = new Wire();
      w.mutation =
          v -> {
            v.put("state", state).put("places", new JSONArray());
            if (state.equals("FAILED"))
              v.put("recommendationPlan", JSONObject.NULL)
                  .put("fetchedAt", JSONObject.NULL)
                  .put("expiresAt", JSONObject.NULL)
                  .put("failure", "UPSTREAM_UNAVAILABLE");
            else
              v.getJSONObject("recommendationPlan")
                  .put("state", state)
                  .put("candidates", new JSONArray());
          };
      var c = controller(w);
      c.search(INPUT);
      await(() -> !c.view().state().equals("LOADING"));
      assertEquals(state, c.view().state());
      c.invalidate("CLOSED");
    }
  }

  @Test
  void unsafeOrMismatchedResponsesAreRejected() throws Exception {
    List<Consumer<JSONObject>> mutations =
        List.of(
            v -> v.put("mode", "LIVE"),
            v -> v.put("generation", 999),
            v ->
                v.getJSONObject("recommendationPlan")
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .put("bookable", true),
            v ->
                v.getJSONObject("recommendationPlan")
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .put("inquiryDispatchAllowed", true),
            v ->
                v.getJSONObject("recommendationPlan")
                    .getJSONObject("requirements")
                    .put("maxWaitMinutes", 100),
            v ->
                v.getJSONObject("recommendationPlan")
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONArray("questions")
                    .getJSONObject(0)
                    .put("certainty", "CONFIRMED"));
    for (var mutation : mutations) {
      Wire w = new Wire();
      w.mutation = mutation;
      var c = controller(w);
      c.search(INPUT);
      await(() -> !c.view().state().equals("LOADING"));
      assertEquals("INVALID_RESPONSE", c.view().state());
      assertNull(c.view().result());
    }
  }

  @Test
  void missingCleanupIsNotSuccessAndNeverRestoresExpiredResults() throws Exception {
    Wire w = new Wire();
    w.cleanupHttp = new AuthSession.HttpFailure(404, "PREVIEW_NOT_FOUND");
    var c = controller(w);
    c.search(INPUT);
    await(() -> c.view().result() != null);
    now.set(NOW.plusSeconds(301));
    c.tick();
    await(() -> !c.view().cleanupPending());
    assertEquals("EXPIRED", c.view().state());
    assertNull(c.view().result());
    assertTrue(c.view().cleanupMissing());
    assertFalse(c.view().cleanupFailed());
    assertEquals(1, w.queries.get());
    assertEquals(1, w.cancels.get());
    assertEquals(1, w.closes.get());
  }

  @Test
  void unclassified404RemainsCleanupFailure() throws Exception {
    Wire w = new Wire();
    w.cleanupHttp = new AuthSession.HttpFailure(404);
    var c = controller(w);
    c.search(INPUT);
    await(() -> c.view().result() != null);
    c.invalidate("EDITING");
    await(() -> !c.view().cleanupPending());
    assertTrue(c.view().cleanupFailed());
    assertFalse(c.view().cleanupMissing());
    assertNull(c.view().result());
  }

  @Test
  void expiredResponseNeverAppears() throws Exception {
    Wire w = new Wire();
    now.set(NOW.plusSeconds(301));
    var c = controller(w);
    c.search(INPUT);
    await(() -> !c.view().state().equals("LOADING"));
    assertEquals("EXPIRED", c.view().state());
    assertNull(c.view().result());
  }

  @Test
  void inputBoundsAndNoAutomaticRequestOnInvalidInput() throws Exception {
    Wire w = new Wire();
    var c = controller(w);
    c.search(new PreviewController.Input(0, 1, 15, true, false, "한식", 0, 1000));
    assertEquals("INVALID_INPUT", c.view().state());
    assertEquals(0, w.creates.get());
    assertThrows(
        IllegalArgumentException.class,
        () -> new PreviewController.Input(2, 1, 121, true, false, "한식", 0, 1000).body(1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PreviewController.Input(2, 1, 15, true, false, "한식,한식", 0, 1000).body(1));
  }
}
