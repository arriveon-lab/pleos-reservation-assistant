package ai.pleos.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline response boundary, not an HTTP adapter or Spring bean. No keys, IO, facts or persistence.
 */
public final class KakaoPlaceMapper {
  private static final int MAX_BODY_CHARACTERS = 262_144;
  private static final Set<String> CUISINES = Set.of("한식", "양식", "중식", "일식", "분식");
  private final ObjectMapper json;
  private final Clock clock;

  public KakaoPlaceMapper(ObjectMapper json, Clock clock) {
    this.json = json;
    this.clock = clock;
  }

  public PlaceSearchResult mapResponse(int httpStatus, String body) {
    if (httpStatus == 401) return failure(PlaceSearchFailure.AUTHENTICATION);
    if (httpStatus == 403) return failure(PlaceSearchFailure.FORBIDDEN);
    if (httpStatus == 429) return failure(PlaceSearchFailure.RATE_LIMITED);
    if (httpStatus >= 500 && httpStatus <= 599)
      return failure(PlaceSearchFailure.UPSTREAM_UNAVAILABLE);
    if (httpStatus == 400) return failure(PlaceSearchFailure.INVALID_QUERY);
    if (httpStatus != 200 || body == null || body.length() > MAX_BODY_CHARACTERS)
      return failure(PlaceSearchFailure.INVALID_RESPONSE);
    try {
      JsonNode root =
          json.reader()
              .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(body);
      if (root == null || !root.isObject() || !root.path("documents").isArray())
        return failure(PlaceSearchFailure.INVALID_RESPONSE);
      JsonNode documents = root.get("documents");
      // The first-page contract is deliberately bounded; no silent pagination or truncation.
      if (documents.size() > 15) return failure(PlaceSearchFailure.INVALID_RESPONSE);
      List<PlaceSummary> places = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();
      int rejected = 0;
      for (JsonNode document : documents) {
        try {
          PlaceSummary place = mapPlace(document);
          if (seen.add(place.providerPlaceId())) places.add(place);
        } catch (IllegalArgumentException e) {
          rejected++;
        }
      }
      if (!documents.isEmpty() && places.isEmpty())
        return failure(PlaceSearchFailure.INVALID_RESPONSE);
      return new PlaceSearchResult.Success(places, clock.instant(), rejected);
    } catch (JsonProcessingException e) {
      return failure(PlaceSearchFailure.INVALID_RESPONSE);
    }
  }

  private PlaceSummary mapPlace(JsonNode document) {
    String group = text(document, "category_group_code", false);
    if (!"FD6".equals(group)) throw new IllegalArgumentException("Not a restaurant");
    String address = text(document, "road_address_name", false);
    if (address == null) address = text(document, "address_name", false);
    String distance = text(document, "distance", false);
    return new PlaceSummary(
        PlaceSummary.Provider.KAKAO,
        text(document, "id", true),
        text(document, "place_name", true),
        cuisine(text(document, "category_name", false)),
        number(document, "y"),
        number(document, "x"),
        address,
        text(document, "phone", false),
        text(document, "place_url", false),
        distance == null ? null : Integer.valueOf(distance));
  }

  private String cuisine(String category) {
    if (category == null) return null;
    String[] levels = category.split(">");
    if (levels.length < 2 || !levels[0].strip().equals("음식점")) return null;
    String cuisine = levels[1].strip();
    return CUISINES.contains(cuisine) ? cuisine : null;
  }

  private String text(JsonNode document, String key, boolean required) {
    JsonNode value = document.get(key);
    if (value == null || value.isNull() || (value.isTextual() && value.textValue().isBlank())) {
      if (required) throw new IllegalArgumentException("Missing listing field");
      return null;
    }
    if (!value.isTextual()) throw new IllegalArgumentException("Invalid listing field type");
    return value.textValue().strip();
  }

  private double number(JsonNode document, String key) {
    return Double.parseDouble(text(document, key, true));
  }

  private PlaceSearchResult.Failure failure(PlaceSearchFailure kind) {
    return new PlaceSearchResult.Failure(kind);
  }
}
