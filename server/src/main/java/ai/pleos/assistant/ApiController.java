package ai.pleos.assistant;

import static ai.pleos.assistant.Model.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final WorkflowService workflow;

  public ApiController(WorkflowService workflow) {
    this.workflow = workflow;
  }

  @GetMapping("/status")
  public Map<String, Object> status() {
    return Map.of(
        "contractVersion",
        "1.0.0",
        "mode",
        "MOCK",
        "externalCallsEnabled",
        false,
        "pleosEtaVerified",
        false,
        "dataSource",
        "FICTIONAL_FIXTURES");
  }

  @PostMapping("/searches")
  public Search search(
      @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody SearchRequest body) {
    return workflow.search(key, body);
  }

  @GetMapping("/searches/{id}")
  public Search getSearch(@PathVariable String id) {
    return workflow.getSearch(id);
  }

  @GetMapping("/searches/{id}/calls")
  public List<CallTask> getCalls(@PathVariable String id) {
    return workflow.getCalls(id);
  }

  @PostMapping("/searches/{id}/inquiries")
  public CallTask inquire(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody InquiryRequest body) {
    return workflow.inquire(id, key, body);
  }

  @PostMapping("/searches/{id}/reservations")
  public Reservation book(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody BookingRequest body) {
    return workflow.book(id, key, body);
  }

  @GetMapping("/reservations/{id}")
  public Reservation getReservation(@PathVariable String id) {
    return workflow.getReservation(id);
  }

  @PostMapping("/reservations/{id}/changes")
  public Reservation change(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ChangeRequest body) {
    return workflow.change(id, key, body);
  }

  @GetMapping("/calls/{id}")
  public CallTask getCall(@PathVariable String id) {
    return workflow.getCall(id);
  }

  @PostMapping("/mock/calls/{id}/outcomes")
  public CallTask outcome(@PathVariable String id, @Valid @RequestBody OutcomeRequest body) {
    return workflow.outcome(id, body);
  }
}
