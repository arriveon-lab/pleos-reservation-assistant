package ai.pleos.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-scoped handoff from a current shortlist to the loopback phone simulator. */
@RestController
@ConditionalOnProperty(
    name = {"app.destination-recommendations.enabled", "app.recommendation-phone.enabled"},
    havingValue = "true")
@RequestMapping("/api/experimental/destination-recommendations/{id}/phone-reservations")
class RecommendationPhoneController {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
  private static final Set<String> PHONE_ERRORS =
      Set.of(
          "NOT_FOUND",
          "INVALID_INPUT",
          "INVALID_ACTION",
          "INVALID_STATE",
          "INVALID_SCENARIO",
          "CHANGE_NOT_FOUND",
          "CHANGE_PENDING",
          "CONFIRMED_BOOKING_REQUIRED",
          "CALLBACK_REVIEW_REQUIRED",
          "UNVERIFIED_MOCK_CANDIDATE",
          "FALLBACK_APPROVAL_REQUIRED",
          "APPROVAL_SCOPE_CHANGED",
          "NO_FALLBACK",
          "IDEMPOTENCY_CONFLICT");

  record Approval(
      JsonNode approved,
      JsonNode candidateOrdinal,
      Instant reservationAt,
      String reservationName,
      String previousAttemptId,
      String conditionScenario,
      String availabilityScenario,
      String bookingScenario) {}

  private final DestinationRecommendationService recommendations;
  private final ObjectMapper json;
  private final Clock clock;
  private final HttpClient phone =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .proxy(HttpClient.Builder.NO_PROXY)
          .build();
  private final String endpoint;
  private final String token;

  RecommendationPhoneController(
      DestinationRecommendationService recommendations,
      ObjectMapper json,
      Clock clock,
      @Value("${app.mode:MOCK}") Model.Mode mode,
      @Value("${app.recommendation-phone.port:0}") int port,
      @Value("${PHONE_RECOMMENDATION_TOKEN:}") String token) {
    if (mode != Model.Mode.MOCK
        || port < 1
        || port > 65535
        || !token.matches("[A-Za-z0-9_-]{32,128}")) {
      throw new IllegalArgumentException(
          "Configure the loopback recommendation phone simulator in MOCK mode");
    }
    this.recommendations = recommendations;
    this.json = json;
    this.clock = clock;
    this.endpoint = "http://127.0.0.1:" + port + "/api/internal/mock-recommendation-bookings/";
    this.token = token;
  }

  @GetMapping
  JsonNode latest(HttpServletRequest request, @PathVariable UUID id) {
    return send(requestToPhone(RequestActor.from(request), id).GET().build());
  }

  @GetMapping("/change")
  JsonNode latestChange(HttpServletRequest request, @PathVariable UUID id) {
    return send(requestToPhone(RequestActor.from(request), id + "/change").GET().build());
  }

