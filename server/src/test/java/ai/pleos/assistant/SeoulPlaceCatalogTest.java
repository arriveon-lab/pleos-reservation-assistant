package ai.pleos.assistant;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeoulPlaceCatalogTest {
  private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  @TempDir Path temporary;

  private ObjectNode fixture() throws IOException {
    try (var stream = getClass().getResourceAsStream("/places/seoul-catalog-fictional.json")) {
      return (ObjectNode) mapper.readTree(stream);
    }
  }

  private SeoulPlaceCatalog load(ObjectNode tree) throws IOException {
    Path file = temporary.resolve("catalog.json");
    mapper.writeValue(file.toFile(), tree);
    return SeoulPlaceCatalog.load(file, mapper);
  }

  private SeoulPlaceSearchAdapter adapter(SeoulPlaceCatalog catalog, Instant now) {
    return new SeoulPlaceSearchAdapter(catalog, Clock.fixed(now, ZoneOffset.UTC));
  }

  private ObjectNode first(ObjectNode tree) {
    return (ObjectNode) tree.withArray("entries").get(0);
  }

  @Test
  void privateSnapshotSurvivesReloadWithStableIdentityAndOriginalTimes() throws Exception {
    var data = load(fixture());
    var reloaded = load(fixture());
    assertEquals(data, reloaded);
    assertEquals(4, data.entries().size());
    assertEquals(Instant.parse("2026-11-07T00:00:00Z"), data.sourceSnapshotAt());
    for (var entry : data.entries()) {
      assertEquals(SeoulPlaceCatalog.stableId(entry.sourceRecordId()), entry.catalogPlaceId());
      assertEquals("20191231000000", entry.rowModifiedRaw());
      assertEquals("UNKNOWN", entry.rowTimeZone());
      assertEquals("UNVERIFIED", entry.contactStatus());
      assertFalse(data.toString().contains(entry.name()));
      assertFalse(entry.toString().contains(entry.phone()));
      assertTrue(
          data.dispatchBlockers(entry.catalogPlaceId()).contains("PROCESSOR_REVIEW_REQUIRED"));
    }
    assertEquals(Set.of("CATALOG_PLACE_NOT_FOUND"), data.dispatchBlockers("Kakao-ordinal-1"));
  }

  @Test
  void localAdapterFeedsAtMostThreeUnknownCandidatesWithoutBookingAuthority() throws Exception {
    var data = load(fixture());
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    var query = new PlaceQuery(37.544, 127.055, 1000, 15);
    var adapter = adapter(data, NOW);
    var result = assertInstanceOf(PlaceSearchResult.Success.class, adapter.search(query));
    assertEquals(4, result.places().size());
    var planner = new PlaceRecommendationPlanner(clock);
    var plan =
        planner.evaluate(
            PlaceSummary.Provider.SEOUL_OPEN_DATA,
            query,
            result,
            new PlaceRecommendationPlanner.Requirements(
                new Model.Party(2, 1), 15, Set.of("parking", "childFriendly"), Set.of("한식")));
    assertEquals(PlaceRecommendationPlanner.State.RESULTS, plan.state());
    assertEquals(3, plan.candidates().size());
    for (var candidate : plan.candidates()) {
      assertFalse(candidate.bookable());
      assertFalse(candidate.inquiryDispatchAllowed());
      assertFalse(candidate.questions().isEmpty());
      assertTrue(
          candidate.questions().stream().allMatch(q -> q.certainty() == Model.Certainty.UNKNOWN));
    }
    assertEquals(data.sourceSnapshotAt(), adapter.provenance().sourceSnapshotAt());
    assertEquals(NOW, result.fetchedAt());
  }

  @Test
  void unknownOrExpiredSnapshotIsFailureRatherThanEmptyAndCannotBeReagedByQuery() throws Exception {
    var tree = fixture();
    tree.putNull("sourceSnapshotAt");
    assertInstanceOf(
        PlaceSearchResult.Failure.class,
        adapter(load(tree), NOW).search(new PlaceQuery(37.544, 127.055, 1000, 3)));
    var data = load(fixture());
    var expired = adapter(data, data.sourceSnapshotAt().plus(Duration.ofDays(7)));
    assertEquals(
        new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE),
        expired.search(new PlaceQuery(37.544, 127.055, 1000, 3)));
    assertEquals(Instant.parse("2026-11-07T00:00:00Z"), expired.provenance().sourceSnapshotAt());
  }

  @Test
  void futureAcquisitionOrSnapshotAndOversizedRadiusFailClosed() throws Exception {
    var data = load(fixture());
    assertInstanceOf(
        PlaceSearchResult.Failure.class,
        adapter(data, NOW.minusSeconds(1)).search(new PlaceQuery(37.544, 127.055, 1000, 3)));
    assertEquals(
        new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_QUERY),
        adapter(data, NOW).search(new PlaceQuery(37.544, 127.055, 1001, 3)));
    assertEquals(
        new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_QUERY),
        adapter(data, NOW).search(new PlaceQuery(38, 127, 1000, 3)));
    var tree = fixture();
    tree.put("sourceSnapshotAt", "2026-11-11T00:00:00Z");
    assertThrows(IOException.class, () -> load(tree));
  }

  @Test
  void genuineEmptyAndRadiusFilteredResultsAreSuccessfulAndDoNotUseMockFallback() throws Exception {
    var tree = fixture();
    tree.set("entries", mapper.createArrayNode());
    tree.put("inputRows", 0);
    var empty =
        assertInstanceOf(
            PlaceSearchResult.Success.class,
            adapter(load(tree), NOW).search(new PlaceQuery(37.544, 127.055, 1000, 3)));
    assertTrue(empty.places().isEmpty());
    tree.put("inputRows", 4);
    tree.put("rejectedRows", 4);
    assertInstanceOf(
        PlaceSearchResult.Failure.class,
        adapter(load(tree), NOW).search(new PlaceQuery(37.544, 127.055, 1000, 3)));
    var filtered =
        assertInstanceOf(
            PlaceSearchResult.Success.class,
            adapter(load(fixture()), NOW).search(new PlaceQuery(37.545, 127.055, 1, 3)));
    assertTrue(filtered.places().isEmpty());
  }

  @Test
  void sourceLaunderingDuplicateIdsAndInvalidClaimsAreRejected() throws Exception {
    for (String field :
        new String[] {"sourceKind", "datasetId", "catalogUrl", "licenseUrl", "attribution"}) {
      var tree = fixture();
      tree.put(field, "KAKAO");
      assertThrows(IOException.class, () -> load(tree));
    }
    var duplicate = fixture();
    duplicate.withArray("entries").add(first(duplicate).deepCopy());
    assertThrows(IOException.class, () -> load(duplicate));
    for (String field :
        new String[] {"catalogPlaceId", "contactStatus", "administrativeStatus", "rowTimeZone"}) {
      var tree = fixture();
      first(tree).put(field, "VERIFIED");
      assertThrows(IOException.class, () -> load(tree));
    }
  }

  @Test
  void malformedCoordinatesMissingIdentityAndMobileNumbersAreRejectedWithoutLeaking()
      throws Exception {
    for (String field : new String[] {"name", "address", "phone", "latitude", "longitude"}) {
      var tree = fixture();
      first(tree).remove(field);
      var error = assertThrows(IOException.class, () -> load(tree));
      assertEquals("Invalid public catalog", error.getMessage());
      assertNull(error.getCause());
    }
    for (String phone :
        new String[] {"01012345678", "0200001234", "0212345678\nprivate", "15881234"}) {
      var tree = fixture();
      first(tree).put("phone", phone);
      assertThrows(IOException.class, () -> load(tree));
    }
    var numericPhone = fixture();
    first(numericPhone).put("phone", 212345678);
    assertThrows(IOException.class, () -> load(numericPhone));
    var numericString = fixture();
    first(numericString).put("latitude", "37.544");
    assertThrows(IOException.class, () -> load(numericString));
    var outside = fixture();
    first(outside).put("latitude", 38.0);
    assertThrows(IOException.class, () -> load(outside));
  }

  @Test
  void unknownFieldsDuplicateJsonAndTrailingPayloadAreRejected() throws Exception {
    var missing =
        assertThrows(
            IOException.class,
            () ->
                SeoulPlaceCatalog.load(temporary.resolve("private-missing-catalog.json"), mapper));
    assertEquals("Public catalog unavailable", missing.getMessage());
    assertNull(missing.getCause());
    var tree = fixture();
    tree.put("dispatchAllowed", true);
    assertThrows(IOException.class, () -> load(tree));
    Path file = temporary.resolve("invalid.json");
    Files.writeString(file, mapper.writeValueAsString(fixture()) + " {}");
    assertThrows(IOException.class, () -> SeoulPlaceCatalog.load(file, mapper));
    Files.writeString(
        file,
        mapper
            .writeValueAsString(fixture())
            .replace("\"OA-18655\"", "\"OA-18655\",\"datasetId\":\"OA-18655\""));
    assertThrows(IOException.class, () -> SeoulPlaceCatalog.load(file, mapper));
    Files.write(file, new byte[2 * 1024 * 1024 + 1]);
    assertThrows(IOException.class, () -> SeoulPlaceCatalog.load(file, mapper));
  }
}
