package ai.pleos.assistant;

import java.net.URI;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Transient website reports. Never turns website claims into workflow facts or authority. */
public final class PlaceDetailEnricher {
  public enum Mode {
    MOCK,
    READ_ONLY_WEB
  }

  public enum Origin {
    MOCK,
    DIRECT_MERCHANT,
    APPROVED_PUBLIC,
    KAKAO,
    UNVERIFIED
  }

  public enum Field {
    OPENING_HOURS,
    MENU_URL,
    PRICE_RANGE,
    CUISINE,
    RESERVATION_POLICY,
    PARKING,
    CHILD_FRIENDLY
  }

  public enum State {
    REPORTED,
    UNKNOWN,
    CONFLICT
  }

  public enum SourceType {
    WEB_JSON_LD
  }

  public enum Outcome {
    DETAILS,
    UNKNOWN,
    CANCELLED,
    EXPIRED,
    FAILED
  }

  public enum Reason {
    SOURCE_MATCHED,
    IDENTITY_UNKNOWN,
    IDENTITY_MISMATCH,
    SOURCE_UNAVAILABLE,
    INVALID_SOURCE,
    STALE_SOURCE,
    CONFLICTING_REPORTS,
    DATA_USE_BLOCKED
  }

  public record Identity(String name, String address, Origin origin) {
    public Identity {
      if (origin == null
          || name == null
          || name.isBlank()
          || name.length() > 150
          || (address != null && address.length() > 250))
        throw new IllegalArgumentException("Invalid detail identity");
      name = name.strip();
      address = address == null || address.isBlank() ? null : address.strip();
    }

    public Identity(String name, String address) {
      this(name, address, Origin.UNVERIFIED);
    }
  }

  /** Operator-supplied source approval is separate from identity matching or a search ranking. */
  public record Source(URI url, String approvedHost) {
    public Source {
      if (!safeUrl(url) || approvedHost == null || !url.getHost().equalsIgnoreCase(approvedHost))
        throw new IllegalArgumentException("Source must match its approved HTTPS host");
      approvedHost = approvedHost.toLowerCase(java.util.Locale.ROOT);
    }
  }

  public record Page(URI url, String html, Instant observedAt, Mode mode) {
    public Page {
      if (!safeUrl(url)
          || html == null
          || html.length() > 524288
          || observedAt == null
          || mode == null) throw new IllegalArgumentException("Invalid detail page");
    }

    @Override
    public String toString() {
      return "Page[body omitted]";
    }
  }

  @FunctionalInterface
  public interface PageAdapter {
    Page fetch(Source source, Instant deadline) throws Exception;
  }

  public record Evidence(
      URI url,
      SourceType sourceType,
      Instant observedAt,
      Instant sourceUpdatedAt,
      Instant validUntil,
      String certainty,
      Model.Scope scope,
      Instant appliesAt) {}

  public record Detail(Field field, State state, String value, List<Evidence> evidence) {
    public Detail {
      evidence = List.copyOf(evidence);
    }
  }

  public record Report(
      Mode mode,
      Outcome outcome,
      Instant validUntil,
      List<Detail> details,
      List<Reason> reasons,
      List<String> remainingQuestions,
      boolean bookable,
      boolean inquiryDispatchAllowed) {
    public Report {
      details = List.copyOf(details);
      reasons = List.copyOf(reasons);
      remainingQuestions = List.copyOf(remainingQuestions);
      if (bookable || inquiryDispatchAllowed)
        throw new IllegalArgumentException("Website reports cannot authorize actions");
    }
  }

  record Parsed(
      boolean matched,
      boolean invalid,
      Instant updatedAt,
      java.util.Map<Field, List<String>> values) {}

  private final Clock clock;
  private final Mode mode;
  private final PageAdapter adapter;
  private final JsonLdPlaceDetailParser parser = new JsonLdPlaceDetailParser();
  private static final Duration DISPLAY_AGE = Duration.ofMinutes(5);

  public PlaceDetailEnricher(Clock clock, Mode mode, PageAdapter adapter) {
    this.clock = Objects.requireNonNull(clock);
    this.mode = Objects.requireNonNull(mode);
    this.adapter = Objects.requireNonNull(adapter);
  }