  @PostMapping("/change")
  JsonNode change(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody ObjectNode input)
      throws IOException {
    var actor = RequestActor.from(request);
    if (key == null || !key.matches("[A-Za-z0-9._-]{1,100}")) {
      throw ApiError.bad("INVALID_APPROVAL", "변경 요청의 요청 키를 확인해 주세요.");
    }
    return send(
        requestToPhone(actor, id + "/change")
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(input)))
            .build());
  }

  @PostMapping
  JsonNode approve(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Approval input)
      throws IOException {
    var actor = RequestActor.from(request);
    if (input.approved() == null
        || !input.approved().isBoolean()
        || !input.approved().booleanValue()) {
      throw ApiError.bad("APPROVAL_REQUIRED", "이 후보의 예약을 승인해 주세요.");
    }
    if (key == null
        || !key.matches("[A-Za-z0-9._-]{1,100}")
        || input.reservationAt() == null
        || input.reservationAt().getEpochSecond() % 60 != 0
        || input.reservationAt().getNano() != 0
        || !clock.instant().isBefore(input.reservationAt())
        || input.reservationName() == null
        || input.reservationName().isBlank()
        || input.reservationName().strip().length() > 40
        || input.reservationName().codePoints().anyMatch(Character::isISOControl)
        || (input.previousAttemptId() != null
            && !input.previousAttemptId().matches("[a-f0-9]{32}"))) {
      throw ApiError.bad("INVALID_APPROVAL", "예약 승인 정보와 요청 키를 확인해 주세요.");
    }
    var snapshot =
        recommendations
            .poll(actor.subject(), id)
            .orElseThrow(
                () ->
                    new ApiError(HttpStatus.NOT_FOUND, "RECOMMENDATION_NOT_FOUND", "추천 작업이 없습니다."));
    var listing = snapshot.result() == null ? null : snapshot.result().listing();
    Instant now = clock.instant();
    if (snapshot.state() != RecommendationJobRegistry.State.RESULTS
        || !now.isBefore(snapshot.validUntil())
        || listing == null
        || listing.state() != PlaceRecommendationPlanner.State.RESULTS
        || !now.isBefore(listing.displayValidUntil())
        || listing.provider() != PlaceSummary.Provider.SEOUL_OPEN_DATA) {
      throw ApiError.conflict("MOCK_RECOMMENDATION_REQUIRED", "유효한 가상 식당 추천을 다시 받아 주세요.");
    }
    var ordinal = input.candidateOrdinal();
    if (ordinal == null
        || !ordinal.isIntegralNumber()
        || !ordinal.canConvertToInt()
        || listing.candidates().stream()
            .noneMatch(candidate -> candidate.ordinal() == ordinal.intValue())) {
      throw ApiError.bad("INVALID_CANDIDATE", "추천 목록의 후보를 선택해 주세요.");
    }
    var requirements = listing.requirements();
    var features = requirements.requiredFeatures();
    var party = requirements.party();
    var localTime = input.reservationAt().atZone(SEOUL);
    if (localTime.getYear() > 9999) {
      throw ApiError.bad("INVALID_APPROVAL", "예약 날짜를 확인해 주세요.");
    }
    ObjectNode payload = json.valueToTree(input);
    payload.remove("reservationAt");
    payload.put("date", localTime.toLocalDate().toString());
    payload.put("time", TIME.format(localTime));
    payload.put("reservationName", input.reservationName().strip());
    payload.set(
        "conditions",
        json.valueToTree(
            Map.of(
                "partySize",
                party.adults() + party.children(),
                "childFriendly",
                party.children() > 0 || features.contains("childFriendly"),
                "parkingRequired",
                features.contains("parking"),
                "highChairCount",
                features.contains("highChair") ? 1 : 0,
                "stepFree",
                features.contains("stepFreeAccess"),
                "maxArrivalWaitMinutes",
                requirements.maxWaitMinutes(),
                "petAccompanied",
                "NO")));
    payload.set(
        "candidates",
        json.valueToTree(
            listing.candidates().stream()
                .map(
                    candidate ->
                        Map.of(
                            "ordinal",
                            candidate.ordinal(),
                            "name",
                            candidate.name(),
                            "address",
                            candidate.address()))
                .toList()));
    return send(
        requestToPhone(actor, id)
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)))
            .build());
  }

  private HttpRequest.Builder requestToPhone(RequestActor actor, UUID id) {
    return requestToPhone(actor, id.toString());
  }

  private HttpRequest.Builder requestToPhone(RequestActor actor, String suffix) {
    return HttpRequest.newBuilder(URI.create(endpoint + suffix))
        .timeout(Duration.ofSeconds(5))
        .header("Authorization", "Bearer " + token)
        .header("X-Recommendation-Owner", actor.subject());
  }

  private JsonNode send(HttpRequest request) {
    try {
      var response = phone.send(request, HttpResponse.BodyHandlers.ofByteArray());
      var body = json.readTree(response.body());
      if (response.statusCode() == 200
          && body != null
          && body.isObject()
          && "MOCK".equals(body.path("mode").asText())
          && body.path("externalCalls").asInt(-1) == 0) {
        return body;
      }
      if (body != null && Set.of(400, 404, 409).contains(response.statusCode())) {
        var error = body.path("error");
        String code = error.path("code").asText();
        if (PHONE_ERRORS.contains(code)) {
          throw new ApiError(
              HttpStatus.valueOf(response.statusCode()), code, error.path("message").asText());
        }
      }
    } catch (HttpTimeoutException error) {
      throw new ApiError(
          HttpStatus.GATEWAY_TIMEOUT, "PHONE_RESULT_PENDING", "전화 에이전트의 처리 상태를 조회해 주세요.");
    } catch (IOException error) {
      throw unavailable();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
    throw unavailable();
  }

  private static ApiError unavailable() {
    return new ApiError(
        HttpStatus.BAD_GATEWAY, "PHONE_AGENT_UNAVAILABLE", "전화 에이전트의 처리 상태를 조회해 주세요.");
  }
}
