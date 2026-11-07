package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Fresh JVMs exercise JDK properties before the HTTP implementation caches them. */
@Timeout(15)
class KakaoTransportStartupTest {
  private static final List<String> SAFE_OPTIONS =
      List.of(
          "-Djdk.httpclient.disableRetryConnect=true", "-Djdk.httpclient.redirects.retrylimit=1");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "-Djdk.httpclient.disableRetryConnect=true",
        "-Djdk.httpclient.redirects.retrylimit=1",
        "-Djdk.httpclient.disableRetryConnect=false,-Djdk.httpclient.redirects.retrylimit=1",
        "-Djdk.httpclient.disableRetryConnect=true,-Djdk.httpclient.redirects.retrylimit=2",
        "-Djdk.httpclient.disableRetryConnect=true,-Djdk.httpclient.redirects.retrylimit=0",
        "-Djdk.httpclient.disableRetryConnect=true,-Djdk.httpclient.redirects.retrylimit=1,-Djdk.httpclient.disableRetryConnect=false"
      })
  void missingOrUnsafeStartupOptionsBlockConstruction(String options) throws Exception {
    assertThat(runProbe(options.isEmpty() ? List.of() : List.of(options.split(",")), "construct"))
        .isEqualTo("BLOCKED");
  }

  @Test
  void correctStartupOptionsAllowConstructionWithoutCallingKakao() throws Exception {
    assertThat(runProbe(SAFE_OPTIONS, "construct")).isEqualTo("READY");
  }

  @Test
  void settingPropertiesAfterHttpInitializationCannotBypassTheStartupCheck() throws Exception {
    assertThat(runProbe(List.of(), "late-properties")).isEqualTo("BLOCKED");
  }

  @Test
  void changingStartupPropertiesBeforeConstructionIsRejected() throws Exception {
    assertThat(runProbe(SAFE_OPTIONS, "changed-properties")).isEqualTo("BLOCKED");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"drop-before-headers", "drop-during-body", "drop-on-reused-connection", "success"})
  void aSingleSearchDoesNotResendAfterTheServerDropsTheConnection(String scenario)
      throws Exception {
    AtomicInteger requests = new AtomicInteger();
    List<Integer> clientPorts = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          int count = requests.incrementAndGet();
          clientPorts.add(exchange.getRemoteAddress().getPort());
          try (exchange) {
            if (scenario.equals("success")
                || (scenario.equals("drop-on-reused-connection") && count == 1)) {
              byte[] body = "{\"documents\":[]}".getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
            } else if (scenario.equals("drop-during-body")) {
              exchange.sendResponseHeaders(200, 100);
              exchange.getResponseBody().write('{');
              exchange.getResponseBody().flush();
            }
            // Close without a response, or with an incomplete body; the upstream saw the request.
          }
        });
    server.start();
    try {
      String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
      String result =
          runProbe(
              SAFE_OPTIONS,
              scenario.equals("drop-on-reused-connection") ? "pooled:" + endpoint : endpoint);
      assertThat(result).isEqualTo(scenario.equals("success") ? "EMPTY" : "UPSTREAM_UNAVAILABLE");
      assertThat(requests).hasValue(scenario.equals("drop-on-reused-connection") ? 2 : 1);
      if (scenario.equals("drop-on-reused-connection"))
        assertThat(clientPorts)
            .as("both requests used the same TCP connection")
            .containsOnly(clientPorts.getFirst());
    } finally {
      server.stop(0);
    }
  }

  private String runProbe(List<String> options, String action) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(options);
    command.addAll(
        List.of("-cp", System.getProperty("placeTestClasspath"), Probe.class.getName(), action));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    // Host options must not silently repair or change the deliberately unsafe child configurations.
    for (String name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))
      builder.environment().remove(name);
    Process child = builder.start();
    try {
      assertThat(child.waitFor(8, TimeUnit.SECONDS))
          .as("isolated transport process finished")
          .isTrue();
      String output =
          new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      assertThat(child.exitValue()).withFailMessage(output).isZero();
      return output;
    } finally {
      child.destroyForcibly();
      child.waitFor(2, TimeUnit.SECONDS);
    }
  }

  public static final class Probe {
    public static void main(String[] args) {
      String action = args[0];
      if (action.equals("late-properties")) {
        // Initialize the JDK HTTP implementation before setting the apparently safe values.
        try (var ignored = java.net.http.HttpClient.newHttpClient()) {}
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        System.setProperty("jdk.httpclient.redirects.retrylimit", "1");
      }
      if (action.equals("changed-properties"))
        System.setProperty("jdk.httpclient.redirects.retrylimit", "5");
      var mapper = new KakaoPlaceMapper(new ObjectMapper(), Clock.systemUTC());
      boolean pooled = action.startsWith("pooled:");
      if (pooled) action = action.substring("pooled:".length());
      if (!action.startsWith("http://127.0.0.1:")) {
        try (var ignored = new KakaoPlaceSearchAdapter("fictional-rest-key", mapper)) {
          System.out.println("READY");
        } catch (IllegalStateException blocked) {
          System.out.println("BLOCKED");
        }
        return;
      }
      try (var adapter =
          new KakaoPlaceSearchAdapter(
              "fictional-rest-key", mapper, URI.create(action), Duration.ofSeconds(3))) {
        if (pooled
            && !(adapter.search(new PlaceQuery(37.544, 127.055, 1000, 15))
                instanceof PlaceSearchResult.Success))
          throw new AssertionError("Connection warmup failed");
        var result = adapter.search(new PlaceQuery(37.544, 127.055, 1000, 15));
        if (result instanceof PlaceSearchResult.Success success && success.places().isEmpty())
          System.out.println("EMPTY");
        else if (result instanceof PlaceSearchResult.Failure failure)
          System.out.println(failure.kind());
        else throw new AssertionError("Unexpected local response");
      }
    }
  }
}