  public Report enrich(
      Identity identity,
      List<Source> sources,
      Instant listingValidUntil,
      java.util.Set<Field> requiredFeatures,
      BooleanSupplier stillCurrent) {
    Objects.requireNonNull(identity);
    Objects.requireNonNull(sources);
    Objects.requireNonNull(listingValidUntil);
    Objects.requireNonNull(stillCurrent);
    Objects.requireNonNull(requiredFeatures);
    if (!java.util.Set.of(Field.PARKING, Field.CHILD_FRIENDLY).containsAll(requiredFeatures))
      throw new IllegalArgumentException("Unsupported required detail feature");
    if (sources.size() > 3
        || sources.stream().anyMatch(Objects::isNull)
        || sources.stream().map(Source::url).distinct().count() != sources.size())
      throw new IllegalArgumentException("At most three distinct approved sources");
    if (!stillCurrent.getAsBoolean()) return empty(Outcome.CANCELLED, List.of());
    if (!clock.instant().isBefore(listingValidUntil)) return empty(Outcome.EXPIRED, List.of());
    if (identity.address() == null)
      return unknown(listingValidUntil, Reason.IDENTITY_UNKNOWN, requiredFeatures);
    if (mode == Mode.READ_ONLY_WEB
        && identity.origin() != Origin.DIRECT_MERCHANT
        && identity.origin() != Origin.APPROVED_PUBLIC)
      return unknown(listingValidUntil, Reason.DATA_USE_BLOCKED, requiredFeatures);
    var claims = new EnumMap<Field, List<Detail>>(Field.class);
    var reasons = new ArrayList<Reason>();
    Instant expiry = listingValidUntil;
    for (Source source : sources) {
      if (!stillCurrent.getAsBoolean()) return empty(Outcome.CANCELLED, List.of());
      if (!clock.instant().isBefore(expiry)) return empty(Outcome.EXPIRED, List.of());
      try {
        Page page = adapter.fetch(source, expiry);
        if (!stillCurrent.getAsBoolean()) return empty(Outcome.CANCELLED, List.of());
        Instant now = clock.instant();
        if (!now.isBefore(expiry)) return empty(Outcome.EXPIRED, List.of());
        if (!source.url().equals(page.url()) || page.mode() != mode) {
          reasons.add(Reason.INVALID_SOURCE);
          continue;
        }
        if (page.observedAt().isAfter(now) || !page.observedAt().isAfter(now.minus(DISPLAY_AGE))) {
          reasons.add(Reason.STALE_SOURCE);
          continue;
        }
        Parsed parsed = parser.parse(identity, page);
        if (parsed.invalid()) {
          reasons.add(Reason.INVALID_SOURCE);
          continue;
        }
        if (!parsed.matched()) {
          reasons.add(Reason.IDENTITY_MISMATCH);
          continue;
        }
        if (parsed.updatedAt() != null && parsed.updatedAt().isAfter(page.observedAt())) {
          reasons.add(Reason.INVALID_SOURCE);
          continue;
        }
        Instant pageExpiry = page.observedAt().plus(DISPLAY_AGE);
        if (pageExpiry.isBefore(expiry)) expiry = pageExpiry;
        reasons.add(Reason.SOURCE_MATCHED);
        var evidence =
            new Evidence(
                page.url(),
                SourceType.WEB_JSON_LD,
                page.observedAt(),
                parsed.updatedAt(),
                pageExpiry.isBefore(listingValidUntil) ? pageExpiry : listingValidUntil,
                "REPORTED_WEB_NOT_MERCHANT_CONFIRMATION",
                Model.Scope.GENERAL,
                null);
        for (var entry : parsed.values().entrySet()) {
          for (String value : entry.getValue())
            claims
                .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                .add(new Detail(entry.getKey(), State.REPORTED, value, List.of(evidence)));
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return empty(Outcome.CANCELLED, List.of());
      } catch (Exception failure) {
        reasons.add(Reason.SOURCE_UNAVAILABLE);
      }
    }
    if (!stillCurrent.getAsBoolean()) return empty(Outcome.CANCELLED, List.of());
    if (!clock.instant().isBefore(expiry)) return empty(Outcome.EXPIRED, List.of());
    var details = new ArrayList<Detail>();
    boolean reported = false;
    for (Field field : Field.values()) {
      List<Detail> values = claims.getOrDefault(field, List.of());
      var distinct = values.stream().map(Detail::value).distinct().toList();
      State state =
          distinct.isEmpty()
              ? State.UNKNOWN
              : distinct.size() == 1 ? State.REPORTED : State.CONFLICT;
      if (state == State.CONFLICT) reasons.add(Reason.CONFLICTING_REPORTS);
      reported |= state == State.REPORTED;
      details.add(
          new Detail(
              field,
              state,
              state == State.REPORTED ? distinct.getFirst() : null,
              values.stream().flatMap(d -> d.evidence().stream()).distinct().toList()));
    }
    boolean allSourcesFailed =
        !sources.isEmpty()
            && !reasons.isEmpty()
            && reasons.stream()
                .allMatch(r -> r == Reason.SOURCE_UNAVAILABLE || r == Reason.INVALID_SOURCE);
    return new Report(
        mode,
        reported ? Outcome.DETAILS : allSourcesFailed ? Outcome.FAILED : Outcome.UNKNOWN,
        expiry,
        details,
        reasons.stream().distinct().toList(),
        questions(details, requiredFeatures),
        false,
        false);
  }

  private Report empty(Outcome outcome, List<Reason> reasons) {
    return new Report(mode, outcome, null, List.of(), reasons, List.of(), false, false);
  }

  private Report unknown(Instant expiry, Reason reason, java.util.Set<Field> required) {
    var details =
        java.util.Arrays.stream(Field.values())
            .map(f -> new Detail(f, State.UNKNOWN, null, List.of()))
            .toList();
    return new Report(
        mode,
        Outcome.UNKNOWN,
        expiry,
        details,
        List.of(reason),
        questions(details, required),
        false,
        false);
  }

  private static List<String> questions(
      List<Detail> details, java.util.Set<Field> requiredFeatures) {
    var questions = new ArrayList<String>();
    for (Detail detail : details) {
      if (requiredFeatures.contains(detail.field())
          && (detail.state() != State.REPORTED || !"true".equals(detail.value())))
        questions.add(detail.field().name());
    }
    // General website reservation policy never answers an ETA-specific availability question.
    questions.add("ARRIVAL_WAIT_MINUTES");
    questions.add("ARRIVAL_RESERVATION_AVAILABLE");
    return questions;
  }

  static String canonical(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replaceAll("\\s+", "")
        .toLowerCase(java.util.Locale.ROOT);
  }

  static boolean safeUrl(URI uri) {
    return uri != null
        && uri.toASCIIString().length() <= 2048
        && "https".equalsIgnoreCase(uri.getScheme())
        && uri.getHost() != null
        && uri.getUserInfo() == null
        && uri.getRawQuery() == null
        && uri.getRawFragment() == null
        && (uri.getPort() == -1 || uri.getPort() == 443)
        && !uri.getHost().contains(":")
        && !uri.getHost().matches("[0-9.]+")
        && !uri.getHost().equalsIgnoreCase("localhost")
        && uri.getHost().contains(".");
  }
}
