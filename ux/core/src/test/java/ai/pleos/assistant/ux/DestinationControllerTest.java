package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.jupiter.api.Test;

class DestinationControllerTest {
  static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
  static final DestinationController.Input INPUT =
      new DestinationController.Input(0, 2, 1, false, false);

  static class Wire implements PreviewController.Transport {
    String id = UUID.randomUUID().toString(), state = "RESULTS";
    AtomicInteger starts = new AtomicInteger(), closes = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(0);
    boolean lost, closeFailed, badId, permitted;
    int count = 2;
    int getFailure;
    JSONObject request;

    public JSONObject exchange(String path, JSONObject body, String key) throws Exception {
      if (path.endsWith("/close")) {
        closes.incrementAndGet();
        if (closeFailed) throw new java.io.IOException();
        return new JSONObject();
      }
      if (body != null) {
        request = new JSONObject(body.toString());
        starts.incrementAndGet();
        entered.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        if (lost) throw new java.io.IOException();
        return new JSONObject().put("taskId", id).put("state", "QUEUED");
      }
      if (getFailure != 0) throw new AuthSession.HttpFailure(getFailure, "NOT_FOUND");
      JSONArray candidates = new JSONArray();
      for (int i = 1; i <= count; i++) candidates.put(candidate(i).put("bookable", permitted));
      return new JSONObject()
          .put("id", badId ? UUID.randomUUID().toString() : id)
          .put("state", "RESULTS")
          .put("validUntil", NOW.plusSeconds(300).toString())
          .put("source", new JSONObject().put("provider", "WEB_SEARCH"))
          .put(
              "result",
              new JSONObject().put("webState", state).put("webRecommendations", candidates));
    }
  }

