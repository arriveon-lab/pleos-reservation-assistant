package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFailureTest {
  @ParameterizedTest
  @ValueSource(strings = {"INVALID_TIME", "STALE_NAVIGATION"})
  void rejectedSearchPreservesIntentAcrossRestartAndBlocksNewCommands(String code)
      throws Exception {
    JSONObject intent =
        new JSONObject()
            .put("path", "/searches")
            .put("key", UUID.randomUUID().toString())
            .put("target", "searchId")
            .put("body", new JSONObject().put("oldTime", "2020-01-01T00:00:00Z"));
    String[] saved = {
      new JSONObject()
          .put("tripId", UUID.randomUUID().toString())
          .put("authBinding", "same-subject")
          .put("pending", intent)
          .toString()
    };
    MockRepository.Journal journal =
        new MockRepository.Journal() {
          public String read() {
            return saved[0];
          }

          public void save(String value) {
            saved[0] = value;
          }
        };
    AtomicInteger posts = new AtomicInteger();
    MockRepository.Transport transport =
        new MockRepository.Transport() {
          public String binding() {
            return "same-subject";
          }

          public JSONObject exchange(String path, JSONObject body, String key) throws Exception {
            if (path.equals("/status"))
              return new JSONObject()
                  .put("mode", "MOCK")
                  .put("externalCallsEnabled", false)
                  .put("contractVersion", "1.1.0");
            posts.incrementAndGet();
            assertEquals(intent.getString("key"), key);
            assertEquals(intent.getJSONObject("body").toString(), body.toString());
            throw new AuthSession.HttpFailure(400, code);
          }
        };
    MockRepository first = new MockRepository(transport, journal);
    first.restrict(MockRepository.Restriction.SIMULATED_PARKED);
    first.connect();
    assertThrows(AuthSession.HttpFailure.class, first::retry);
    MockRepository restored = new MockRepository(transport, journal);
    restored.restrict(MockRepository.Restriction.SIMULATED_PARKED);
    restored.connect();
    JSONObject pending = restored.snapshot().getJSONObject("pending");
    assertEquals(code, pending.getString("recoveryCode"));
    pending.remove("recoveryCode");
    assertEquals(intent.toString(), pending.toString());
    assertEquals("same-subject", restored.snapshot().getString("authBinding"));
    assertFalse(restored.canCreateIdentity());
    assertThrows(IllegalStateException.class, () -> restored.search(2, 0, false));
    assertEquals(1, posts.get());
    assertThrows(AuthSession.HttpFailure.class, restored::retry);
    assertEquals(2, posts.get());
  }

  @Test
  void untrustedErrorTextIsNotExposed() {
    var failure = new AuthSession.HttpFailure(400, "secret arbitrary server message");
    assertEquals("", failure.code);
    assertEquals("HTTP_400", failure.getMessage());
  }
}
