package ai.pleos.assistant;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiError.class)
  public ResponseEntity<Map<String, String>> domain(ApiError error) {
    return ResponseEntity.status(error.status)
        .body(Map.of("code", error.code, "message", error.getMessage(), "mode", "MOCK"));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class
  })
  public ResponseEntity<Map<String, String>> invalid(Exception error) {
    // Never echo request bodies or private user input in validation errors.
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "code",
                "INVALID_REQUEST",
                "message",
                "요청 필드와 Idempotency-Key를 확인하세요.",
                "mode",
                "MOCK"));
  }
}
