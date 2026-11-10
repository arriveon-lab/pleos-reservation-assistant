package ai.pleos.assistant;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Fixed API endpoint, no redirects/retries, bounded full-body deadline. Not a Spring bean. */
public final class GptWebSearchHttpTransport
    implements GptPlaceSourceDiscovery.Transport, AutoCloseable {
  private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
  private static final int MAX_BYTES = 1_048_576;
  private final URI endpoint;
  private final HttpClient client;

  public GptWebSearchHttpTransport() {
    this(ENDPOINT);
  }

  GptWebSearchHttpTransport(URI endpoint) {
    boolean loopback =
        "http".equals(endpoint.getScheme())
            && "127.0.0.1".equals(endpoint.getHost())
            && endpoint.getPort() > 0;
    if ((!ENDPOINT.equals(endpoint) && !loopback)
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null)
      throw new IllegalArgumentException("Invalid search endpoint");
    requireStartup("jdk.httpclient.disableRetryConnect", "true");
    requireStartup("jdk.httpclient.redirects.retrylimit", "1");
    if (!System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank())
      throw new IllegalStateException("HTTP credential logging must be disabled");
    this.endpoint = endpoint;
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
  }

  @Override
  public GptPlaceSourceDiscovery.Response post(String json, String bearer, Duration timeout)
      throws Exception {
    if (json == null
        || json.length() > 8192
        || bearer == null
        || bearer.isBlank()
        || bearer.length() > 1000
        || !bearer.chars().allMatch(c -> c >= 33 && c <= 126)
        || timeout == null
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("Invalid bounded search request");
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Authorization", "Bearer " + bearer)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    var response = client.sendAsync(request, info -> new BoundedBody());
    try {
      var received = response.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
      if (received.statusCode() != 200)
        return new GptPlaceSourceDiscovery.Response(received.statusCode(), null);
      if (!received
          .headers()
          .firstValue("Content-Type")
          .orElse("")
          .toLowerCase(java.util.Locale.ROOT)
          .split(";", 2)[0]
          .strip()
          .equals("application/json")) throw new IOException("Invalid search response type");
      String body =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(received.body()))
              .toString();
      return new GptPlaceSourceDiscovery.Response(200, body);
    } catch (Exception failure) {
      response.cancel(true);
      throw failure;
    }
  }

  private static void requireStartup(String name, String value) {
    String prefix = "-D" + name + "=";
    String actual =
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(a -> a.startsWith(prefix))
            .reduce((a, b) -> b)
            .map(a -> a.substring(prefix.length()))
            .orElse(null);
    if (!value.equals(actual) || !value.equals(System.getProperty(name)))
      throw new IllegalStateException("Explicit no-retry JVM startup settings required");
  }

  @Override
  public void close() {
    client.shutdownNow();
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > MAX_BYTES - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(new IOException("Search response size limit"));
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
