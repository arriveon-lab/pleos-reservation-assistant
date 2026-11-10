package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class DestinationRecommendationClientTest {
  @Test
  void routeChangeDiscardsLateResultsAndClosesView() throws Exception {
    var destination = new DestinationRecommendationState();
    destination.observeDestination(
        new DestinationRecommendationState.Destination(
            37.5, 127.0, "가상 목적지", "poi", Instant.now()));
    var requested = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closes = new AtomicInteger();
    DestinationRecommendationClient.Transport transport =
        (path, body, key) -> {
          if (path.endsWith("/close")) {
            closes.incrementAndGet();
            return new JSONObject().put("state", "CLOSED");
          }
          if (body != null) {
            assertEquals("주차 가능한 식당", body.getString("userRequest"));
            return new JSONObject().put("taskId", UUID.randomUUID().toString());
          }
          requested.countDown();
          assertTrue(release.await(3, TimeUnit.SECONDS));
          return new JSONObject()
              .put("state", "RESULTS")
              .put("validUntil", Instant.now().plusSeconds(120).toString())
              .put(
                  "result",
                  new JSONObject()
                      .put(
                          "listing",
                          new JSONObject()
                              .put("state", "RESULTS")
                              .put("candidates", new JSONArray())));
        };
    try (var client =
        new DestinationRecommendationClient(
            transport, destination, () -> {}, Clock.systemUTC(), System::nanoTime)) {
      client.search("주차 가능한 식당", 2, 0);
      assertTrue(requested.await(3, TimeUnit.SECONDS));
      destination.invalidateRoute();
      client.invalidate("ROUTE_CHANGED");
      release.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (closes.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
      assertEquals(1, closes.get());
      assertEquals("ROUTE_CHANGED", client.view().state());
      assertNull(client.view().result());
    }
  }
}
