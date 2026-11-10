package ai.pleos.assistant;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PlaceQualityProbeTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final Path PLAN = Path.of("../docs/quality/rec07-plan.json");
  @TempDir Path tmp;

  @Test
  void publicPlanHasSixDistinctBoundedQueriesAndNoCredentialFields() throws Exception {
    var plan = PlaceQualityProbe.readPlan(PLAN);
    assertThat(plan.cases()).hasSize(6);
    assertThat(plan.sha256()).matches("[a-f0-9]{64}");
    assertThat(plan.cases())
        .allSatisfy(
            c -> {
              assertThat(c.query().limit()).isEqualTo(3);
              assertThat(c.query().radiusMeters()).isBetween(1, 1000);
            });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "cost",
        "count",
        "provider",
        "category",
        "page",
        "sort",
        "stringCount",
        "duplicateId",
        "duplicateQuery",
        "latitude",
        "longitude",
        "radius",
        "limit",
        "nullLatitude",
        "floatRadius",
        "unknown",
        "missing"
      })
  void malformedOrBroadenedPlansAreRejectedBeforeAnyAdapterExists(String kind) throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(PLAN.toFile());
    ObjectNode item = (ObjectNode) root.get("cases").get(0);
    ObjectNode query = (ObjectNode) item.get("query");
    switch (kind) {
      case "cost" -> root.put("maxCostKRW", 1);
      case "count" -> root.put("maxRequests", 7);
      case "provider" -> root.put("provider", "OTHER");
      case "category" -> root.put("category", "CE7");
      case "page" -> root.put("page", 2);
      case "sort" -> root.put("sort", "accuracy");
      case "stringCount" -> root.put("maxRequests", "6");
      case "duplicateId" -> item.put("id", root.get("cases").get(1).get("id").asText());
      case "duplicateQuery" -> item.set("query", root.get("cases").get(1).get("query"));
      case "latitude" -> query.put("latitude", 38);
      case "longitude" -> query.put("longitude", 128);
      case "radius" -> query.put("radiusMeters", 2000);
      case "limit" -> query.put("limit", 4);
      case "nullLatitude" -> query.putNull("latitude");
      case "floatRadius" -> query.put("radiusMeters", 1000.5);
      case "unknown" -> root.put("key", "fictional-secret");
      case "missing" -> query.remove("limit");
      default -> throw new AssertionError();
    }
    Path path = tmp.resolve("plan.json");
    JSON.writeValue(path.toFile(), root);
    assertThatThrownBy(() -> PlaceQualityProbe.readPlan(path))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void duplicateJsonFieldsCannotHideAnEarlierPaidOrUnboundedSetting() throws Exception {
    Path path = tmp.resolve("duplicate.json");
    Files.writeString(
        path,
        Files.readString(PLAN)
            .replace("\"maxCostKRW\": 0", "\"maxCostKRW\": 1, \"maxCostKRW\": 0"));
    assertThatThrownBy(() -> PlaceQualityProbe.readPlan(path)).isInstanceOf(Exception.class);
  }

  @Test
  void planMustBeABoundedRegularFileRatherThanALinkOrDirectory() throws Exception {
    Path large = tmp.resolve("large.json");
    Files.writeString(large, " ".repeat(16_385));
    assertThatThrownBy(() -> PlaceQualityProbe.readPlan(large))
        .isInstanceOf(IllegalArgumentException.class);
    Path link = tmp.resolve("link.json");
    Files.createSymbolicLink(link, PLAN.toAbsolutePath());
    assertThatThrownBy(() -> PlaceQualityProbe.readPlan(link))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PlaceQualityProbe.readPlan(tmp))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ObjectNode approval(PlaceQualityProbe.Plan plan) {
    return JSON.createObjectNode()
        .put("approvalId", UUID.randomUUID().toString())
        .put("planSha256", plan.sha256())
        .put("userApproved", true)
        .put("approvedMaxRequests", 6)
        .put("maxCostKRW", 0)
        .put("mapEnabled", true)
        .put("paidApiEnabled", false)
        .put("freeQuotaRemaining", 6)
        .put("attestedAt", NOW.toString());
  }

  private Path privateFile(ObjectNode content) throws Exception {
    Path path = tmp.resolve(UUID.randomUUID() + ".json");
    Files.createFile(
        path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    JSON.writeValue(path.toFile(), content);
    return path;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unapproved",
        "fingerprint",
        "count",
        "cost",
        "disabled",
        "paid",
        "quota",
        "stale",
        "future",
        "stringFlag"
      })
  void liveApprovalRequiresExactScopeAndFreshFreeOnlyAttestation(String kind) throws Exception {
    var plan = PlaceQualityProbe.readPlan(PLAN);
    ObjectNode content = approval(plan);
    switch (kind) {
      case "unapproved" -> content.put("userApproved", false);
      case "fingerprint" -> content.put("planSha256", "0".repeat(64));
      case "count" -> content.put("approvedMaxRequests", 7);
      case "cost" -> content.put("maxCostKRW", 1);
      case "disabled" -> content.put("mapEnabled", false);
      case "paid" -> content.put("paidApiEnabled", true);
      case "quota" -> content.put("freeQuotaRemaining", 5);
      case "stale" -> content.put("attestedAt", NOW.minusSeconds(901).toString());
      case "future" -> content.put("attestedAt", NOW.plusSeconds(1).toString());
      case "stringFlag" -> content.put("paidApiEnabled", "false");
      default -> throw new AssertionError();
    }
    Path path = privateFile(content);
    assertThatThrownBy(() -> PlaceQualityProbe.readApproval(path, plan, CLOCK))
        .isInstanceOf(Exception.class);
    try (var files = Files.list(tmp)) {
      assertThat(files.filter(p -> p.getFileName().toString().endsWith(".used")).count()).isZero();
    }
  }

  @Test
  void oneApprovalCanBeClaimedOnlyOnceEvenWithConcurrentExecutions() throws Exception {
    var plan = PlaceQualityProbe.readPlan(PLAN);
    Path path = privateFile(approval(plan));
    var grant = PlaceQualityProbe.readApproval(path, plan, CLOCK);
    AtomicInteger admitted = new AtomicInteger();
    try (var callers = Executors.newFixedThreadPool(4)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            callers.submit(
                () -> {
                  try {
                    grant.claim();
                    admitted.incrementAndGet();
                  } catch (Exception alreadyUsed) {
                    /* Expected atomic rejection. */
                  }
                }));
      for (var future : futures) future.get(2, TimeUnit.SECONDS);
    }
    assertThat(admitted).hasValue(1);
    assertThatThrownBy(grant::claim).isInstanceOf(Exception.class);
    assertThat(Files.getPosixFilePermissions(grant.usedMarker()))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
  }

  @Test
  void approvalHashRejectsEvenAReformattedPlanAfterApproval() throws Exception {
    var plan = PlaceQualityProbe.readPlan(PLAN);
    Path path = privateFile(approval(plan));
    Path changed = tmp.resolve("changed.json");
    Files.writeString(changed, Files.readString(PLAN) + "\n");
    var changedPlan = PlaceQualityProbe.readPlan(changed);
    assertThatThrownBy(() -> PlaceQualityProbe.readApproval(path, changedPlan, CLOCK))
        .isInstanceOf(Exception.class);
  }

  @ParameterizedTest
  @EnumSource(PlaceSearchFailure.class)
  void everyProviderFailureStopsTheCampaignWithoutRetryOrFallback(PlaceSearchFailure failure)
      throws Exception {
    var plan = PlaceQualityProbe.readPlan(PLAN);
    AtomicInteger calls = new AtomicInteger();
    var report =
        PlaceQualityProbe.run(
            plan,
            q -> {
              calls.incrementAndGet();
              return new PlaceSearchResult.Failure(failure);
            },
            true,
            CLOCK);
    assertThat(report.adapterCalls()).isEqualTo(1);
    assertThat(calls).hasValue(1);
    assertThat(report.stoppedBecause()).isEqualTo(failure.name());
    assertThat(report.observations()).hasSize(1);
  }

  @Test
  void reportContainsOnlyAggregateQualityDiagnosticsNotListingValues() throws Exception {
    var plan = PlaceQualityProbe.readPlan(PLAN);
    var place =
        new PlaceSummary(
            PlaceSummary.Provider.KAKAO,
            "fictional-place-id",
            "가상 비공개 상호",
            null,
            37.60,
            127.10,
            null,
            "010-0000-0000",
            "https://example.invalid/private-place",
            5000);
    var report =
        PlaceQualityProbe.run(
            plan, q -> new PlaceSearchResult.Success(List.of(place, place), NOW, 1), true, CLOCK);
    assertThat(report.adapterCalls()).isEqualTo(6);
    assertThat(report.observations())
        .allSatisfy(
            o -> {
              assertThat(o.duplicateIds()).isEqualTo(1);
              assertThat(o.outsideRadius()).isEqualTo(2);
              assertThat(o.missingCuisine()).isEqualTo(2);
              assertThat(o.missingAddress()).isEqualTo(2);
              assertThat(o.missingPhone()).isZero();
            });
    assertThat(JSON.writeValueAsString(report))
        .doesNotContain(
            "가상 비공개 상호", "fictional-place-id", "010-0000-0000", "example.invalid", "37.6", "127.1");
  }

  @Test
  void mockAndEmptySuccessAreExplicitAndNeverPromotedToActualPlaceEvidence() throws Exception {
    var plan = PlaceQualityProbe.readPlan(PLAN);
    var report = PlaceQualityProbe.run(plan, new MockPlaceSearchAdapter(CLOCK), false, CLOCK);
    assertThat(report.mode()).isEqualTo("MOCK");
    assertThat(report.adapterCalls()).isEqualTo(6);
    var empty =
        PlaceQualityProbe.run(
            plan, q -> new PlaceSearchResult.Success(List.of(), NOW, 0), true, CLOCK);
    assertThat(empty.observations()).allSatisfy(o -> assertThat(o.status()).isEqualTo("EMPTY"));
  }

  @Test
  void wrongSourceAndNullOrThrownResponsesFailSafely() throws Exception {
    var plan = PlaceQualityProbe.readPlan(PLAN);
    var wrong = PlaceQualityProbe.run(plan, new MockPlaceSearchAdapter(CLOCK), true, CLOCK);
    assertThat(wrong.stoppedBecause()).isEqualTo("INVALID_RESPONSE");
    assertThat(wrong.adapterCalls()).isEqualTo(1);
    var missing = PlaceQualityProbe.run(plan, q -> null, true, CLOCK);
    assertThat(missing.stoppedBecause()).isEqualTo("INVALID_RESPONSE");
    var thrown =
        PlaceQualityProbe.run(
            plan,
            q -> {
              throw new IllegalStateException("secret-upstream");
            },
            true,
            CLOCK);
    assertThat(thrown.stoppedBecause()).isEqualTo("UPSTREAM_UNAVAILABLE");
    assertThat(JSON.writeValueAsString(thrown)).doesNotContain("secret-upstream");
  }

  @Test
  void dryRunCliDoesNotReadCredentialsAndDispatchesNothing() throws Exception {
    Process process =
        new ProcessBuilder(
                "java",
                "-cp",
                System.getProperty("placeTestClasspath"),
                PlaceQualityProbe.class.getName(),
                "dry-run",
                PLAN.toString())
            .redirectErrorStream(true)
            .start();
    assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(process.exitValue()).isZero();
    assertThat(output)
        .contains(
            "\"mode\":\"DRY_RUN\"", "\"plannedRequests\":6", "\"adapterCalls\":0", "NOT_EXECUTED");
    assertThat(output).doesNotContain("Authorization", "KakaoAK");
  }
}
