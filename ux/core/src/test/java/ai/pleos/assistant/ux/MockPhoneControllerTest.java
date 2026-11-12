package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class MockPhoneControllerTest {
  static class Store implements MockPhoneController.Store {
    String value;
    boolean fail;

    public String read() {
      return value;
    }

    public void save(String value) throws Exception {
      if (fail) throw new Exception();
      this.value = value;
    }
  }

  static class Wire implements PreviewController.Transport {
    int posts, gets;
    boolean lost, mismatch, missing;
    String status = "CONFIRMED";
    JSONObject receipt;
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(0);

    public JSONObject exchange(String path, JSONObject body, String key) throws Exception {
      if (body == null) {
        gets++;
        if (missing) throw new AuthSession.HttpFailure(404, "NOT_FOUND");
        return receipt;
      }
      posts++;
      entered.countDown();
      release.await(2, TimeUnit.SECONDS);
      var time = Instant.parse(body.getString("reservationAt")).atZone(ZoneId.of("Asia/Seoul"));
      String requested = time.toLocalTime().toString();
      receipt =
          new JSONObject()
              .put("mode", "MOCK")
              .put("externalCalls", 0)
              .put("attemptId", UUID.randomUUID().toString().replace("-", ""))
              .put("candidateOrdinal", body.getInt("candidateOrdinal"))
              .put("partySize", 4)
              .put("date", time.toLocalDate().toString())
              .put("requestedTime", requested)
              .put("status", status)
              .put("bookingRequested", true)
              .put("conditionStatus", "MET")
              .put("availabilityStatus", "AVAILABLE")
              .put("confirmedTime", mismatch ? "00:00" : requested)
              .put(
                  "nextCandidate",
                  "DECLINED".equals(status) ? new JSONObject().put("ordinal", 2) : JSONObject.NULL);
      if (lost) throw new java.io.IOException();
      return receipt;
    }
  }

  static JSONObject body(int ordinal) throws Exception {
    return new JSONObject()
        .put("approved", true)
        .put("candidateOrdinal", ordinal)
        .put(
            "reservationAt",
            Instant.now()
                .plusSeconds(7200)
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                .toString())
        .put("reservationName", "가상 예약자")
        .put("conditionScenario", "MET")
        .put("availabilityScenario", "AVAILABLE")
        .put("bookingScenario", "ACCEPT");
  }

  static MockPhoneController controller(Wire w, Store s) {
    var c = new MockPhoneController(w, s, () -> {});
    c.allow(true);
    return c;
  }

  static void done(MockPhoneController c) throws Exception {
    for (int i = 0; i < 200 && c.busy(); i++) Thread.sleep(10);
    assertFalse(c.busy());
  }

  @Test
  void persistsBeforePostAndBlocksDuplicate() throws Exception {
    Wire w = new Wire();
    w.release = new CountDownLatch(1);
    Store s = new Store();
    try (var c = controller(w, s)) {
      String id = UUID.randomUUID().toString();
      JSONObject a = body(1);
      c.approve(id, a, 4);
      assertTrue(w.entered.await(2, TimeUnit.SECONDS));
      assertNotNull(s.value);
      assertEquals(a.toString(), new JSONObject(s.value).getJSONObject("approval").toString());
      c.approve(id, a, 4);
      w.release.countDown();
      done(c);
      assertEquals(1, w.posts);
      assertEquals("CONFIRMED", c.state());
      c.approve(UUID.randomUUID().toString(), a, 4);
      assertEquals(1, w.posts);
    }
  }

  @Test
  void lostResponseSurvivesRestartAndOnlyGets() throws Exception {
    Wire w = new Wire();
    w.lost = true;
    Store s = new Store();
    try (var c = controller(w, s)) {
      c.approve(UUID.randomUUID().toString(), body(1), 4);
      done(c);
      assertEquals("UNKNOWN", c.state());
    }
    try (var c = controller(w, s)) {
      assertEquals("UNKNOWN", c.state());
      c.refresh();
      done(c);
      assertEquals("CONFIRMED", c.state());
      assertEquals(1, w.posts);
      assertEquals(1, w.gets);
    }
  }

  @Test
  void unknownAnd404DoNotPermitNewRequest() throws Exception {
    Wire w = new Wire();
    w.status = "UNKNOWN";
    Store s = new Store();
    try (var c = controller(w, s)) {
      String id = UUID.randomUUID().toString();
      c.approve(id, body(1), 4);
      done(c);
      w.missing = true;
      c.refresh();
      done(c);
      assertEquals("UNKNOWN", c.state());
      assertFalse(c.canApprove(id, 2));
      assertEquals(1, w.posts);
    }
  }

  @Test
  void mismatchNeverConfirms() throws Exception {
    Wire w = new Wire();
    w.mismatch = true;
    try (var c = controller(w, new Store())) {
      c.approve(UUID.randomUUID().toString(), body(1), 4);
      done(c);
      assertEquals("UNKNOWN", c.state());
      assertFalse(c.receipt().has("attemptId"));
    }
  }

  @Test
  void failedStorageAndRestrictionsPreventPost() throws Exception {
    Wire w = new Wire();
    Store s = new Store();
    s.fail = true;
    try (var c = controller(w, s)) {
      c.approve(UUID.randomUUID().toString(), body(1), 4);
      done(c);
      assertEquals("STORAGE_ERROR", c.state());
      assertEquals(0, w.posts);
    }
    try (var c = controller(w, new Store())) {
      c.allow(false);
      c.approve(UUID.randomUUID().toString(), body(1), 4);
      assertEquals(0, w.posts);
    }
  }

  @Test
  void declineRequiresExactOfferedCandidateAndSameScope() throws Exception {
    Wire w = new Wire();
    w.status = "DECLINED";
    Store s = new Store();
    String id = UUID.randomUUID().toString();
    try (var c = controller(w, s)) {
      c.approve(id, body(1), 4);
      done(c);
      assertFalse(c.canApprove(id, 1));
      assertTrue(c.canApprove(id, 2));
      c.approve(id, body(2).put("reservationName", "변경"), 4);
      assertEquals(1, w.posts);
      String previous = c.receipt().getString("attemptId");
      w.status = "CONFIRMED";
      JSONObject a = c.approval().put("candidateOrdinal", 2);
      c.approve(id, a, 4);
      done(c);
      assertEquals(
          previous,
          new JSONObject(s.value).getJSONObject("approval").getString("previousAttemptId"));
      assertTrue(new JSONObject(s.value).has("previous"));
      assertEquals("CONFIRMED", c.state());
    }
  }
}
