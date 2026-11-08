package ai.pleos.assistant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/anonymous-sessions")
public class AuthController {
  private final AuthService auth;
  private final AuthRateLimiter limiter;

  public AuthController(AuthService auth, AuthRateLimiter limiter) {
    this.auth = auth;
    this.limiter = limiter;
  }

  public record CreateRequest() {}

  public record RefreshRequest(
      @NotBlank @Pattern(regexp = "pr_[A-Za-z0-9_-]{43}") String refreshToken) {
    @Override
    public String toString() {
      return "RefreshRequest[REDACTED]";
    }
  }

  @PostMapping
  public AuthService.Tokens create(HttpServletRequest request, @RequestBody CreateRequest body) {
    limiter.acquire(request.getRemoteAddr());
    return auth.issue();
  }

  @PostMapping("/refresh")
  public AuthService.Tokens refresh(
      @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RefreshRequest body) {
    return auth.refresh(body.refreshToken(), key);
  }

  @DeleteMapping("/current")
  public Map<String, String> revoke(@RequestHeader("Authorization") String authorization) {
    auth.revoke(authorization.substring(7));
    return Map.of("state", "REVOKED", "mode", "MOCK");
  }
}
