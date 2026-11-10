package ai.pleos.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Container readiness only: fixed internal URL, no credentials, no response bodies in logs. */
public final class PreviewAcceptanceHealthCheck {
  public static void main(String[] args) {
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      if (args.length != 0) throw new IllegalArgumentException();
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:18082/api/v1/status"))
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      var body = new ObjectMapper().readTree(response.body());
      if (response.statusCode() != 200
          || !"MOCK".equals(body.path("mode").asText())
          || !"1.1.0".equals(body.path("contractVersion").asText())
          || !body.has("externalCallsEnabled")
          || body.path("externalCallsEnabled").asBoolean()) throw new IllegalStateException();
    } catch (Exception unavailable) {
      System.exit(1);
    }
  }
}
