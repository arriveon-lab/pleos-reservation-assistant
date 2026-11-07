package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class KakaoPlaceSearchAdapterTest {
  private static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");
  private static final PlaceQuery QUERY = new PlaceQuery(37.544, 127.055, 1000, 15);
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
  private final AtomicReference<Received> received = new AtomicReference<>();
  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch requestStarted = new CountDownLatch(1);
  private HttpServer server;
  private ExecutorService workers;
  private URI endpoint;
  private byte[] fixture;

  private record Received(String method, String path, String query, String authorization) {}

  @BeforeEach
  void startLoopbackServer() throws Exception {
    try (var stream = getClass().getResourceAsStream("/places/kakao-fictional.json")) {
      fixture = stream.readAllBytes();
    }
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    workers = Executors.newCachedThreadPool();
    server.setExecutor(workers);
    handler.set(exchange -> reply(exchange, 200, fixture));
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          received.set(
              new Received(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestURI().getRawQuery(),
                  exchange.getRequestHeaders().getFirst("Authorization")));
          requestStarted.countDown();
          try (exchange) {
            handler.get().handle(exchange);
          }
        });
    server.start();
    endpoint =
        URI.create(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v2/local/search/category.json");
  }

  @AfterEach
  void stopLoopbackServer() {
    release.countDown();
    server.stop(0);
    workers.shutdownNow();
  }

  private KakaoPlaceSearchAdapter adapter(Duration timeout) {
    return new KakaoPlaceSearchAdapter(
        "fictional-rest-key",
        new KakaoPlaceMapper(new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC)),
        endpoint,
        timeout);
  }

  private void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  private void waitForRelease() throws IOException {
    try {
      if (!release.await(5, TimeUnit.SECONDS))
        throw new IOException("Test response was not released");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Test response interrupted", e);
    }
  }

  @ParameterizedTest
  @CsvSource({"37.544,127.055", "37.548,127.047", "37.541,127.043"})
  void sendsOnlyDestinationCategoryAndLimitsThenNormalizesTheResponse(
      double latitude, double longitude) {
    PlaceQuery query =
        PlaceQuery.forDestination(new Model.Destination("가상 목적지", latitude, longitude));
    try (var adapter = adapter(Duration.ofSeconds(3))) {
      var result = (PlaceSearchResult.Success) adapter.search(query);
      assertThat(result.places()).hasSize(2);
      assertThat(result.fetchedAt()).isEqualTo(NOW);
      assertThat(received.get())
          .isEqualTo(
              new Received(
                  "GET",
                  "/v2/local/search/category.json",
                  "category_group_code=FD6&x="
                      + longitude
                      + "&y="
                      + latitude
                      + "&radius=1000&sort=distance&page=1&size=15",
                  "KakaoAK fictional-rest-key"));
      assertThat(requests).hasValue(1);
    }
  }

  @Test
  void successfulEmptySearchIsNotAProviderFailure() {
    handler.set(
        exchange -> reply(exchange, 200, "{\"documents\":[]}".getBytes(StandardCharsets.UTF_8)));
    try (var adapter = adapter(Duration.ofSeconds(3))) {
      assertThat(((PlaceSearchResult.Success) adapter.search(QUERY)).places()).isEmpty();
      assertThat(requests).hasValue(1);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "400,INVALID_QUERY",
    "401,AUTHENTICATION",
    "403,FORBIDDEN",
    "429,RATE_LIMITED",
    "500,UPSTREAM_UNAVAILABLE",
    "503,UPSTREAM_UNAVAILABLE"
  })
  void providerFailuresDoNotRetryOrReturnFictionalCandidates(
      int status, PlaceSearchFailure expected) {
    handler.set(
        exchange ->
            reply(exchange, status, "private-upstream-body".getBytes(StandardCharsets.UTF_8)));
    try (var adapter = adapter(Duration.ofSeconds(3))) {
      assertThat(adapter.search(QUERY)).isEqualTo(new PlaceSearchResult.Failure(expected));
      assertThat(requests).hasValue(1);
    }
  }

  @Test
  void redirectsDoNotForwardTheAuthorizationHeaderOrFollowAnotherLocation() {
    handler.set(
        exchange -> {
          exchange
              .getResponseHeaders()
              .set("Location", endpoint.resolve("/another-location").toString());
          exchange.sendResponseHeaders(302, -1);
        });
    try (var adapter = adapter(Duration.ofSeconds(3))) {
      assertThat(adapter.search(QUERY))
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE));
      assertThat(requests).hasValue(1);
    }
  }

  @Test
  void delayedHeadersReachTheOverallDeadline() {
    handler.set(exchange -> waitForRelease());
    try (var adapter = adapter(Duration.ofSeconds(2))) {
      assertThat(adapter.search(QUERY))
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.TIMEOUT));
      assertThat(requests).hasValue(1);
    }
  }

  @Test
  void aStalledBodyAlsoReachesTheDeadlineAfterHeadersAndPartialBytesArrive() {
    handler.set(
        exchange -> {
          exchange.sendResponseHeaders(200, 100);
          exchange.getResponseBody().write('{');
          exchange.getResponseBody().flush();
          waitForRelease();
        });
    try (var adapter = adapter(Duration.ofSeconds(2))) {
      assertThat(adapter.search(QUERY))
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.TIMEOUT));
      assertThat(requests).hasValue(1);
    }
  }

  @Test
  void interruptionCancelsAnInFlightRequestAndPreservesTheInterruptFlag() throws Exception {
    handler.set(exchange -> waitForRelease());
    AtomicReference<PlaceSearchResult> result = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    try (var adapter = adapter(Duration.ofSeconds(3))) {
      Thread caller =
          new Thread(
              () -> {
                result.set(adapter.search(QUERY));
                interrupted.set(Thread.currentThread().isInterrupted());
              });
      caller.start();
      try {
        assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(2000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(result.get())
            .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.CANCELLED));
        assertThat(interrupted).isTrue();
        assertThat(requests).hasValue(1);
      } finally {
        caller.interrupt();
      }
    }
  }

  @Test
  void alreadyInterruptedCallsAndNullQueriesDoNotSendRequests() {
    try (var adapter = adapter(Duration.ofSeconds(3))) {
      assertThat(adapter.search(null))
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_QUERY));
      Thread.currentThread().interrupt();
      try {
        assertThat(adapter.search(QUERY))
            .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.CANCELLED));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally {
        Thread.interrupted();
      }
      assertThat(requests).hasValue(0);
    }
  }

  @Test
  void connectionFailureDoesNotBecomeAnEmptySearch() {
    server.stop(0);
    try (var adapter = adapter(Duration.ofSeconds(2))) {
      assertThat(adapter.search(QUERY))
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.UPSTREAM_UNAVAILABLE));
    }
  }

  @Test
  void responseSizeIsBoundedWhileReadingAndInvalidUtf8IsRejected() {
    handler.set(exchange -> reply(exchange, 200, new byte[262_145]));
    try (var adapter = adapter(Duration.ofSeconds(3))) {
      assertThat(adapter.search(QUERY))
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE));
      handler.set(exchange -> reply(exchange, 200, new byte[] {(byte) 0xff}));
      assertThat(adapter.search(QUERY))
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE));
      handler.set(exchange -> reply(exchange, 403, new byte[] {(byte) 0xff}));
      assertThat(adapter.search(QUERY))
          .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.FORBIDDEN));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "has spaces", "header\r\ninjection", "가상키"})
  void invalidKeysAreRejectedWithoutEchoingThemOrSendingRequests(String key) {
    assertThatThrownBy(
            () ->
                new KakaoPlaceSearchAdapter(
                    key,
                    new KakaoPlaceMapper(new ObjectMapper(), Clock.systemUTC()),
                    endpoint,
                    Duration.ofSeconds(3)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid REST API key configuration");
    assertThat(requests).hasValue(0);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://example.com/",
        "https://example.com/",
        "http://user@127.0.0.1:1234/",
        "http://127.0.0.1:1234/?query=value",
        "http://127.0.0.1:1234/#fragment"
      })
  void alternateEndpointsAreLimitedToExplicitLoopbackTests(String uri) {
    assertThatThrownBy(
            () ->
                new KakaoPlaceSearchAdapter(
                    "fictional-rest-key",
                    new KakaoPlaceMapper(new ObjectMapper(), Clock.systemUTC()),
                    URI.create(uri),
                    Duration.ofSeconds(3)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(requests).hasValue(0);
  }
}
