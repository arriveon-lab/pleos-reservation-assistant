package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UnknownRefreshTest {
  static class Fixture implements MockRepository.Transport, MockRepository.Journal {
    String saved =
        new JSONObject()
            .put("tripId", UUID.randomUUID().toString())
            .put("searchId", "search")
            .put("reservationId", "reservation")
            .put(
                "pending",
                new JSONObject()
                    .put("key", "original-key")
                    .put("body", new JSONObject().put("original", true)))
            .toString();
    JSONObject reservation, call;
    boolean failCall, failSave;
    int writes;

    Fixture(boolean change) {
      reservation =
          new JSONObject()
              .put("mode", "MOCK")
              .put("id", "reservation")
              .put("searchId", "search")
              .put("bookingCallId", "booking")
              .put("status", change ? "CONFIRMED" : "UNKNOWN")
              .put("confirmedTime", change ? "2026-11-10T12:00:00Z" : JSONObject.NULL)
              .put("pendingChangeCallId", change ? "change" : JSONObject.NULL);
      call =
          new JSONObject()
              .put("mode", "MOCK")
              .put("id", change ? "change" : "booking")
              .put("reservationId", "reservation")
              .put("searchId", "search")
              .put("purpose", change ? "CHANGE" : "BOOKING")
              .put("status", "UNKNOWN");
    }

    public String read() {
      return saved;
    }

    public void save(String value) throws Exception {
      if (failSave) throw new IOException("storage");
      saved = value;
    }

    public JSONObject exchange(String path, JSONObject body, String key) throws Exception {
      if (body != null) {
        writes++;
        throw new AssertionError("Read-only check dispatched command");
      }
      if (path.equals("/status"))
        return new JSONObject()
            .put("mode", "MOCK")
            .put("contractVersion", "1.1.0")
            .put("externalCallsEnabled", false);
      if (path.equals("/searches/search"))
        return new JSONObject().put("mode", "MOCK").put("id", "search");
      if (path.equals("/reservations/reservation")) return new JSONObject(reservation.toString());
      if (path.equals("/calls/booking") || path.equals("/calls/change")) {
        if (failCall) throw new AuthSession.HttpFailure(404);
        return new JSONObject(call.toString());
      }
      throw new AssertionError("Unexpected path: " + path);
    }

    MockRepository ready() throws Exception {
      var repo = new MockRepository(this, this);
      repo.connect();
      return repo;
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unknownGetAndRestartPreserveIntentWithoutWrites(boolean change) throws Exception {
    Fixture f = new Fixture(change);
    String original = f.saved;
    var repo = f.ready();
    repo.refresh();
    assertEquals("UNKNOWN", repo.snapshot().getJSONObject("call").getString("status"));
    assertTrue(new JSONObject(original).similar(new JSONObject(f.saved)));
    var reopened = f.ready();
    assertTrue(repo.snapshot().similar(reopened.snapshot()));
    assertEquals(0, f.writes);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedCallGetCannotPublishPartialReservation(boolean change) throws Exception {
    Fixture f = new Fixture(change);
    var repo = f.ready();
    JSONObject old = repo.snapshot();
    String saved = f.saved;
    f.reservation.put("status", "REJECTED");
    f.failCall = true;
    assertThrows(AuthSession.HttpFailure.class, repo::refresh);
    assertTrue(old.similar(repo.snapshot()));
    assertEquals(saved, f.saved);
    assertEquals(0, f.writes);
  }

  @ParameterizedTest
  @ValueSource(strings = {"id", "reservationId", "searchId", "purpose"})
  void mismatchedCallNeverReplacesExistingEvidence(String field) throws Exception {
    Fixture f = new Fixture(true);
    var repo = f.ready();
    JSONObject old = repo.snapshot();
    String saved = f.saved;
    f.call.put(field, "wrong");
    assertThrows(IllegalStateException.class, repo::refresh);
    assertTrue(old.similar(repo.snapshot()));
    assertEquals(saved, f.saved);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedReferenceSaveKeepsPreviousSnapshot(boolean change) throws Exception {
    Fixture f = new Fixture(change);
    var repo = f.ready();
    JSONObject old = repo.snapshot();
    String saved = f.saved;
    f.reservation.put("status", "REJECTED");
    f.failSave = true;
    assertThrows(IOException.class, repo::refresh);
    assertTrue(old.similar(repo.snapshot()));
    assertEquals(saved, f.saved);
  }

  @ParameterizedTest
  @ValueSource(strings = {"id", "searchId"})
  void mismatchedReservationDoesNotPublish(String field) throws Exception {
    Fixture f = new Fixture(true);
    var repo = f.ready();
    JSONObject old = repo.snapshot();
    String saved = f.saved;
    f.reservation.put(field, "wrong");
    assertThrows(IllegalStateException.class, repo::refresh);
    assertTrue(old.similar(repo.snapshot()));
    assertEquals(saved, f.saved);
  }
}
