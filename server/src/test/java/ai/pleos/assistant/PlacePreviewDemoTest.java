package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class PlacePreviewDemoTest {
  private static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");
  private static final PlaceSearchResult.Success EMPTY =
      new PlaceSearchResult.Success(List.of(), NOW, 0);
  @TempDir Path temporary;

  private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void normalEmptyResponseHasNoStoreAndReloadCannotDispatchAgain() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (var viewer =
            new PlacePreviewDemo.Viewer(
                query -> {
                  calls.incrementAndGet();
                  assertThat(query).isEqualTo(new PlaceQuery(37.544, 127.055, 1000, 3));
                  return EMPTY;
                },
                false);
        var client = HttpClient.newHttpClient()) {
      var response = get(client, viewer.url());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("가상 데이터 · MOCK", "검색 결과가 없습니다", "300000");
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store, max-age=0");
      assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow())
          .contains("default-src 'none'", "frame-ancestors 'none'", "script-src 'nonce-");
      assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
      assertThat(get(client, viewer.url()).statusCode()).isEqualTo(410);
      assertThat(calls).hasValue(1);
      assertThat(viewer.completion.get(1, TimeUnit.SECONDS))
          .isEqualTo(new PlacePreviewDemo.Summary("MOCK", "SUCCESS", 1, 0, 0));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"path", "query", "method", "cross-site", "prefetch", "sec-prefetch"})
  void irrelevantOrUntrustedRequestsCannotSpendTheOneAllowedDispatch(String kind) throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (var viewer =
            new PlacePreviewDemo.Viewer(
                query -> {
                  calls.incrementAndGet();
                  return EMPTY;
                },
                false);
        var client = HttpClient.newHttpClient()) {
      URI uri =
          switch (kind) {
            case "path" -> viewer.url().resolve("/favicon.ico");
            case "query" -> URI.create(viewer.url() + "?query=ignored");
            default -> viewer.url();
          };
      var request = HttpRequest.newBuilder(uri).GET();
      if (kind.equals("method")) request.POST(HttpRequest.BodyPublishers.noBody());
      if (kind.equals("cross-site")) request.header("Sec-Fetch-Site", "cross-site");
      if (kind.equals("prefetch")) request.header("Purpose", "prefetch");
      if (kind.equals("sec-prefetch")) request.header("Sec-Purpose", "prefetch");
      int expected =
          switch (kind) {
            case "path", "query" -> 404;
            case "method" -> 405;
            default -> 403;
          };
      assertThat(client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode())
          .isEqualTo(expected);
      assertThat(calls).hasValue(0);
      assertThat(get(client, viewer.url()).statusCode()).isEqualTo(200);
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void simultaneousPageLoadsStillMakeOnlyOneAdapterCall() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    try (var viewer =
            new PlacePreviewDemo.Viewer(
                query -> {
                  calls.incrementAndGet();
                  entered.countDown();
                  try {
                    if (!release.await(3, TimeUnit.SECONDS))
                      throw new IllegalStateException("Not released");
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                  }
                  return EMPTY;
                },
                false);
        var client = HttpClient.newHttpClient()) {
      try {
        var first =
            client.sendAsync(
                HttpRequest.newBuilder(viewer.url()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(get(client, viewer.url()).statusCode()).isEqualTo(410);
        release.countDown();
        assertThat(first.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(calls).hasValue(1);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void failureIsVisibleWithoutAnEmptySuccessOrAutomaticRetry() throws Exception {
    try (var viewer =
            new PlacePreviewDemo.Viewer(
                query -> new PlaceSearchResult.Failure(PlaceSearchFailure.TIMEOUT), true);
        var client = HttpClient.newHttpClient()) {
      String html = get(client, viewer.url()).body();
      assertThat(html).contains("실제 장소 검색 · Kakao", "TIMEOUT", "검색을 완료하지 못했습니다");
      assertThat(html).doesNotContain("검색 결과가 없습니다");
      assertThat(viewer.completion.get(1, TimeUnit.SECONDS).status()).isEqualTo("TIMEOUT");
    }
  }

  @Test
  void displayEscapesProviderTextAndDoesNotExposeContactDetailsOrRawCoordinates() {
    var place =
        new PlaceSummary(
            PlaceSummary.Provider.MOCK,
            "fictional-id-not-displayed",
            "<img src=x onerror=alert(1)>",
            null,
            37.544,
            127.055,
            "가상 주소 <script>",
            "fictional-phone-not-displayed",
            null,
            50);
    String html =
        PlacePreviewDemo.page(new PlaceSearchResult.Success(List.of(place), NOW, 0), false);
    assertThat(html).contains("&lt;img", "&lt;script&gt;", "검색 중심에서 50m", "확인 필요");
    assertThat(html)
        .doesNotContain(
            "<img",
            "<script>",
            "fictional-phone-not-displayed",
            "fictional-id-not-displayed",
            "37.544",
            "127.055");
  }

  @Test
  void wrongProviderAndTooManyRecordsAreNotDisplayedAsRealSearchSuccess() throws Exception {
    var place =
        new PlaceSummary(
            PlaceSummary.Provider.MOCK,
            "fictional-id",
            "가상 테스트 식당",
            null,
            37.544,
            127.055,
            null,
            null,
            null,
            null);
    var realShape =
        new PlaceSummary(
            PlaceSummary.Provider.KAKAO,
            "fictional-kakao-id",
            "가상 테스트 식당",
            null,
            37.544,
            127.055,
            null,
            null,
            null,
            null);
    for (var result :
        List.of(
            new PlaceSearchResult.Success(List.of(place), NOW, 0),
            new PlaceSearchResult.Success(
                List.of(realShape, realShape, realShape, realShape), NOW, 0))) {
      try (var viewer = new PlacePreviewDemo.Viewer(query -> result, true);
          var client = HttpClient.newHttpClient()) {
        assertThat(get(client, viewer.url()).body())
            .contains("INVALID_RESPONSE")
            .doesNotContain("가상 테스트 식당");
      }
    }
  }

  @Test
  void privateKeyFilesMustBeOwnerOnlyAndCannotBeSymlinkedOrEchoTheirContents() throws Exception {
    Path key = temporary.resolve("key");
    Files.writeString(key, "fictional-rest-key\n");
    Files.setPosixFilePermissions(
        key, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    assertThat(PlacePreviewDemo.readPrivateKey(key)).isEqualTo("fictional-rest-key");
    Path link = temporary.resolve("link");
    Files.createSymbolicLink(link, key);
    assertThatThrownBy(() -> PlacePreviewDemo.readPrivateKey(link))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("fictional-rest-key");
    Files.setPosixFilePermissions(
        key, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OTHERS_READ));
    assertThatThrownBy(() -> PlacePreviewDemo.readPrivateKey(key))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("fictional-rest-key");
  }
}
