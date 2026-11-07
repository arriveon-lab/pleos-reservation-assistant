package ai.pleos.assistant;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Explicitly constructed transport, not a Spring bean. Real use requires an approved account and a
 * data-retention/transaction contract; the current workflow injects MockPlaceSearchAdapter only.
 */
public final class KakaoPlaceSearchAdapter implements PlaceSearchAdapter, AutoCloseable {
  private static final URI ENDPOINT =
      URI.create("https://dapi.kakao.com/v2/local/search/category.json");
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
  private static final int MAX_BODY_BYTES = 262_144;
  private final HttpClient client;
  private final KakaoPlaceMapper mapper;
  private final URI endpoint;
  private final Duration timeout;
  private final String restApiKey;

  public KakaoPlaceSearchAdapter(String restApiKey, KakaoPlaceMapper mapper) {
    this(restApiKey, mapper, ENDPOINT, REQUEST_TIMEOUT);
  }

  // An alternate endpoint is available only to package-local loopback tests, never application
  // config.
  KakaoPlaceSearchAdapter(
      String restApiKey, KakaoPlaceMapper mapper, URI endpoint, Duration timeout) {
    if (restApiKey == null
        || restApiKey.isBlank()
        || restApiKey.length() > 1000
        || !restApiKey.chars().allMatch(c -> c >= 33 && c <= 126))
      throw new IllegalArgumentException("Invalid REST API key configuration");
    Objects.requireNonNull(endpoint);
    boolean loopback =
        "http".equals(endpoint.getScheme())
            && "127.0.0.1".equals(endpoint.getHost())
            && endpoint.getPort() > 0;
    if ((!ENDPOINT.equals(endpoint) && !loopback)
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null)
      throw new IllegalArgumentException("Invalid place endpoint configuration");
    if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(REQUEST_TIMEOUT) > 0)
      throw new IllegalArgumentException("Invalid place timeout configuration");
    this.restApiKey = restApiKey;
    this.mapper = Objects.requireNonNull(mapper);
    this.endpoint = endpoint;
    this.timeout = timeout;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(
                timeout.compareTo(Duration.ofSeconds(2)) < 0 ? timeout : Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public PlaceSearchResult search(PlaceQuery query) {
    if (query == null) return failure(PlaceSearchFailure.INVALID_QUERY);
    if (Thread.currentThread().isInterrupted()) return failure(PlaceSearchFailure.CANCELLED);
    URI uri =
        URI.create(
            endpoint
                + "?category_group_code=FD6&x="
                + query.longitude()
                + "&y="
                + query.latitude()
                + "&radius="
                + query.radiusMeters()
                + "&sort=distance&page=1&size="
                + query.limit());
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Authorization", "KakaoAK " + restApiKey)
            .header("Accept", "application/json")
            .GET()
            .build();
    CompletableFuture<HttpResponse<byte[]>> response =
        client.sendAsync(request, info -> new BoundedBody());
    try {
      // The deadline includes the full body, even when headers arrive promptly and the body stalls.
      HttpResponse<byte[]> received = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (received.statusCode() != 200) return mapper.mapResponse(received.statusCode(), null);
      String body =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(received.body()))
              .toString();
      return mapper.mapResponse(received.statusCode(), body);
    } catch (TimeoutException e) {
      response.cancel(true);
      return failure(PlaceSearchFailure.TIMEOUT);
    } catch (InterruptedException e) {
      response.cancel(true);
      Thread.currentThread().interrupt();
      return failure(PlaceSearchFailure.CANCELLED);
    } catch (CharacterCodingException e) {
      return failure(PlaceSearchFailure.INVALID_RESPONSE);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      while (cause instanceof CompletionException && cause.getCause() != null)
        cause = cause.getCause();
      if (cause instanceof HttpTimeoutException) return failure(PlaceSearchFailure.TIMEOUT);
      return failure(
          cause instanceof ResponseTooLarge
              ? PlaceSearchFailure.INVALID_RESPONSE
              : PlaceSearchFailure.UPSTREAM_UNAVAILABLE);
    }
  }

  @Override
  public void close() {
    client.shutdownNow();
  }

  private static PlaceSearchResult.Failure failure(PlaceSearchFailure kind) {
    return new PlaceSearchResult.Failure(kind);
  }

  private static final class ResponseTooLarge extends IOException {
    ResponseTooLarge() {
      super("Place response exceeds size limit");
    }
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > MAX_BODY_BYTES - bytes.size()) {
          subscription.cancel();
          body.completeExceptionally(new ResponseTooLarge());
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
      body.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      body.complete(bytes.toByteArray());
    }
  }
}
