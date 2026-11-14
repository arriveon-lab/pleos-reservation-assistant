package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ChangeCooldownTest {
  @Test
  void serverTimestampDefinesTenMinuteBoundary() {
    Instant start = Instant.parse("2026-11-10T10:00:00Z");
    assertEquals(
        start.plusSeconds(600),
        MockRepository.nextChangeAt(
            new JSONObject().put("lastChangeRequestedAt", start.toString())));
    assertNull(MockRepository.nextChangeAt(new JSONObject()));
  }

  @Test
  void prematureChangeDoesNotPersistOrDispatchNewIntent() throws Exception {
    AtomicInteger sends = new AtomicInteger(), saves = new AtomicInteger();
    MockRepository repository =
        new MockRepository(
            (p, b, k) -> {
              sends.incrementAndGet();
              return new JSONObject();
            },
            new MockRepository.Journal() {
              public String read() {
                return null;
              }

              public void save(String value) {
                saves.incrementAndGet();
              }
            });
    var field = MockRepository.class.getDeclaredField("state");
    field.setAccessible(true);
    field.set(
        repository,
        new JSONObject()
            .put("reservationId", "existing")
            .put(
                "reservation",
                new JSONObject()
                    .put("status", "CONFIRMED")
                    .put("lastChangeRequestedAt", Instant.now().toString())));
    assertEquals(
        "CHANGE_COOLDOWN",
        assertThrows(IllegalStateException.class, () -> repository.change(true)).getMessage());
    assertEquals(0, sends.get());
    assertEquals(0, saves.get());
    assertFalse(repository.snapshot().has("pending"));
  }
}
