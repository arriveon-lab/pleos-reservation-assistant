package ai.pleos.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class Model {
  private Model() {}

  public enum Mode {
    MOCK,
    LIVE
  }

  public enum NavigationSource {
    MOCK,
    MANUAL,
    PLEOS
  }

  public enum Purpose {
    INQUIRY,
    BOOKING,
    CHANGE
  }

  public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    UNKNOWN
  }

  public enum CallStatus {
    PENDING,
    ANSWERED,
    ACCEPTED,
    REJECTED,
    UNKNOWN
  }

  public enum Outcome {
    ANSWERED,
    ACCEPTED,
    REJECTED,
    UNKNOWN
  }

  public enum Certainty {
    CONFIRMED,
    ESTIMATED,
    UNKNOWN
  }

  public enum Scope {
    NOW,
    ARRIVAL,
    GENERAL
  }

  public record Destination(
      @NotBlank @Size(max = 100) String name,
      @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
      @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

  public record Navigation(
      @NotBlank @Size(max = 100) String tripId,
      @NotNull @Valid Destination destination,
      @NotNull Instant eta,
      @NotNull Instant observedAt,
      @NotNull NavigationSource source) {}

  public record Party(@Min(1) @Max(20) int adults, @Min(0) @Max(10) int children) {}

  public record SearchRequest(
      @NotNull @Valid Navigation navigation,
      @NotNull @Valid Party party,
      @Min(0) @Max(120) int maxWaitMinutes,
      @NotNull @Size(max = 2)
          Set<@Pattern(regexp = "childFriendly|parking") String> requiredFeatures,
      @NotNull @Size(max = 8) Set<@NotBlank @Size(max = 30) String> preferredCuisines,
      boolean learningConsent) {
    public SearchRequest {
      if (requiredFeatures != null)
        requiredFeatures = Collections.unmodifiableSet(new TreeSet<>(requiredFeatures));
      if (preferredCuisines != null)
        preferredCuisines = Collections.unmodifiableSet(new TreeSet<>(preferredCuisines));
    }
  }

  public record InquiryRequest(@NotBlank String candidateId) {}

  public record ChangePolicy(
      boolean enabled, Instant latestTime, @Min(0) @Max(100000) int maxAdditionalCostKrw) {}

  public record BookingRequest(
      @NotBlank String candidateId,
      @NotNull Instant requestedTime,
      @AssertTrue boolean allowBooking,
      @NotNull @Valid ChangePolicy changePolicy) {}

  public record ChangeRequest(
      @NotNull Instant requestedTime,
      @Min(0) @Max(100000) int additionalCostKrw,
      boolean approveAdditionalCost) {}

  public record InquiryFacts(
      Boolean childFriendly,
      Boolean parking,
      Boolean seatsAvailable,
      Boolean reservationAvailable,
      @Min(0) @Max(240) Integer nowWaitMinutes,
      @Min(0) @Max(240) Integer arrivalWaitMinutes,
      Instant arrivalAppliesAt) {}

  public record OutcomeRequest(
      @NotBlank @Size(max = 100) String eventId,
      @NotNull Outcome outcome,
      boolean explicitAcceptance,
      @NotNull Instant observedAt,
      Instant acceptedTime,
      @Min(0) @Max(100000) int additionalCostKrw,
      @Valid InquiryFacts facts) {}

  public record Fact(
      Object value,
      Mode mode,
      String source,
      String evidenceRef,
      Instant observedAt,
      Instant appliesAt,
      Instant validUntil,
      Certainty certainty,
      Scope scope) {}

  public static class Candidate {
    public String id;
    public String name;
    public String cuisine;
    public Map<String, Fact> facts = new LinkedHashMap<>();
    public List<String> missing = new ArrayList<>();
    public boolean eligible;
    public boolean bookable;
    public List<String> exclusionReasons = new ArrayList<>();
    public int preferenceScore;
  }

  public static class Search {
    public String id;
    public Mode mode = Mode.MOCK;
    public String region = "서울 성수동 · 가상 식당 데이터";
    public SearchRequest request;
    public Instant createdAt;
    public List<Candidate> candidates = new ArrayList<>();
  }

  public static class Reservation {
    public String id;
    public Mode mode = Mode.MOCK;
    public String searchId;
    public String candidateId;
    public String tripId;
    public ReservationStatus status = ReservationStatus.PENDING;
    public Instant originalRequestedTime;
    public Instant confirmedTime;
    public ChangePolicy changePolicy;
    public String bookingCallId;
    public String pendingChangeCallId;
    public List<String> changeCallIds = new ArrayList<>();
    public Instant lastChangeRequestedAt;
    public int totalAdditionalCostKrw;
  }

  public static class CallTask {
    public String id;
    public Mode mode = Mode.MOCK;
    public String searchId;
    public String candidateId;
    public String reservationId;
    public Purpose purpose;
    public CallStatus status = CallStatus.PENDING;
    public Instant requestedTime;
    public Instant createdAt;
    public int additionalCostKrw;
    public String resultReason;
    public String disclosure = "안녕하세요. 고객을 대신해 문의하는 AI 예약 비서입니다. AI가 생성한 음성으로 안내드립니다.";
    public String message = "모의 통화 작업입니다. 실제 발신은 수행하지 않습니다.";
  }
}