  static JSONObject candidate(int i) throws Exception {
    JSONObject evidence =
        new JSONObject()
            .put("url", "https://example.org/mock")
            .put("title", "가상 출처")
            .put("condition", "확인되지 않은 가상 조건")
            .put("observedAt", NOW.toString())
            .put("validUntil", NOW.plusSeconds(60).toString())
            .put("sourceType", "OTHER")
            .put("sourceUpdatedAt", JSONObject.NULL)
            .put("scope", "GENERAL")
            .put("appliesAt", JSONObject.NULL)
            .put("certainty", "REPORTED_WEB_NOT_MERCHANT_CONFIRMATION");
    return new JSONObject()
        .put("ordinal", i)
        .put("name", "가상 식당 " + i)
        .put("address", "가상 주소 " + i)
        .put("identitySourceUrl", "https://example.org/mock")
        .put("fit", "NEEDS_CONFIRMATION")
        .put("identityVerified", false)
        .put("bookable", false)
        .put("inquiryDispatchAllowed", false)
        .put("remainingQuestions", new JSONArray().put("PARKING"))
        .put("reviews", new JSONArray().put(evidence))
        .put(
            "assessments",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("need", "PARKING")
                        .put("verdict", "UNKNOWN")
                        .put("evidence", new JSONArray())));
  }

  static void await(java.util.function.BooleanSupplier test) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!test.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    assertTrue(test.getAsBoolean());
  }

  DestinationController controller(Wire w, AtomicLong nano) {
    var c = new DestinationController(w, () -> {}, Clock.fixed(NOW, ZoneOffset.UTC), nano::get);
    c.allow(true);
    return c;
  }

  @Test
  void twoAndThreeCandidatesAreVariableAndCopiesAreIsolated() throws Exception {
    for (int count : new int[] {2, 3}) {
      Wire w = new Wire();
      w.count = count;
      try (var c = controller(w, new AtomicLong())) {
        c.search(INPUT);
        await(() -> c.view().state().equals("RESULTS"));
        assertEquals(count, c.view().candidates().length());
        c.view().candidates().getJSONObject(0).put("bookable", true);
        assertFalse(c.view().candidates().getJSONObject(0).getBoolean("bookable"));
        assertEquals(1, w.starts.get());
      }
    }
  }

  @Test
  void duplicateWhileStartingDoesNotResubmit() throws Exception {
    Wire w = new Wire();
    w.release = new CountDownLatch(1);
    try (var c = controller(w, new AtomicLong())) {
      c.search(INPUT);
      assertTrue(w.entered.await(2, TimeUnit.SECONDS));
      c.search(INPUT);
      assertEquals(1, w.starts.get());
      w.release.countDown();
      await(() -> c.view().state().equals("RESULTS"));
    }
  }

  @Test
  void lateStartAndFailedCleanupNeverRestoreResults() throws Exception {
    Wire w = new Wire();
    w.release = new CountDownLatch(1);
    w.closeFailed = true;
    try (var c = controller(w, new AtomicLong())) {
      c.search(INPUT);
      assertTrue(w.entered.await(2, TimeUnit.SECONDS));
      c.invalidate("EDITING");
      w.release.countDown();
      await(() -> c.view().cleanupFailed());
      assertEquals("EDITING", c.view().state());
      assertEquals(0, c.view().candidates().length());
    }
  }

  @Test
  void lostResponseDoesNotAutomaticallyRetry() throws Exception {
    Wire w = new Wire();
    w.lost = true;
    try (var c = controller(w, new AtomicLong())) {
      c.search(INPUT);
      await(() -> c.view().state().equals("FAILED"));
      for (int i = 0; i < 5; i++) c.tick();
      assertEquals(1, w.starts.get());
      assertEquals(0, c.view().candidates().length());
    }
  }

  @Test
  void earliestEvidenceExpiryUsesMonotonicDeadline() throws Exception {
    Wire w = new Wire();
    AtomicLong nanos = new AtomicLong();
    try (var c = controller(w, nanos)) {
      c.search(INPUT);
      await(() -> c.view().state().equals("RESULTS"));
      nanos.set(TimeUnit.SECONDS.toNanos(61));
      c.tick();
      assertEquals("EXPIRED", c.view().state());
      assertEquals(0, c.view().candidates().length());
      assertEquals(1, w.starts.get());
    }
  }

  @Test
  void restrictionsClearAndBlockNewRequests() throws Exception {
    Wire w = new Wire();
    try (var c = controller(w, new AtomicLong())) {
      c.search(INPUT);
      await(() -> c.view().state().equals("RESULTS"));
      c.allow(false);
      c.search(INPUT);
      assertEquals("BLOCKED", c.view().state());
      assertEquals(0, c.view().candidates().length());
      assertEquals(1, w.starts.get());
    }
  }

  @Test
  void malformedReferenceOrActionPermissionIsRejected() throws Exception {
    for (boolean reference : new boolean[] {true, false}) {
      Wire w = new Wire();
      w.badId = reference;
      w.permitted = !reference;
      try (var c = controller(w, new AtomicLong())) {
        c.search(INPUT);
        await(() -> c.view().state().equals("FAILED"));
        assertEquals(0, c.view().candidates().length());
      }
    }
  }

  @Test
  void emptyFilteredFailedAndApprovalRequiredAreNotResults() throws Exception {
    for (String status :
        List.of("EMPTY", "FILTERED", "FAILED", "APPROVAL_REQUIRED", "ALREADY_REQUESTED")) {
      Wire w = new Wire();
      w.state = status;
      try (var c = controller(w, new AtomicLong())) {
        c.search(INPUT);
        await(() -> c.view().state().equals(status));
        assertEquals(0, c.view().candidates().length());
      }
    }
  }

  @Test
  void recreationDoesNotRestoreCandidatesOrAutoSearch() throws Exception {
    Wire w = new Wire();
    try (var c = controller(w, new AtomicLong())) {
      c.search(INPUT);
      await(() -> c.view().state().equals("RESULTS"));
    }
    try (var fresh = controller(w, new AtomicLong())) {
      fresh.tick();
      assertEquals("IDLE", fresh.view().state());
      assertEquals(0, fresh.view().candidates().length());
      assertEquals(1, w.starts.get());
    }
  }

  @Test
  void get404ClearsWithoutResubmission() throws Exception {
    Wire w = new Wire();
    w.getFailure = 404;
    try (var c = controller(w, new AtomicLong())) {
      c.search(INPUT);
      await(() -> c.view().state().equals("NOT_FOUND"));
      for (int i = 0; i < 3; i++) c.tick();
      assertEquals(0, c.view().candidates().length());
      assertEquals(1, w.starts.get());
    }
  }

  @Test
  void requestUsesCurrentContractAndInvalidReplacementClears() throws Exception {
    Wire w = new Wire();
    try (var c = controller(w, new AtomicLong())) {
      c.search(new DestinationController.Input(1, 20, 10, true, true));
      await(() -> c.view().state().equals("RESULTS"));
      assertEquals(20, w.request.getJSONObject("party").getInt("adults"));
      assertEquals(10, w.request.getJSONObject("party").getInt("children"));
      assertEquals(3, w.request.getJSONArray("requiredFeatures").length());
      assertFalse(w.request.has("eta"));
      assertFalse(w.request.has("arrivalAt"));
      c.search(new DestinationController.Input(0, 21, 0, false, false));
      assertEquals("INVALID_INPUT", c.view().state());
      assertEquals(0, c.view().candidates().length());
      assertEquals(1, w.starts.get());
    }
  }

  @Test
  void invalidInputDoesNotSend() throws Exception {
    Wire w = new Wire();
    try (var c = controller(w, new AtomicLong())) {
      c.search(new DestinationController.Input(0, 0, 1, false, false));
      assertEquals("INVALID_INPUT", c.view().state());
      assertEquals(0, w.starts.get());
    }
  }
}
