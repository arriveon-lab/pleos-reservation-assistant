package ai.pleos.assistant;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Bounded single-server issuance limit. Uses direct peer address, never forwarded client headers.
 */
@Component
public class AuthRateLimiter {
  private final Clock clock;
  private long window = -1;
  private int total;
  private final Map<String, Integer> peers = new HashMap<>();

  public AuthRateLimiter(Clock clock) {
    this.clock = clock;
  }

  public synchronized void acquire(String address) {
    long minute = clock.instant().getEpochSecond() / 60;
    if (minute != window) {
      peers.clear();
      total = 0;
      window = minute;
    }
    int count = peers.getOrDefault(address, 0);
    if (total >= 100 || count >= 10)
      throw new ApiError(
          HttpStatus.TOO_MANY_REQUESTS, "ISSUANCE_RATE_LIMITED", "토큰 발급 요청이 많습니다. 잠시 후 다시 시도하세요.");
    total++;
    peers.put(address, count + 1);
  }
}
