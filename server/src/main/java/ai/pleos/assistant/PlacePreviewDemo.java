package ai.pleos.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone development viewer; never starts Spring, Store, a database, AI or telephony. */
public final class PlacePreviewDemo {
  static final PlaceQuery PILOT_QUERY = new PlaceQuery(37.544, 127.055, 1_000, 3);

  public static void main(String[] args) {
    try {
      String mode = args.length == 0 ? "mock" : args[0];
      if (args.length > 2 || (!mode.equals("mock") && !mode.equals("live")))
        throw new IllegalArgumentException("Invalid preview mode");
      Clock clock = Clock.systemUTC();
      if (mode.equals("mock")) {
        if (args.length > 1) throw new IllegalArgumentException("MOCK does not read credentials");
        run(new MockPlaceSearchAdapter(clock), false);
      } else {
        String key =
            args.length == 2
                ? readPrivateKey(Path.of(args[1]))
                : System.getenv("KAKAO_REST_API_KEY");
        try (var adapter =
            new KakaoPlaceSearchAdapter(key, new KakaoPlaceMapper(new ObjectMapper(), clock))) {
          run(adapter, true);
        }
      }
    } catch (Exception error) {
      // Never print key content, upstream text, location payloads or a credential path/stack trace.
      System.err.println("PREVIEW_SETUP_FAILED: 실행 모드·키 설정·JVM 옵션을 확인하세요.");
      System.exit(1);
    }
  }

  private static void run(PlaceSearchAdapter adapter, boolean actual) throws Exception {
    try (var viewer = new Viewer(adapter, actual)) {
      System.out.println("PREVIEW_URL " + viewer.url());
      System.out.println("PREVIEW_MODE " + (actual ? "REAL_SEARCH" : "MOCK"));
      Summary result = viewer.completion.get(2, TimeUnit.MINUTES);
      // Execution metadata only. No response, name, ID, address, phone or key is printed.
      System.out.println("PREVIEW_RESULT " + new ObjectMapper().writeValueAsString(result));
    }
  }

  static String readPrivateKey(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1000)
      throw new IllegalArgumentException("Invalid private key file");
    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
    if (permissions.stream()
        .anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
      throw new IllegalArgumentException("Private key file must be owner-only");
    String key = Files.readString(path, StandardCharsets.UTF_8).strip();
    if (key.isBlank()) throw new IllegalArgumentException("Empty private key configuration");
    return key;
  }

  record Summary(
      String source, String status, int dispatches, int displayed, int rejectedRecords) {}

  static final class Viewer implements AutoCloseable {
    private final PlaceSearchAdapter adapter;
    private final boolean actual;
    private final String path = "/preview/" + UUID.randomUUID() + "/";
    private final AtomicBoolean used = new AtomicBoolean();
    private final HttpServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    final CompletableFuture<Summary> completion = new CompletableFuture<>();

    Viewer(PlaceSearchAdapter adapter, boolean actual) throws IOException {
      this.adapter = adapter;
      this.actual = actual;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(workers);
      server.createContext("/", this::handle);
      server.start();
    }

