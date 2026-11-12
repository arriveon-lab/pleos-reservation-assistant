package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class PlaceLookupGatewayTest {
  @Test
  void discoversAndVerifiesBranchesBeforeRankingByNeedsAndReviewCount() throws Exception {
    var json = new ObjectMapper();
    var now = Instant.parse("2026-11-12T01:00:00Z");
    var clock = Clock.fixed(now, ZoneOffset.UTC);
    var index = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var lookups = new AtomicInteger();
    index.createContext(
        "/places",
        exchange -> {
          try (exchange) {
            lookups.incrementAndGet();
            var query = json.readTree(exchange.getRequestBody());
            String payload;
            if (query.has("verifyBranches")) {
              var verification = json.createObjectNode();
              var matched = verification.putArray("verified");
              for (var branch : query.path("verifyBranches")) {
                if (!branch.path("name").asText().equals("가상 원거리")) matched.add(branch);
              }
              payload = verification.toString();
            } else {
              payload =
                  """
              {"observedAt":"2026-11-12T01:00:00Z","sources":[{"url":"https://index.example/places","title":"가상 상가정보"}],
               "candidates":[{"name":"기본 가상 식당","address":"서울 가상구 가온로 25",
                 "identitySourceUrl":"https://index.example/places","venueCategory":"RESTAURANT","reports":[]}]}
              """;
            }
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    index.start();
    var body = json.createObjectNode();
    var candidates = body.putArray("candidates");
    addRestaurant(candidates, "가상 새 식당", 300, "YES", "브레이크타임 없음");
    addRestaurant(candidates, "가상 인기 식당", 600, "YES", "브레이크타임 없음");
    addRestaurant(candidates, "가상 유아의자 미확인", 8000, "UNKNOWN", "브레이크타임 없음");
    addRestaurant(candidates, "가상 유아의자 불가", 9000, "NO", "브레이크타임 없음");
    addRestaurant(candidates, "가상 브레이크타임", 9500, "YES", "매일 15:00–17:00");
    addRestaurant(candidates, "가상 원거리", 9900, "YES", "브레이크타임 없음");
    addRestaurant(candidates, "가상 주문 종료", 9800, "YES", "브레이크타임 없음");
    ((ArrayNode) candidates.get(6).path("businessDetails"))
        .addObject()
        .put("kind", "LAST_ORDER")
        .put("condition", "매일 15:00")
        .put("sourceUrl", "https://merchant.example/branches")
        .put("sourceType", "MERCHANT_PAGE")
        .putNull("sourceUpdatedAt");
    var reply = json.createObjectNode().put("status", "completed");
    var output = reply.putArray("output");
    output
        .addObject()
        .put("type", "web_search_call")
        .put("status", "completed")
        .putObject("action")
        .put("type", "search");
    var response =
        output
            .addObject()
            .put("type", "message")
            .put("role", "assistant")
            .putArray("content")
            .addObject()
            .put("type", "output_text")
            .put("text", body.toString());
    response
        .putArray("annotations")
        .addObject()
        .put("type", "url_citation")
        .put("url", "https://merchant.example/branches")
        .put("title", "가상 매장 근거");
    var adapter =
        new GptWebRecommendationAdapter(
            clock,
            (payload, credential, timeout) -> {
              var sent = json.readTree(payload);
              assertThat(sent.path("max_tool_calls").asInt()).isEqualTo(1);
              assertThat(json.readTree(sent.path("input").asText()).path("area").asText())
                  .isEqualTo("서울 가상구 가온로 25");
              return new GptPlaceSourceDiscovery.Response(200, reply.toString());
            },
            () -> "fictional-key");
    var web = new RuntimeWebRecommendationGateway(clock, adapter, "gpt-6-luna", 3072, 1);
    var request =
        new Request(
            UUID.randomUUID(),
            1,
            "서울 가상구 가온로 25",
            Set.of(Need.PARKING, Need.HIGH_CHAIR),
            false,
            VenueCategory.RESTAURANT,
            Set.of(),
            LocalDateTime.parse("2026-11-12T15:30"));
    try (var gateway =
        new PlaceLookupGateway(
            clock, index.getAddress().getPort(), "f".repeat(32), web, false, true, false)) {
      var result = gateway.search(request, now.plusSeconds(30), () -> true);
      assertThat(result.state()).isEqualTo(State.RESULTS);
      assertThat(result.candidates())
          .extracting(Candidate::name)
          .containsExactly("가상 인기 식당", "가상 새 식당", "가상 유아의자 미확인");
      assertThat(
              result
                  .candidates()
                  .getFirst()
                  .businessDetails()
                  .get(BusinessDetailKind.REVIEW_COUNT)
                  .condition())
          .isEqualTo("네이버 방문자 리뷰 600개");
      assertThat(result.candidates().getLast().remainingQuestions())
          .containsExactly(Need.HIGH_CHAIR);
      assertThat(web.consumedCalls()).isOne();
      assertThat(lookups.get()).isEqualTo(2);
    } finally {
      index.stop(0);
    }
  }

  private static void addRestaurant(
      ArrayNode candidates, String name, int reviews, String chair, String breaks) {
    var url = "https://merchant.example/branches";
    var place =
        candidates
            .addObject()
            .put("name", name)
            .put("address", "서울 가상구 가온로 25")
            .put("identitySourceUrl", url)
            .put("venueCategory", "RESTAURANT");
    var details = place.putArray("businessDetails");
    details
        .addObject()
        .put("kind", "REVIEW_COUNT")
        .put("condition", "네이버 방문자 리뷰 " + reviews + "개")
        .put("sourceUrl", url)
        .put("sourceType", "OTHER")
        .put("sourceUpdatedAt", "2026-11-10T00:00:00Z");
    details
        .addObject()
        .put("kind", "OPENING_HOURS")
        .put("condition", "매일 11:00–22:00")
        .put("sourceUrl", url)
        .put("sourceType", "MERCHANT_PAGE")
        .putNull("sourceUpdatedAt");
    details
        .addObject()
        .put("kind", "BREAK_TIME")
        .put("condition", breaks)
        .put("sourceUrl", url)
        .put("sourceType", "MERCHANT_PAGE")
        .putNull("sourceUpdatedAt");
    var reports = place.putArray("reports");
    for (var need : Set.of("PARKING", "HIGH_CHAIR")) {
      reports
          .addObject()
          .put("need", need)
          .put("answer", need.equals("PARKING") ? "YES" : chair)
          .put("condition", "가상 매장 안내")
          .put("sourceUrl", url)
          .put("sourceType", "MERCHANT_PAGE")
          .putNull("sourceUpdatedAt");
    }
  }

  @Test
  void keptCandidatesDoNotHideTheReasonDetailedSearchFailed(CapturedOutput log) throws Exception {
    var now = Instant.parse("2026-11-12T01:00:00Z");
    var clock = Clock.fixed(now, ZoneOffset.UTC);
    var index = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    byte[] base =
        """
        {"observedAt":"2026-11-12T01:00:00Z",
         "sources":[{"url":"https://index.example/places","title":"가상 상가정보"}],
         "candidates":[{"name":"가상 식당","address":"가상시 가온로 30",
         "identitySourceUrl":"https://index.example/places","venueCategory":"RESTAURANT","reports":[]}]}
        """
            .getBytes(StandardCharsets.UTF_8);
    index.createContext(
        "/places",
        exchange -> {
          try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, base.length);
            exchange.getResponseBody().write(base);
          }
        });
    index.start();
    var adapter =
        new GptWebRecommendationAdapter(
            clock,
            (body, credential, timeout) -> new GptPlaceSourceDiscovery.Response(429, null),
            () -> "fictional-key");
    var web = new RuntimeWebRecommendationGateway(clock, adapter, "gpt-6-luna", 3072, 1);
    var request = new Request(UUID.randomUUID(), 1, "가상시 가온로 25", Set.of(Need.PARKING), false);
    try (var gateway =
        new PlaceLookupGateway(
            clock, index.getAddress().getPort(), "f".repeat(32), web, false, true, false)) {
      var result = gateway.search(request, now.plusSeconds(30), () -> true);
      assertThat(result.state()).isEqualTo(State.RESULTS);
      assertThat(result.candidates()).extracting(Candidate::name).containsExactly("가상 식당");
      assertThat(log.getAll()).contains("state=FAILED, reason=HTTP_RATE_LIMITED");
      assertThat(log.getAll()).doesNotContain("fictional-key", "가온로");
    } finally {
      index.stop(0);
    }
  }
}
