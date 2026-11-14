package ai.pleos.assistant.ux;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class LiveTrialPolicyTest {
  @Test
  void onlyUnexpiredExplicitLiveRegistrationPermitsCalls() throws Exception {
    Instant now = Instant.parse("2026-11-12T00:00:00Z");
    JSONObject b =
        new JSONObject()
            .put("active", true)
            .put("phoneLast4", "0000")
            .put("expiresAt", now.plusSeconds(60).toString());
    assertFalse(LiveTrialPolicy.ready(b, now));
    b.put("mode", "MOCK");
    assertFalse(LiveTrialPolicy.ready(b, now));
    b.put("mode", "LIVE");
    assertTrue(LiveTrialPolicy.ready(b, now));
    assertFalse(LiveTrialPolicy.ready(b, now.plusSeconds(60)));
    b.put("active", false);
    assertFalse(LiveTrialPolicy.ready(b, now));
  }

  @Test
  void mockConfirmationAndUnknownNeverBecomeRealConfirmation() throws Exception {
    JSONObject r =
        new JSONObject()
            .put("status", "CONFIRMED")
            .put("confirmedTime", "19:10")
            .put("mode", "MOCK");
    assertFalse(LiveTrialPolicy.confirmed(r));
    r.put("mode", "LIVE");
    assertTrue(LiveTrialPolicy.confirmed(r));
    r.put("status", "UNKNOWN");
    assertFalse(LiveTrialPolicy.confirmed(r));
  }
}
