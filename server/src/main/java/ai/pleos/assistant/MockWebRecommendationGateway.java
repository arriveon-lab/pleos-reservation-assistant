package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/** Same response parser as the paid adapter, fictional reserved domains and no HTTP/key access. */
public final class MockWebRecommendationGateway implements WebRecommendationService.Gateway {
  private final Clock clock;

  public MockWebRecommendationGateway(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Result search(Request request, Instant deadline, BooleanSupplier current) {
    if (!current.getAsBoolean()) return empty(Mode.MOCK, State.CANCELLED);
    if (!clock.instant().isBefore(deadline)) return empty(Mode.MOCK, State.EXPIRED);
    return new WebRecommendationParser()
        .parse(
            fictionalResponse(),
            request,
            Mode.MOCK,
            clock.instant(),
            clock.instant().plusSeconds(300));
  }

  static String fictionalResponse() {
    var json = new ObjectMapper();
    var content = json.createObjectNode();
    var candidates = content.putArray("candidates");
    var annotations = json.createArrayNode();
    for (int i = 1; i <= 4; i++) {
      String url = "https://merchant.example/fictional-" + i;
      var candidate = candidates.addObject();
      candidate.put("name", "가상 웹추천 식당 " + i);
      candidate.put("address", "가상 서울숲 시험주소 " + i);
      candidate.put("identitySourceUrl", url);
      var reports = candidate.putArray("reports");
      for (Need need : Need.values()) {
        var report = reports.addObject();
        report.put("need", need.name());
        report.put(
            "answer",
            i == 4
                ? "NO"
                : (i == 2 && need == Need.INDOOR_PET_FRIENDLY)
                        || need == Need.HIGH_CHAIR
                        || need == Need.STEP_FREE_ACCESS
                    ? "UNKNOWN"
                    : "YES");
        report.put("condition", i == 2 ? "가상 안내: 테라스 이용 조건은 별도 확인" : "가상 안내: 해당 조건 가능 보고");
        report.put("sourceUrl", url);
        report.put("sourceType", i == 3 ? "VISITOR_REVIEW" : "MERCHANT_PAGE");
        report.putNull("sourceUpdatedAt");
      }
      var annotation = annotations.addObject();
      annotation.put("type", "url_citation");
      annotation.put("url", url);
      annotation.put("title", "가상 매장 안내 " + i);
    }
    var root = json.createObjectNode();
    root.put("status", "completed");
    var output = root.putArray("output");
    var call = output.addObject();
    call.put("type", "web_search_call");
    call.put("status", "completed");
    var message = output.addObject();
    message.put("type", "message");
    message.put("role", "assistant");
    var text = message.putArray("content").addObject();
    text.put("type", "output_text");
    text.put("text", content.toString());
    text.set("annotations", annotations);
    return root.toString();
  }
}
