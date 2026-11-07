package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class KakaoPlaceMapperTest {
  private static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");
  private final ObjectMapper json = new ObjectMapper();
  private final KakaoPlaceMapper mapper =
      new KakaoPlaceMapper(json, Clock.fixed(NOW, ZoneOffset.UTC));

  private ObjectNode fixture() throws IOException {
    try (var stream = getClass().getResourceAsStream("/places/kakao-fictional.json")) {
      return (ObjectNode) json.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private PlaceSearchResult.Success success(ObjectNode body) {
    return (PlaceSearchResult.Success) mapper.mapResponse(200, body.toString());
  }

  @Test
  void normalizesCoordinatesCuisineAddressAndMissingContactWithoutInventingFacts()
      throws Exception {
    var result = success(fixture());
    assertThat(result.fetchedAt()).isEqualTo(NOW);
    assertThat(result.rejectedRecords()).isZero();
    assertThat(result.places()).hasSize(2);
    var first = result.places().getFirst();
    assertThat(first.provider()).isEqualTo(PlaceSummary.Provider.KAKAO);
    assertThat(first.providerPlaceId()).isEqualTo("fictional-kakao-1");
    assertThat(first.latitude()).isEqualTo(37.544);
    assertThat(first.longitude()).isEqualTo(127.055);
    assertThat(first.cuisine()).isEqualTo("한식");
    assertThat(first.address()).isEqualTo("가상 도로명 주소 A");
    assertThat(first.distanceMeters()).isEqualTo(120);
    assertThat(first.phone()).isNull();
    assertThat(first.placeUrl()).isNull();
    var second = result.places().get(1);
    assertThat(second.cuisine()).isNull();
    assertThat(second.address()).isEqualTo("가상 지번 주소 B");
    assertThat(second.distanceMeters()).isNull();
    // These listings have the same name but distinct provider IDs and remain separate.
    assertThat(second.name()).isEqualTo(first.name());
  }

  @Test
  void duplicatesAreRemovedAndInvalidRecordsAreCounted() throws Exception {
    var body = fixture();
    ArrayNode documents = (ArrayNode) body.get("documents");
    documents.add(documents.get(0).deepCopy());
    documents.addObject().put("id", "invalid");
    var result = success(body);
    assertThat(result.places()).hasSize(2);
    assertThat(result.rejectedRecords()).isEqualTo(1);
  }

  @Test
  void genuinelyEmptyResultsAreSuccessfulButAllRejectedRecordsAreFailures() throws Exception {
    var body = fixture();
    ((ArrayNode) body.get("documents")).removeAll();
    assertThat(success(body).places()).isEmpty();
    ((ArrayNode) body.get("documents")).addObject().put("id", "invalid");
    assertThat(mapper.mapResponse(200, body.toString()))
        .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE));
  }

  @ParameterizedTest
  @CsvSource({
    "x,NaN",
    "y,Infinity",
    "x,181",
    "y,-91",
    "x,not-a-number",
    "distance,-1",
    "distance,1.5"
  })
  void invalidNumbersRejectTheRecordRatherThanUsingZero(String field, String value)
      throws Exception {
    var body = fixture();
    ((ObjectNode) body.get("documents").get(0)).put(field, value);
    assertThat(success(body).places())
        .extracting(PlaceSummary::providerPlaceId)
        .containsExactly("fictional-kakao-2");
    assertThat(success(body).rejectedRecords()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"id", "place_name", "x", "y", "category_group_code"})
  void missingRequiredFieldsDoNotBecomePlausiblePlaces(String field) throws Exception {
    var body = fixture();
    ((ObjectNode) body.get("documents").get(0)).remove(field);
    assertThat(success(body).places()).hasSize(1);
    assertThat(success(body).rejectedRecords()).isEqualTo(1);
  }

  @Test
  void nonRestaurantAndIncorrectFieldTypesAreRejected() throws Exception {
    var body = fixture();
    ((ObjectNode) body.get("documents").get(0)).put("category_group_code", "PK6");
    ((ObjectNode) body.get("documents").get(1)).put("place_name", true);
    assertThat(mapper.mapResponse(200, body.toString()))
        .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"한식", "양식", "중식", "일식", "분식"})
  void mapsOnlyDocumentedCuisineGroups(String cuisine) throws Exception {
    var body = fixture();
    ((ObjectNode) body.get("documents").get(0))
        .put("category_name", "음식점 > " + cuisine + " > 가상 세부분류");
    assertThat(success(body).places().getFirst().cuisine()).isEqualTo(cuisine);
  }

  @Test
  void unknownCuisineIsNotGuessedAndOversizedPagesAreNotSilentlyTruncated() throws Exception {
    var body = fixture();
    ((ObjectNode) body.get("documents").get(0)).put("category_name", "음식점 > 가상미분류");
    assertThat(success(body).places().getFirst().cuisine()).isNull();
    ArrayNode documents = (ArrayNode) body.get("documents");
    while (documents.size() < 16) documents.add(documents.get(0).deepCopy());
    assertThat(mapper.mapResponse(200, body.toString()))
        .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "null",
        "[]",
        "{}",
        "{\"documents\":null}",
        "{\"documents\":{}}",
        "{\"documents\":[]} trailing",
        "{not-json"
      })
  void malformedResponsesAreNotEmptySearchSuccesses(String body) {
    assertThat(mapper.mapResponse(200, body))
        .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE));
  }

  @Test
  void missingAndOversizedBodiesFailWithoutKeepingTheBody() {
    assertThat(mapper.mapResponse(200, null))
        .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE));
    assertThat(mapper.mapResponse(200, "x".repeat(262_145)))
        .isEqualTo(new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE));
  }

  @ParameterizedTest
  @CsvSource({
    "401,AUTHENTICATION",
    "403,FORBIDDEN",
    "429,RATE_LIMITED",
    "500,UPSTREAM_UNAVAILABLE",
    "503,UPSTREAM_UNAVAILABLE",
    "400,INVALID_QUERY",
    "302,INVALID_RESPONSE",
    "404,INVALID_RESPONSE",
    "204,INVALID_RESPONSE"
  })
  void providerErrorsStayDistinctFromEmptyResultsAndNeverExposeRawErrors(
      int status, PlaceSearchFailure kind) {
    assertThat(mapper.mapResponse(status, "private-upstream-response"))
        .isEqualTo(new PlaceSearchResult.Failure(kind));
  }
}