    URI url() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store, max-age=0");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (!exchange.getRequestURI().getRawPath().equals(path)
            || exchange.getRequestURI().getRawQuery() != null) {
          reply(exchange, 404, "미리보기 주소를 확인하세요.");
          return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
          exchange.getResponseHeaders().set("Allow", "GET");
          reply(exchange, 405, "GET 요청만 지원합니다.");
          return;
        }
        if (!url().getAuthority().equals(exchange.getRequestHeaders().getFirst("Host"))
            || "cross-site".equals(exchange.getRequestHeaders().getFirst("Sec-Fetch-Site"))
            || exchange.getRequestHeaders().containsKey("Purpose")
            || exchange.getRequestHeaders().containsKey("Sec-Purpose")) {
          reply(exchange, 403, "주소창에서 직접 미리보기를 열어 주세요.");
          return;
        }
        if (!used.compareAndSet(false, true)) {
          reply(exchange, 410, "이번 조회는 이미 사용했습니다. 새로고침으로 다시 검색하지 않습니다.");
          return;
        }
        PlaceSearchResult result;
        try {
          result = adapter.search(PILOT_QUERY);
        } catch (RuntimeException error) {
          result = new PlaceSearchResult.Failure(PlaceSearchFailure.UPSTREAM_UNAVAILABLE);
        }
        if (result == null)
          result = new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE);
        PlaceSummary.Provider expected =
            actual ? PlaceSummary.Provider.KAKAO : PlaceSummary.Provider.MOCK;
        if (result instanceof PlaceSearchResult.Success success
            && (success.places().size() > 3
                || success.places().stream().anyMatch(p -> p.provider() != expected)))
          result = new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE);
        Summary summary =
            result instanceof PlaceSearchResult.Success success
                ? new Summary(
                    expected.name(),
                    "SUCCESS",
                    1,
                    success.places().size(),
                    success.rejectedRecords())
                : new Summary(
                    expected.name(), ((PlaceSearchResult.Failure) result).kind().name(), 1, 0, 0);
        try {
          reply(exchange, 200, page(result, actual));
        } finally {
          exchange.close(); // Flush the document before main shuts the one-use server down.
          completion.complete(summary);
        }
      }
    }

    private void reply(HttpExchange exchange, int status, String html) throws IOException {
      String nonce = UUID.randomUUID().toString();
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange
          .getResponseHeaders()
          .set(
              "Content-Security-Policy",
              "default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-"
                  + nonce
                  + "'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
      String document =
          "<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\">"
              + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
              + "<link rel=\"icon\" href=\"data:,\"><title>ArriveOn 장소 검색 확인</title>"
              + "<style>body{margin:0;padding:48px 24px;background:#f3f6fa;color:#172638;font:17px/1.6 system-ui,sans-serif}"
              + "main{max-width:1100px;margin:auto}h1{font-size:34px;line-height:1.3}small{color:#506277}"
              + ".badge{display:inline-block;background:#d6f2e9;color:#145f49;padding:6px 14px;border-radius:20px;font-weight:700}"
              + ".cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:18px;margin:28px 0}"
              + "article{background:white;border-radius:18px;padding:24px;box-shadow:0 4px 18px #132a4510}"
              + "h2{font-size:22px;margin-top:0}footer{border-top:1px solid #d6dee8;padding-top:20px;color:#506277}"
              + "</style><main>"
              + html
              + "</main><script nonce=\""
              + nonce
              + "\">"
              + "setTimeout(()=>{document.querySelector('main').textContent='표시 시간이 끝났습니다. 결과를 저장하거나 다시 조회하지 않았습니다.';},300000);"
              + "</script></html>";
      byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
      server.stop(0);
      workers.shutdownNow();
    }
  }

  static String page(PlaceSearchResult result, boolean actual) {
    String title =
        "<span class=\"badge\">"
            + (actual ? "실제 장소 검색 · Kakao" : "가상 데이터 · MOCK")
            + "</span><h1>목적지 주변 음식점</h1><p>성수·서울숲 시범 입력 · 반경 1km · 최대 3곳</p>";
    String body;
    if (result instanceof PlaceSearchResult.Success success) {
      StringBuilder cards = new StringBuilder("<div class=\"cards\">");
      for (PlaceSummary place : success.places()) {
        cards
            .append("<article><h2>")
            .append(escape(place.name()))
            .append("</h2><p>")
            .append(escape(place.cuisine() == null ? "음식 분류 미상" : place.cuisine()))
            .append("</p><p>")
            .append(escape(place.address() == null ? "주소 정보 없음" : place.address()))
            .append("</p><small>")
            .append(
                place.distanceMeters() == null
                    ? "거리 정보 없음"
                    : "검색 중심에서 " + place.distanceMeters() + "m")
            .append("</small><p>주차 · 아이 동반 · 예약 가능<br><strong>확인 필요</strong></p></article>");
      }
      body =
          success.places().isEmpty()
              ? "<p>이 범위에서 검색 결과가 없습니다.</p>"
              : cards.append("</div>").toString();
    } else {
      body =
          "<article><h2>검색을 완료하지 못했습니다</h2><p>"
              + ((PlaceSearchResult.Failure) result).kind().name()
              + "</p><p>자동 재시도나 가상 후보 대체는 하지 않았습니다.</p></article>";
    }
    return title
        + body
        + "<footer>장소 정보 확인용입니다. 전화 · 예약 · AI 정보 보완은 실행하지 않았습니다."
        + "<br>실시간 일시 표시 · 새로고침 재조회 없음 · 5분 후 표시 종료</footer>";
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
