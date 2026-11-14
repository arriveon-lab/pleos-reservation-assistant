package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.UUID;
import org.json.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SearchReconciliationTest {
  final String id = UUID.randomUUID().toString();
  final JSONObject command =
      new JSONObject()
          .put("path", "/searches")
          .put("target", "searchId")
          .put("key", UUID.randomUUID().toString())
          .put("body", new JSONObject().put("original", "unchanged"));
  String saved =
      new JSONObject()
          .put("tripId", UUID.randomUUID().toString())
          .put("authBinding", "same-subject")
          .put("pending", command)
          .toString();
  final String original = saved;
  boolean failReceipt, lostResponse, failedGet;
  int reconciles, newSearches;
  JSONObject response =
      new JSONObject()
          .put("state", "REJECTED_CLOSED")
          .put("mode", "MOCK")
          .put("searchId", JSONObject.NULL)
          .put("reason", "INVALID_TIME")
          .put("resolvedAt", "2026-11-10T06:00:00Z");
  int errorStatus;
  MockRepository.Transport wire =
      new MockRepository.Transport() {
        public String binding() {
          return "same-subject";
        }

        public JSONObject exchange(String path, JSONObject body, String key) throws Exception {
          if (path.equals("/status"))
            return new JSONObject()
                .put("mode", "MOCK")
                .put("contractVersion", "1.1.0")
                .put("externalCallsEnabled", false);
          if (path.equals("/searches/reconcile")) {
            reconciles++;
            assertEquals(command.getString("key"), key);
            assertEquals(command.getJSONObject("body").toString(), body.toString());
            if (errorStatus != 0) throw new AuthSession.HttpFailure(errorStatus);
            if (lostResponse) {
              lostResponse = false;
              throw new IOException();
            }
            return new JSONObject(response.toString());
          }
          if (path.equals("/searches/" + id)) {
            if (failedGet) throw new IOException();
            return new JSONObject().put("mode", "MOCK").put("id", id);
          }
          if (path.equals("/searches")) {
            newSearches++;
            assertNotEquals(command.getString("key"), key);
            assertEquals(
                new JSONObject(original).getString("tripId"),
                body.getJSONObject("navigation").getString("tripId"));
            return new JSONObject().put("mode", "MOCK").put("id", id);
          }
          throw new AssertionError(path);
        }
      };

  MockRepository restore() throws Exception {
    var repository =
        new MockRepository(
            wire,
            new MockRepository.Journal() {
              public String read() {
                return saved;
              }

              public void save(String value) throws Exception {
                if (failReceipt && new JSONObject(value).has("searchReconciliations"))
                  throw new IOException();
                saved = value;
              }
            });
    repository.restrict(MockRepository.Restriction.SIMULATED_PARKED);
    repository.connect();
    return repository;
  }

  void preserved(MockRepository repository) throws Exception {
    assertEquals(command.toString(), repository.snapshot().getJSONObject("pending").toString());
    assertFalse(repository.snapshot().has("searchReconciliations"));
    assertThrows(IllegalStateException.class, () -> repository.search(2, 0, false));
  }

  @Test
  void closureIsAtomicAndAllowsOnlyExplicitNewSearch() throws Exception {
    var repository = restore();
    repository.reconcileSearch();
    assertFalse(repository.snapshot().has("pending"));
    var record = repository.snapshot().getJSONArray("searchReconciliations").getJSONObject(0);
    assertEquals(command.toString(), record.getJSONObject("command").toString());
    assertEquals("REJECTED_CLOSED", record.getJSONObject("result").getString("state"));
    assertEquals(0, newSearches);
    var restarted = restore();
    assertFalse(restarted.snapshot().has("pending"));
    restarted.search(2, 0, false);
    assertEquals(1, newSearches);
    assertEquals(1, restarted.snapshot().getJSONArray("searchReconciliations").length());
  }

  @Test
  void responseLossAndSaveFailureRetrySameReconciliation() throws Exception {
    var repository = restore();
    lostResponse = true;
    assertThrows(IOException.class, repository::reconcileSearch);
    preserved(repository);
    failReceipt = true;
    repository = restore();
    assertThrows(IOException.class, repository::reconcileSearch);
    preserved(repository);
    failReceipt = false;
    repository = restore();
    repository.reconcileSearch();
    assertEquals(3, reconciles);
    assertEquals(0, newSearches);
    assertEquals(1, repository.snapshot().getJSONArray("searchReconciliations").length());
  }

  @Test
  void completedRequiresGetBeforeReleasingPending() throws Exception {
    response.put("state", "COMPLETED").put("searchId", id).put("reason", JSONObject.NULL);
    var repository = restore();
    failedGet = true;
    assertThrows(IOException.class, repository::reconcileSearch);
    preserved(repository);
    failedGet = false;
    repository.reconcileSearch();
    assertEquals(id, repository.snapshot().getJSONObject("search").getString("id"));
    assertFalse(repository.snapshot().has("pending"));
    assertEquals(0, newSearches);
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 404, 409, 503})
  void errorsNeverReleasePending(int status) throws Exception {
    errorStatus = status;
    var repository = restore();
    assertThrows(AuthSession.HttpFailure.class, repository::reconcileSearch);
    preserved(repository);
    preserved(restore());
  }

  @ParameterizedTest
  @ValueSource(strings = {"state", "resolvedAt", "reason", "searchId", "mode"})
  void malformedReceiptNeverReleasesPending(String field) throws Exception {
    var repository = restore();
    response.put(field, "invalid");
    assertThrows(Exception.class, repository::reconcileSearch);
    preserved(repository);
  }

  @Test
  void restrictedAndNonSearchCommandsCannotReconcile() throws Exception {
    var repository = restore();
    repository.restrict(MockRepository.Restriction.UNKNOWN);
    assertThrows(IllegalStateException.class, repository::reconcileSearch);
    assertEquals(0, reconciles);
    JSONObject state = new JSONObject(saved);
    state.getJSONObject("pending").put("path", "/reservations/fixture/changes");
    saved = state.toString();
    repository = restore();
    assertThrows(IllegalStateException.class, repository::reconcileSearch);
    assertEquals(0, reconciles);
  }
}
