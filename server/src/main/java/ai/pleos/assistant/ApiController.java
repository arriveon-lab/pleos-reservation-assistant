package ai.pleos.assistant;

import static ai.pleos.assistant.Model.*;

import jakarta.servlet.http.HttpServletRequest;
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
        "1.1.0",
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
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody SearchRequest body) {
    return workflow.search(RequestActor.from(request), key, body);
  }

  @GetMapping("/searches/{id}")
  public Search getSearch(HttpServletRequest request, @PathVariable String id) {
    return workflow.getSearch(RequestActor.from(request), id);
  }

  @PostMapping("/searches/reconcile")
  public WorkflowService.SearchResolution reconcileSearch(
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody SearchRequest body) {
    return workflow.reconcileSearch(RequestActor.from(request), key, body);
  }

  @GetMapping("/searches/{id}/calls")
  public List<CallTask> getCalls(HttpServletRequest request, @PathVariable String id) {
    return workflow.getCalls(RequestActor.from(request), id);
  }

  @PostMapping("/searches/{id}/inquiries")
  public CallTask inquire(
      HttpServletRequest request,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody InquiryRequest body) {
    return workflow.inquire(RequestActor.from(request), id, key, body);
  }

  @PostMapping("/searches/{id}/reservations")
  public Reservation book(
      HttpServletRequest request,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody BookingRequest body) {
    return workflow.book(RequestActor.from(request), id, key, body);
  }

  @GetMapping("/reservations/{id}")
  public Reservation getReservation(HttpServletRequest request, @PathVariable String id) {
    return workflow.getReservation(RequestActor.from(request), id);
  }

  @PostMapping("/reservations/{id}/changes")
  public Reservation change(
      HttpServletRequest request,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ChangeRequest body) {
    return workflow.change(RequestActor.from(request), id, key, body);
  }

  @GetMapping("/calls/{id}")
  public CallTask getCall(HttpServletRequest request, @PathVariable String id) {
    return workflow.getCall(RequestActor.from(request), id);
  }

  @PostMapping("/mock/calls/{id}/outcomes")
  public CallTask outcome(
      HttpServletRequest request,
      @PathVariable String id,
      @Valid @RequestBody OutcomeRequest body) {
    return workflow.outcome(RequestActor.from(request), id, body);
  }
}
