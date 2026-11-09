package ai.pleos.assistant;

import static ai.pleos.assistant.PlaceDetailEnricher.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlaceDetailEnricherTest {
  @Test
  void approved_real_source_keeps_read_only_mode_and_unverified_identity_is_blocked() {
    var calls = new AtomicInteger();
    var enricher =
        new PlaceDetailEnricher(
            Clock.fixed(NOW, ZoneOffset.UTC),
            Mode.READ_ONLY_WEB,
            (s, d) -> {
              calls.incrementAndGet();
              return new Page(s.url(), html(business()), NOW, Mode.READ_ONLY_WEB);
            });
    var unverified =
        enricher.enrich(
            new Identity(ID.name(), ID.address()),
            List.of(A),
            NOW.plusSeconds(60),
            Set.of(Field.PARKING),
            () -> true);
    assertThat(unverified.reasons()).contains(Reason.DATA_USE_BLOCKED);
    assertThat(unverified.remainingQuestions())
        .contains("PARKING", "ARRIVAL_RESERVATION_AVAILABLE");
    assertThat(calls).hasValue(0);
    var approved =
        enricher.enrich(
            new Identity(ID.name(), ID.address(), Origin.DIRECT_MERCHANT),
            List.of(A),
            NOW.plusSeconds(60),
            Set.of(),
            () -> true);
    assertThat(approved.outcome()).isEqualTo(Outcome.DETAILS);
    assertThat(approved.mode()).isEqualTo(Mode.READ_ONLY_WEB);
    assertThat(calls).hasValue(1);
  }

  static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
  static final Identity ID = new Identity("가상식당 성수점", "가상시 가상구 가상로 1", Origin.MOCK);
  static final Source A =
      new Source(
          URI.create("https://merchant.example.invalid/seongsu"), "merchant.example.invalid");
  static final Source B =
      new Source(URI.create("https://merchant.example.invalid/info"), "merchant.example.invalid");
  static final ObjectMapper JSON = new ObjectMapper();

  static class Time extends Clock {
    Instant now = NOW;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  static ObjectNode business() {
    return JSON.createObjectNode()
        .put("@context", "https://schema.org")
        .put("@type", "Restaurant")
        .put("name", ID.name())
        .put("address", ID.address())
        .put("servesCuisine", "한식")
        .put("openingHours", "Mo-Fr 11:00-21:00")
        .put("hasMenu", "/menu")
        .put("priceRange", "10000–20000 KRW")
        .put("acceptsReservations", true);
  }

  static String html(ObjectNode business) {
    return "<html><script type='application/ld+json'>" + business + "</script></html>";
  }

  private Report report(ObjectNode node) {
    return new PlaceDetailEnricher(
            Clock.fixed(NOW, ZoneOffset.UTC),
            Mode.MOCK,
            (s, d) -> new Page(s.url(), html(node), NOW, Mode.MOCK))
        .enrich(
            ID,
            List.of(A),
            NOW.plusSeconds(120),
            Set.of(Field.PARKING, Field.CHILD_FRIENDLY),
            () -> true);
  }

  static Detail detail(Report report, Field field) {
    return report.details().stream().filter(d -> d.field() == field).findFirst().orElseThrow();
  }

  @Test
  void matching_source_reports_details_without_arrival_or_action_authority() {
    Report report = report(business());
    assertThat(report.outcome()).isEqualTo(Outcome.DETAILS);
    assertThat(detail(report, Field.MENU_URL).value())
        .isEqualTo("https://merchant.example.invalid/menu");
    assertThat(detail(report, Field.RESERVATION_POLICY).value()).isEqualTo("true");
    assertThat(report.remainingQuestions())
        .contains("ARRIVAL_WAIT_MINUTES", "ARRIVAL_RESERVATION_AVAILABLE");
    assertThat(report.bookable()).isFalse();
    assertThat(report.inquiryDispatchAllowed()).isFalse();
    Evidence evidence = detail(report, Field.CUISINE).evidence().getFirst();
    assertThat(evidence.sourceType()).isEqualTo(SourceType.WEB_JSON_LD);
    assertThat(evidence.scope()).isEqualTo(Model.Scope.GENERAL);
    assertThat(evidence.appliesAt()).isNull();
    assertThat(evidence.sourceUpdatedAt()).isNull();
    assertThat(evidence.validUntil()).isEqualTo(NOW.plusSeconds(120));
    assertThat(evidence.certainty()).contains("REPORTED_WEB");
  }

  @Test
  void same_name_different_branch_address_cannot_enrich() {
    Report report = report(business().put("address", "가상시 다른구 다른로 99"));
    assertThat(report.outcome()).isEqualTo(Outcome.UNKNOWN);
    assertThat(report.reasons()).contains(Reason.IDENTITY_MISMATCH);
    assertThat(report.details()).allMatch(d -> d.value() == null);
  }

  @Test
  void branch_suffix_is_not_discarded() {
    assertThat(report(business().put("name", "가상식당 다른점")).outcome()).isEqualTo(Outcome.UNKNOWN);
  }

  @Test
  void missing_identity_skips_fetch_entirely() {
    AtomicInteger count = new AtomicInteger();
    var report =
        new PlaceDetailEnricher(
                Clock.fixed(NOW, ZoneOffset.UTC),
                Mode.MOCK,
                (s, d) -> {
                  count.incrementAndGet();
                  throw new AssertionError();
                })
            .enrich(
                new Identity(ID.name(), null),
                List.of(A),
                NOW.plusSeconds(60),
                Set.of(),
                () -> true);
    assertThat(count).hasValue(0);
    assertThat(report.reasons()).contains(Reason.IDENTITY_UNKNOWN);
  }

  @Test
  void missing_and_false_features_are_different_and_not_inferred_from_prose() {
    var node = business().put("description", "가상 주차 가능·유아 동반 가능");
    node.putArray("amenityFeature").addObject().put("name", "parking").put("value", false);
    var report = report(node);
    assertThat(detail(report, Field.PARKING).state()).isEqualTo(State.REPORTED);
    assertThat(detail(report, Field.PARKING).value()).isEqualTo("false");
    assertThat(detail(report, Field.CHILD_FRIENDLY).state()).isEqualTo(State.UNKNOWN);
    assertThat(report.remainingQuestions()).contains("PARKING", "CHILD_FRIENDLY");
  }

  @Test
  void reported_general_feature_only_resolves_that_general_question() {
    var node = business();
    node.putArray("amenityFeature").addObject().put("name", "주차").put("value", true);
    var report = report(node);
    assertThat(report.remainingQuestions())
        .doesNotContain("PARKING")
        .contains("ARRIVAL_RESERVATION_AVAILABLE");
    assertThat(report.bookable()).isFalse();
  }

  @Test
  void unspecified_party_requirements_do_not_create_child_or_parking_questions() {
    var report =
        new PlaceDetailEnricher(
                Clock.fixed(NOW, ZoneOffset.UTC),
                Mode.MOCK,
                (s, d) -> new Page(s.url(), html(business()), NOW, Mode.MOCK))
            .enrich(ID, List.of(A), NOW.plusSeconds(60), Set.of(), () -> true);
    assertThat(report.remainingQuestions())
        .containsExactly("ARRIVAL_WAIT_MINUTES", "ARRIVAL_RESERVATION_AVAILABLE");
  }

  @Test
  void different_reports_are_conflicting_not_last_write_wins() {
    var enricher =
        new PlaceDetailEnricher(
            Clock.fixed(NOW, ZoneOffset.UTC),
            Mode.MOCK,
            (s, d) ->
                new Page(
                    s.url(),
                    html(business().put("priceRange", s.equals(A) ? "가상가격A" : "가상가격B")),
                    NOW,
                    Mode.MOCK));
    var report = enricher.enrich(ID, List.of(A, B), NOW.plusSeconds(60), Set.of(), () -> true);
    assertThat(detail(report, Field.PRICE_RANGE).state()).isEqualTo(State.CONFLICT);
    assertThat(detail(report, Field.PRICE_RANGE).value()).isNull();
    assertThat(detail(report, Field.PRICE_RANGE).evidence()).hasSize(2);
    assertThat(report.reasons()).contains(Reason.CONFLICTING_REPORTS);
  }

  @Test
  void identical_reports_preserve_multiple_citations() {
    var report =
        new PlaceDetailEnricher(
                Clock.fixed(NOW, ZoneOffset.UTC),
                Mode.MOCK,
                (s, d) -> new Page(s.url(), html(business()), NOW, Mode.MOCK))
            .enrich(ID, List.of(A, B), NOW.plusSeconds(60), Set.of(), () -> true);
    assertThat(detail(report, Field.CUISINE).state()).isEqualTo(State.REPORTED);
    assertThat(detail(report, Field.CUISINE).evidence()).hasSize(2);
  }

  @Test
  void cancellation_after_source_completes_drops_every_value() {
    var current = new AtomicBoolean(true);
    var report =
        new PlaceDetailEnricher(
                Clock.fixed(NOW, ZoneOffset.UTC),
                Mode.MOCK,
                (s, d) -> {
                  current.set(false);
                  return new Page(s.url(), html(business()), NOW, Mode.MOCK);
                })
            .enrich(ID, List.of(A), NOW.plusSeconds(60), Set.of(), current::get);
    assertThat(report.outcome()).isEqualTo(Outcome.CANCELLED);
    assertThat(report.details()).isEmpty();
  }

  @Test
  void expired_listing_cannot_start_fetch() {
    var report =
        new PlaceDetailEnricher(
                Clock.fixed(NOW, ZoneOffset.UTC),
                Mode.MOCK,
                (s, d) -> {
                  throw new AssertionError();
                })
            .enrich(ID, List.of(A), NOW, Set.of(), () -> true);
    assertThat(report.outcome()).isEqualTo(Outcome.EXPIRED);
  }

  @Test
  void late_source_drops_result_and_does_not_continue() {
    Time clock = new Time();
    AtomicInteger calls = new AtomicInteger();
    var report =
        new PlaceDetailEnricher(
                clock,
                Mode.MOCK,
                (s, d) -> {
                  calls.incrementAndGet();
                  clock.now = NOW.plusSeconds(60);
                  return new Page(s.url(), html(business()), clock.now, Mode.MOCK);
                })
            .enrich(ID, List.of(A, B), NOW.plusSeconds(60), Set.of(), () -> true);
    assertThat(report.outcome()).isEqualTo(Outcome.EXPIRED);
    assertThat(calls).hasValue(1);
  }

  @Test
  void stale_or_future_source_is_unknown_not_refreshed() {
    for (Instant time : List.of(NOW.minusSeconds(300), NOW.plusSeconds(1))) {
      var report =
          new PlaceDetailEnricher(
                  Clock.fixed(NOW, ZoneOffset.UTC),
                  Mode.MOCK,
                  (s, d) -> new Page(s.url(), html(business()), time, Mode.MOCK))
              .enrich(ID, List.of(A), NOW.plusSeconds(60), Set.of(), () -> true);
      assertThat(report.outcome()).isEqualTo(Outcome.UNKNOWN);
      assertThat(report.reasons()).contains(Reason.STALE_SOURCE);
    }
  }

  @Test
  void original_source_modification_is_not_replaced_with_fetch_time() {
    var report = report(business().put("dateModified", "2020-01-01T00:00:00Z"));
    assertThat(detail(report, Field.CUISINE).evidence().getFirst().sourceUpdatedAt())
        .isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
    assertThat(report(business().put("dateModified", "2030-01-01T00:00:00Z")).outcome())
        .isEqualTo(Outcome.FAILED);
  }

  @Test
  void source_failure_is_not_a_normal_empty_website() {
    var report =
        new PlaceDetailEnricher(
                Clock.fixed(NOW, ZoneOffset.UTC),
                Mode.MOCK,
                (s, d) -> {
                  throw new java.io.IOException("private-parser-text");
                })
            .enrich(ID, List.of(A), NOW.plusSeconds(60), Set.of(), () -> true);
    assertThat(report.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(report.toString()).doesNotContain("private-parser-text");
  }

  @Test
  void wrong_response_url_or_mode_is_rejected() {
    for (Page page :
        List.of(
            new Page(B.url(), html(business()), NOW, Mode.MOCK),
            new Page(A.url(), html(business()), NOW, Mode.READ_ONLY_WEB))) {
      var report =
          new PlaceDetailEnricher(Clock.fixed(NOW, ZoneOffset.UTC), Mode.MOCK, (s, d) -> page)
              .enrich(ID, List.of(A), NOW.plusSeconds(60), Set.of(), () -> true);
      assertThat(report.reasons()).contains(Reason.INVALID_SOURCE);
      assertThat(report.outcome()).isEqualTo(Outcome.FAILED);
    }
  }

  @Test
  void malformed_or_duplicate_jsonld_is_rejected_without_raw_error() {
    for (String source :
        List.of(
            "{private-invalid}",
            "{\"@context\":\"https://schema.org\",\"name\":\"a\",\"name\":\"b\"}")) {
      var report =
          new PlaceDetailEnricher(
                  Clock.fixed(NOW, ZoneOffset.UTC),
                  Mode.MOCK,
                  (s, d) ->
                      new Page(
                          s.url(),
                          "<script type='application/ld+json'>" + source + "</script>",
                          NOW,
                          Mode.MOCK))
              .enrich(ID, List.of(A), NOW.plusSeconds(60), Set.of(), () -> true);
      assertThat(report.outcome()).isEqualTo(Outcome.FAILED);
      assertThat(report.toString()).doesNotContain("private-invalid");
    }
  }

  @Test
  void custom_context_cannot_redefine_restaurant_fields() {
    assertThat(
            report(business().put("@context", "https://other.example.invalid/context")).outcome())
        .isEqualTo(Outcome.UNKNOWN);
  }

  @Test
  void graph_context_and_split_postal_address_match_exact_branch() {
    var child = business();
    child.remove("@context");
    child
        .putObject("address")
        .put("addressRegion", "가상시")
        .put("addressLocality", "가상구")
        .put("streetAddress", "가상로 1");
    var graph = JSON.createObjectNode().put("@context", "https://schema.org");
    graph.putArray("@graph").add(child);
    assertThat(report(graph).outcome()).isEqualTo(Outcome.DETAILS);
    child.withObject("/address").put("addressLocality", "다른구");
    assertThat(report(graph).outcome()).isEqualTo(Outcome.UNKNOWN);
  }

  @Test
  void structured_hours_preserve_schedule_without_evaluating_arrival_open_state() {
    var node = business();
    node.remove("openingHours");
    node.putObject("openingHoursSpecification")
        .put("dayOfWeek", "https://schema.org/Monday")
        .put("opens", "22:00")
        .put("closes", "02:00")
        .put("telephone", "private-contact");
    var report = report(node);
    assertThat(detail(report, Field.OPENING_HOURS).value())
        .contains("Monday", "22:00", "02:00")
        .doesNotContain("telephone", "private-contact");
    assertThat(report.remainingQuestions()).contains("ARRIVAL_RESERVATION_AVAILABLE");
    node.withObject("/openingHoursSpecification").put("opens", "99:00");
    assertThat(detail(report(node), Field.OPENING_HOURS).state()).isEqualTo(State.UNKNOWN);
  }

  @Test
  void unsafe_links_are_unknown_without_hiding_other_valid_fields() {
    var report =
        report(
            business().put("hasMenu", "javascript:evil()").put("acceptsReservations", "not a URL"));
    assertThat(detail(report, Field.MENU_URL).state()).isEqualTo(State.UNKNOWN);
    assertThat(detail(report, Field.RESERVATION_POLICY).state()).isEqualTo(State.UNKNOWN);
    assertThat(detail(report, Field.CUISINE).state()).isEqualTo(State.REPORTED);
  }

  @Test
  void blocked_source_provenance_cannot_be_forwarded_by_real_enrichment() {
    var report =
        new PlaceDetailEnricher(
                Clock.fixed(NOW, ZoneOffset.UTC),
                Mode.READ_ONLY_WEB,
                (s, d) -> {
                  throw new AssertionError();
                })
            .enrich(
                new Identity(ID.name(), ID.address(), Origin.KAKAO),
                List.of(A),
                NOW.plusSeconds(60),
                Set.of(),
                () -> true);
    assertThat(report.reasons()).contains(Reason.DATA_USE_BLOCKED);
  }

  @Test
  void bounded_source_configuration_rejects_unsafe_urls_and_duplicate_fetches() {
    for (String uri :
        List.of(
            "http://merchant.example.invalid/",
            "https://127.0.0.1/",
            "https://localhost/",
            "https://user:pass@merchant.example.invalid/",
            "https://merchant.example.invalid/?key=private",
            "https://merchant.example.invalid/#private",
            "https://merchant.example.invalid:8443/")) {
      assertThatThrownBy(() -> new Source(URI.create(uri), "merchant.example.invalid"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var enricher =
        new PlaceDetailEnricher(
            Clock.fixed(NOW, ZoneOffset.UTC),
            Mode.MOCK,
            (s, d) -> {
              throw new AssertionError();
            });
    assertThatThrownBy(
            () -> enricher.enrich(ID, List.of(A, A), NOW.plusSeconds(60), Set.of(), () -> true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Page(A.url(), "x".repeat(524289), NOW, Mode.MOCK))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new Page(A.url(), "private-body", NOW, Mode.MOCK).toString())
        .doesNotContain("private-body");
  }
}
