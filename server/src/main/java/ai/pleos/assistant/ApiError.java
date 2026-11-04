package ai.pleos.assistant;

import org.springframework.http.HttpStatus;

public class ApiError extends RuntimeException {
  public final HttpStatus status;
  public final String code;

  public ApiError(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static ApiError conflict(String code, String message) {
    return new ApiError(HttpStatus.CONFLICT, code, message);
  }

  public static ApiError bad(String code, String message) {
    return new ApiError(HttpStatus.BAD_REQUEST, code, message);
  }
}
