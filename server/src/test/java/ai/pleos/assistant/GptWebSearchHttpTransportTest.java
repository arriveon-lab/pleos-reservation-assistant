package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class GptWebSearchHttpTransportTest {
  private HttpServer server;
  private ExecutorService worker;
  private URI endpoint;
  private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
  private final AtomicInteger requests = new AtomicInteger();
  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch started = new CountDownLatch(1);

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    worker = Executors.newCachedThreadPool();
    server.setExecutor(worker);
    handler.set(e -> reply(e, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8)));
    server.createContext(
        "/",
        e -> {
          requests.incrementAndGet();
          started.countDown();
          try (e) {
            handler.get().handle(e);
          }
        });
    server.start();
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
  }

  @AfterEach
  void stop() {
    release.countDown();
    server.stop(0);
    worker.shutdownNow();
  }

  private static void reply(HttpExchange e, int status, String type, byte[] body)
      throws java.io.IOException {
    e.getResponseHeaders().set("Content-Type", type);
    e.sendResponseHeaders(status, body.length);
    e.getResponseBody().write(body);
  }

  @Test
  void fixed_endpoint_posts_fake_credential_and_payload_once() throws Exception {
    handler.set(
        e -> {
          assertThat(e.getRequestMethod()).isEqualTo("POST");
          assertThat(e.getRequestURI().getPath()).isEqualTo("/v1/responses");
          assertThat(e.getRequestURI().getQuery()).isNull();
          assertThat(e.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer fictional-key");
          assertThat(e.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/json");
          assertThat(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
              .isEqualTo("{\"store\":false}");
          reply(
              e,
              200,
              "application/json; charset=utf-8",
              "{\"status\":\"completed\"}".getBytes(StandardCharsets.UTF_8));
        });
    try (var client = new GptWebSearchHttpTransport(endpoint)) {
      var result = client.post("{\"store\":false}", "fictional-key", Duration.ofSeconds(2));
      assertThat(result.status()).isEqualTo(200);
      assertThat(result.json()).contains("completed");
      assertThat(result.toString()).doesNotContain("completed", "fictional-key");
    }
    assertThat(requests).hasValue(1);
  }

  @Test
  void redirects_and_failures_are_not_retried_and_discard_body() throws Exception {
    handler.set(
        e -> {
          e.getResponseHeaders().set("Location", endpoint.resolve("/another").toString());
          reply(e, 302, "text/html", "private response".getBytes(StandardCharsets.UTF_8));
        });
    try (var client = new GptWebSearchHttpTransport(endpoint)) {
      var response = client.post("{}", "fictional-key", Duration.ofSeconds(2));
      assertThat(response.status()).isEqualTo(302);
      assertThat(response.json()).isNull();
      assertThat(requests).hasValue(1);
      handler.set(
          e -> reply(e, 429, "application/json", "private error".getBytes(StandardCharsets.UTF_8)));
      assertThat(client.post("{}", "fictional-key", Duration.ofSeconds(2)).json()).isNull();
      assertThat(requests).hasValue(2);
    }
  }

  @Test
  void rejects_bad_content_type_invalid_utf8_and_oversized_body() throws Exception {
    try (var client = new GptWebSearchHttpTransport(endpoint)) {
      handler.set(e -> reply(e, 200, "text/html", "{}".getBytes(StandardCharsets.UTF_8)));
      assertThatThrownBy(() -> client.post("{}", "fictional-key", Duration.ofSeconds(2)))
          .isInstanceOf(java.io.IOException.class);
      handler.set(e -> reply(e, 200, "application/json", new byte[] {(byte) 0xc3, 0x28}));
      assertThatThrownBy(() -> client.post("{}", "fictional-key", Duration.ofSeconds(2)))
          .isInstanceOf(java.nio.charset.CharacterCodingException.class);
      handler.set(e -> reply(e, 200, "application/json", new byte[1_048_577]));
      assertThatThrownBy(() -> client.post("{}", "fictional-key", Duration.ofSeconds(2)))
          .isInstanceOf(Exception.class);
      assertThat(requests).hasValue(3);
    }
  }

  @Test
  void deadline_covers_stalled_body_after_headers() throws Exception {
    handler.set(
        e -> {
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(200, 0);
          e.getResponseBody().write('{');
          e.getResponseBody().flush();
          try {
            release.await(3, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
    try (var client = new GptWebSearchHttpTransport(endpoint);
        var task = Executors.newSingleThreadExecutor()) {
      var future = task.submit(() -> client.post("{}", "fictional-key", Duration.ofMillis(300)));
      try {
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class);
      } finally {
        release.countDown();
      }
    }
    assertThat(requests).hasValue(1);
  }

  @Test
  void rejects_unbounded_requests_and_other_endpoint_configuration() throws Exception {
    try (var client = new GptWebSearchHttpTransport(endpoint)) {
      for (Duration timeout :
          new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(31)})
        assertThatThrownBy(() -> client.post("{}", "fictional-key", timeout))
            .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> client.post("x".repeat(8193), "fictional-key", Duration.ofSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> client.post("{}", "bad\nheader", Duration.ofSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String url :
        new String[] {
          "https://other.example.invalid/v1/responses",
          "http://localhost:8080/v1/responses",
          endpoint + "?key=x",
          endpoint + "#fragment"
        })
      assertThatThrownBy(() -> new GptWebSearchHttpTransport(URI.create(url)))
          .isInstanceOf(IllegalArgumentException.class);
    assertThat(requests).hasValue(0);
  }
}
