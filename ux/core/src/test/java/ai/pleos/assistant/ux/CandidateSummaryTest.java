package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class CandidateSummaryTest {
  private final Instant now = Instant.parse("2026-11-09T00:00:00Z");

  private JSONObject fact(Object value, String scope) {
    return new JSONObject()
        .put("appliesAt", now.plusSeconds(3600).toString())
        .put("value", value)
        .put("scope", scope)
        .put("certainty", "CONFIRMED")
        .put("validUntil", now.plusSeconds(60).toString());
  }

  @Test
  void eligibleWithMissingFactsIsReviewOnly() {
    assertEquals(
        CandidateSummary.State.REVIEW_REQUIRED,
        CandidateSummary.state(new JSONObject().put("eligible", true).put("bookable", false)));
    assertEquals(
        CandidateSummary.State.REQUEST_AVAILABLE,
        CandidateSummary.state(new JSONObject().put("eligible", true).put("bookable", true)));
    assertEquals(
        CandidateSummary.State.EXCLUDED,
        CandidateSummary.state(new JSONObject().put("eligible", false).put("bookable", true)));
  }

  @Test
  void currentWaitNeverBecomesArrivalWait() {
    JSONObject facts = new JSONObject().put("nowWaitMinutes", fact(5, "NOW"));
    assertEquals(
        "UNKNOWN", CandidateSummary.fact(facts, "arrivalWaitMinutes", now, now.plusSeconds(3600)));
    facts.put("arrivalWaitMinutes", fact(5, "NOW"));
    assertEquals(
        "UNKNOWN", CandidateSummary.fact(facts, "arrivalWaitMinutes", now, now.plusSeconds(3600)));
    facts.put("arrivalWaitMinutes", fact(15, "ARRIVAL"));
    assertEquals(
        "15", CandidateSummary.fact(facts, "arrivalWaitMinutes", now, now.plusSeconds(3600)));
  }

  @Test
  void unknownExpiredAndFalseRemainDistinct() {
    JSONObject f = fact(false, "GENERAL");
    JSONObject facts = new JSONObject().put("parking", f);
    assertEquals("false", CandidateSummary.fact(facts, "parking", now, now.plusSeconds(3600)));
    f.put("certainty", "UNKNOWN");
    assertEquals("UNKNOWN", CandidateSummary.fact(facts, "parking", now, now.plusSeconds(3600)));
    f.put("certainty", "CONFIRMED").put("validUntil", now.toString());
    assertEquals("EXPIRED", CandidateSummary.fact(facts, "parking", now, now.plusSeconds(3600)));
    f.put("validUntil", "invalid");
    assertEquals("UNKNOWN", CandidateSummary.fact(facts, "parking", now, now.plusSeconds(3600)));
  }

  @Test
  void estimatesAndMismatchedArrivalStayExplicit() {
    JSONObject f = fact(20, "ARRIVAL").put("certainty", "ESTIMATED");
    JSONObject facts = new JSONObject().put("arrivalWaitMinutes", f);
    assertEquals(
        "ESTIMATED:20",
        CandidateSummary.fact(facts, "arrivalWaitMinutes", now, now.plusSeconds(3600)));
    assertEquals(
        "UNKNOWN", CandidateSummary.fact(facts, "arrivalWaitMinutes", now, now.plusSeconds(7200)));
    f.put("value", -1);
    assertEquals(
        "UNKNOWN", CandidateSummary.fact(facts, "arrivalWaitMinutes", now, now.plusSeconds(3600)));
  }
}
