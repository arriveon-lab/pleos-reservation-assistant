package ai.pleos.assistant;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.http.HttpStatus;

/** Trusted server identity, never derived from request JSON or an identity header. */
public record RequestActor(String subject, boolean mockOperator) {
  public RequestActor {
    if (subject == null || !subject.matches("[A-Za-z0-9._:-]{1,100}"))
      throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "검증된 사용자 인증이 필요합니다.");
  }

  public static RequestActor from(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    return new RequestActor(
        principal == null ? null : principal.getName(), request.isUserInRole("MOCK_OPERATOR"));
  }

  public void requireMockOperator() {
    if (!mockOperator)
      throw new ApiError(HttpStatus.FORBIDDEN, "MOCK_OPERATOR_REQUIRED", "모의 결과 입력 권한이 필요합니다.");
  }
}
