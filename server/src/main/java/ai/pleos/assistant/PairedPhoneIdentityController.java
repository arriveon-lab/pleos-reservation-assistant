package ai.pleos.assistant;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Loopback only: the paired test gateway verifies the existing app bearer with its issuer. */
@RestController
@ConditionalOnProperty(name = "app.destination-recommendations.enabled", havingValue = "true")
class PairedPhoneIdentityController {
  @GetMapping("/api/v1/internal/paired-phone-subject")
  Map<String, String> subject(HttpServletRequest request) {
    return Map.of("subject", RequestActor.from(request).subject());
  }
}
