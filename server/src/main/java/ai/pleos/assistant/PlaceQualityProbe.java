package ai.pleos.assistant;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Bounded standalone quality probe. Dry-run by default; no Spring/DB/phone/AI. */
public final class PlaceQualityProbe {
  record Case(String id, PlaceQuery query) {
    Case {
      if (id == null
          || !id.matches("[a-z0-9-]{1,32}")
          || query == null
          || query.latitude() < 37.530
          || query.latitude() > 37.560
          || query.longitude() < 127.035
          || query.longitude() > 127.075
          || query.radiusMeters() > 1000
          || query.limit() > 3) throw invalid();
    }
  }

  record Plan(String planId, String sha256, List<Case> cases) {
    Plan {
      if (planId == null
          || !planId.matches("rec07-[a-z0-9-]{1,64}")
          || sha256 == null
          || !sha256.matches("[a-f0-9]{64}")) throw invalid();
      cases = List.copyOf(cases);
      if (cases.isEmpty()
          || cases.size() > 6
          || cases.stream().map(Case::id).distinct().count() != cases.size()
          || cases.stream().map(Case::query).distinct().count() != cases.size()) throw invalid();
    }
  }

  record Approval(Path usedMarker) {
    void claim() throws Exception {
      // Conservative local one-use marker: partial/failed runs cannot silently reset the budget.
      Files.createFile(
          usedMarker,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
  }

  record Observation(
      String caseId,
      String status,
      int returned,
      int rejected,
      int duplicateIds,
      int outsideRadius,
      int missingCuisine,
      int missingAddress,
      int missingPhone) {}

  record Report(
      String mode,
      String planId,
      String recordedAt,
      int plannedRequests,
      int adapterCalls,
      String stoppedBecause,
      List<Observation> observations) {}

  private static final ObjectMapper JSON =
      new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  public static void main(String[] args) {
    try {
      String mode = args.length == 0 ? "dry-run" : args[0];
      Path path = Path.of(args.length > 1 ? args[1] : "docs/quality/rec07-plan.json");
      if (!Set.of("dry-run", "mock", "live").contains(mode)
          || (mode.equals("live") ? args.length != 4 : args.length > 2))
        throw new IllegalArgumentException("Invalid execution mode");
      Plan plan = readPlan(path);
      Clock clock = Clock.systemUTC();
      Report report;
      if (mode.equals("dry-run")) {
        report =
            new Report(
                "DRY_RUN",
                plan.planId(),
                clock.instant().toString(),
                plan.cases().size(),
                0,
                "NOT_EXECUTED",
                List.of());
      } else if (mode.equals("mock")) {
        report = run(plan, new MockPlaceSearchAdapter(clock), false, clock);
      } else {
        Approval approval = readApproval(Path.of(args[2]), plan, clock);
        String key = PlacePreviewDemo.readPrivateKey(Path.of(args[3]));
        try (var adapter = new KakaoPlaceSearchAdapter(key, new KakaoPlaceMapper(JSON, clock))) {
          approval.claim();
          report = run(plan, adapter, true, clock);
        }
      }
      // Aggregate diagnostics only; never serialize a place, ID, name, coordinates or contact.
      System.out.println("QUALITY_REPORT " + JSON.writeValueAsString(report));
    } catch (Exception failure) {
      System.err.println("QUALITY_SETUP_FAILED: 모드·계획·승인/무료 확인·키 설정을 확인하세요.");
      System.exit(1);
    }
  }

  static Plan readPlan(Path path) throws Exception {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw invalid();
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(16_385);
    }
    if (bytes.length > 16_384) throw invalid();
    JsonNode root = JSON.readTree(bytes);
    fields(
        root,
        "planId",
        "provider",
        "category",
        "page",
        "sort",
        "maxRequests",
        "maxCostKRW",
        "cases");
    if (!root.path("planId").asText().matches("rec07-[a-z0-9-]{1,64}")
        || !root.path("provider").asText().equals("KAKAO")
        || !root.path("category").asText().equals("FD6")
        || integer(root.get("page")) != 1
        || !root.path("sort").asText().equals("distance")
        || integer(root.get("maxCostKRW")) != 0
        || !root.path("cases").isArray()) throw invalid();
    int max = integer(root.get("maxRequests"));
    if (max < 1 || max > 6 || root.get("cases").size() != max) throw invalid();
    var cases = new ArrayList<Case>();
    var ids = new HashSet<String>();
    var queries = new HashSet<PlaceQuery>();
    for (JsonNode item : root.get("cases")) {
      fields(item, "id", "query");
      String id = item.path("id").asText();
      if (!id.matches("[a-z0-9-]{1,32}") || !ids.add(id)) throw invalid();
      JsonNode q = item.get("query");
      fields(q, "latitude", "longitude", "radiusMeters", "limit");
      if (!q.path("latitude").isNumber() || !q.path("longitude").isNumber()) throw invalid();
      var query =
          new PlaceQuery(
              q.get("latitude").doubleValue(),
              q.get("longitude").doubleValue(),
              integer(q.get("radiusMeters")),
              integer(q.get("limit")));
      // Public pilot test window, not a vehicle track or an administrative-boundary assertion.
      if (query.latitude() < 37.530
          || query.latitude() > 37.560
          || query.longitude() < 127.035
          || query.longitude() > 127.075
          || query.radiusMeters() > 1000
          || query.limit() > 3
          || !queries.add(query)) throw invalid();
      cases.add(new Case(id, query));
    }
    return new Plan(
        root.get("planId").asText(),
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        List.copyOf(cases));
  }

  static Approval readApproval(Path path, Plan plan, Clock clock) throws Exception {
    JsonNode a = JSON.readTree(PlacePreviewDemo.readPrivateKey(path));
    fields(
        a,
        "approvalId",
        "planSha256",
        "userApproved",
        "approvedMaxRequests",
        "maxCostKRW",
        "mapEnabled",
        "paidApiEnabled",
        "freeQuotaRemaining",
        "attestedAt");
    UUID id = UUID.fromString(a.path("approvalId").asText());
    Instant observed = Instant.parse(a.path("attestedAt").asText());
    Duration age = Duration.between(observed, clock.instant());
    if (!a.path("planSha256").asText().equals(plan.sha256())
        || !a.path("userApproved").isBoolean()
        || !a.get("userApproved").booleanValue()
        || integer(a.get("approvedMaxRequests")) != plan.cases().size()
        || integer(a.get("maxCostKRW")) != 0
        || !a.path("mapEnabled").isBoolean()
        || !a.get("mapEnabled").booleanValue()
        || !a.path("paidApiEnabled").isBoolean()
        || a.get("paidApiEnabled").booleanValue()
        || integer(a.get("freeQuotaRemaining")) < plan.cases().size()
        || age.isNegative()
        || age.compareTo(Duration.ofMinutes(15)) > 0) throw invalid();
    return new Approval(path.toAbsolutePath().resolveSibling("quality-" + id + ".used"));
  }

  static Report run(Plan plan, PlaceSearchAdapter adapter, boolean live, Clock clock) {
    var observations = new ArrayList<Observation>();
    int calls = 0;
    String stopped = "NONE";
    for (Case item : plan.cases()) {
      if (Thread.currentThread().isInterrupted()) {
        stopped = "CANCELLED";
        break;
      }
      PlaceSearchResult result;
      calls++;
      try {
        result = adapter.search(item.query());
      } catch (RuntimeException error) {
        result = new PlaceSearchResult.Failure(PlaceSearchFailure.UPSTREAM_UNAVAILABLE);
      }
      if (result == null)
        result = new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE);
      if (result instanceof PlaceSearchResult.Success s
          && (s.places().size() > item.query().limit()
              || (s.places().isEmpty() && s.rejectedRecords() > 0)
              || s.places().stream()
                  .anyMatch(
                      p ->
                          p.provider()
                              != (live
                                  ? PlaceSummary.Provider.KAKAO
                                  : PlaceSummary.Provider.MOCK))))
        result = new PlaceSearchResult.Failure(PlaceSearchFailure.INVALID_RESPONSE);
      if (result instanceof PlaceSearchResult.Failure failure) {
        stopped = failure.kind().name();
        observations.add(new Observation(item.id(), stopped, 0, 0, 0, 0, 0, 0, 0));
        break;
      }
      var s = (PlaceSearchResult.Success) result;
      // Raw listing values are used for this response only, never accumulated across queries.
      var ids = new HashSet<String>();
      int duplicate = 0, outside = 0, cuisine = 0, address = 0, phone = 0;
      for (PlaceSummary p : s.places()) {
        if (!ids.add(p.providerPlaceId())) duplicate++;
        if (distance(item.query(), p) > item.query().radiusMeters() + 30) outside++;
        if (p.cuisine() == null) cuisine++;
        if (p.address() == null) address++;
        if (p.phone() == null) phone++;
      }
      observations.add(
          new Observation(
              item.id(),
              s.places().isEmpty() ? "EMPTY" : "SUCCESS",
              s.places().size(),
              s.rejectedRecords(),
              duplicate,
              outside,
              cuisine,
              address,
              phone));
    }
    return new Report(
        live ? "REAL_SEARCH" : "MOCK",
        plan.planId(),
        clock.instant().toString(),
        plan.cases().size(),
        calls,
        stopped,
        List.copyOf(observations));
  }

  private static double distance(PlaceQuery q, PlaceSummary p) {
    double a = Math.toRadians(q.latitude()), b = Math.toRadians(p.latitude());
    double lat = Math.sin((b - a) / 2),
        lon = Math.sin(Math.toRadians(p.longitude() - q.longitude()) / 2);
    return 6_371_000
        * 2
        * Math.asin(Math.sqrt(Math.min(1, lat * lat + Math.cos(a) * Math.cos(b) * lon * lon)));
  }

  private static int integer(JsonNode node) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) throw invalid();
    return node.intValue();
  }

  private static void fields(JsonNode node, String... expected) {
    if (node == null || !node.isObject()) throw invalid();
    var found = new HashSet<String>();
    node.fieldNames().forEachRemaining(found::add);
    if (!found.equals(Set.of(expected))) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid quality probe configuration");
  }
}
