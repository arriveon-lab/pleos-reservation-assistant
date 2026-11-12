package ai.pleos.assistant;

import static ai.pleos.assistant.WebRecommendation.*;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Small single-server MOCK sandbox; provider output never enters a transaction or persistent cache.
 */
@Service
@ConditionalOnProperty(name = "app.web-recommendations.enabled", havingValue = "true")
public class WebRecommendationService {
  @FunctionalInterface
  public interface Gateway {
    Result search(Request request, Instant deadline, BooleanSupplier current) throws Exception;
  }

  private final WebRecommendationStore store;
  private final Clock clock;
  private final Gateway gateway;
  private final Map<String, Run> runs = new HashMap<>();
  private int inFlight;

  @Autowired
  public WebRecommendationService(WebRecommendationStore store, Clock clock) {
    this(store, clock, new MockWebRecommendationGateway(clock));
  }

  WebRecommendationService(WebRecommendationStore store, Clock clock, Gateway gateway) {
    this.store = store;
    this.clock = clock;
    this.gateway = gateway;
  }

  public Response start(RequestActor actor, String key, Request request) {
    Run run;
    synchronized (this) {
      reap();
      String view = actor.subject() + ":" + request.viewId();
      long otherViews =
          runs.values().stream()
              .filter(r -> r.owner.equals(actor.subject()) && !r.view.equals(view))
              .count();
      if (inFlight >= 4
          || (runs.size() >= 100 && runs.values().stream().noneMatch(r -> r.view.equals(view)))
          || otherViews >= 2)
        throw new ApiError(
            HttpStatus.TOO_MANY_REQUESTS, "WEB_RECOMMENDATION_LIMIT", "진행 중인 추천을 닫고 다시 요청하세요.");
      var created = store.create(actor, key, request);
      if (!created.newRequest())
        return new Response(created.saved().id(), empty(Mode.MOCK, State.ALREADY_REQUESTED));
      for (Run previous : runs.values().stream().filter(r -> r.view.equals(view)).toList()) {
        previous.active = false;
        runs.remove(previous.id);
      }
      run = new Run(created.saved().id(), actor.subject(), view, created.saved().createdAt());
      runs.put(run.id, run);
      inFlight++;
    }
    try {
      // The transaction creating user-owned metadata has committed before any search begins.
      Result result = gateway.search(request, run.createdAt.plusSeconds(30), () -> current(run));
      synchronized (this) {
        if (!current(run)) return new Response(run.id, empty(Mode.MOCK, stateAfterClose(run)));
        if (!clock.instant().isBefore(run.createdAt.plusSeconds(30)))
          return new Response(run.id, empty(Mode.MOCK, State.EXPIRED));
        if (result == null || result.mode() != Mode.MOCK)
          return new Response(run.id, empty(Mode.MOCK, State.FAILED));
        if (result.state() == State.RESULTS) {
          if (result.observedAt() == null
              || result.observedAt().isAfter(clock.instant())
              || result.validUntil() == null
              || !clock.instant().isBefore(result.validUntil()))
            return new Response(run.id, empty(Mode.MOCK, State.FAILED));
          run.availableOrdinals = result.candidates().size();
          if (result.validUntil().isBefore(run.expiresAt)) run.expiresAt = result.validUntil();
          result =
              new Result(
                  result.mode(),
                  result.state(),
                  result.observedAt(),
                  run.expiresAt,
                  result.candidates(),
                  result.excludedCandidates());
        }
        return new Response(run.id, result);
      }
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      return new Response(run.id, empty(Mode.MOCK, State.CANCELLED));
    } catch (Exception failed) {
      return new Response(run.id, empty(Mode.MOCK, State.FAILED));
    } finally {
      synchronized (this) {
        inFlight--;
      }
    }
  }

  public synchronized Saved feedback(RequestActor actor, String id, Feedback feedback) {
    Saved saved = store.get(actor, id);
    if (!saved.deleted()
        && saved.feedback() != null
        && saved.feedback().selectedOrdinal() == feedback.selectedOrdinal()) {
      // A later user opinion about their already recorded selection needs no retained web content.
      return store.feedback(actor, id, feedback);
    }
    Run run = runs.get(id);
    if (saved.deleted()
        || run == null
        || !current(run)
        || feedback.selectedOrdinal() > run.availableOrdinals)
      throw ApiError.conflict("RECOMMENDATION_NOT_CURRENT", "현재 표시 중인 추천에만 선택·피드백을 남길 수 있습니다.");
    return store.feedback(actor, id, feedback);
  }

  public Saved get(RequestActor actor, String id) {
    return store.get(actor, id);
  }

  public synchronized void close(RequestActor actor, String id) {
    store.get(actor, id);
    Run run = runs.remove(id);
    if (run != null) run.active = false;
  }

  public synchronized Saved delete(RequestActor actor, String id) {
    close(actor, id);
    return store.delete(actor, id);
  }

  private synchronized boolean current(Run run) {
    if (!run.active || runs.get(run.id) != run) return false;
    if (clock.instant().isBefore(run.createdAt) || !clock.instant().isBefore(run.expiresAt)) {
      run.active = false;
      runs.remove(run.id);
      return false;
    }
    return true;
  }

  private State stateAfterClose(Run run) {
    return !clock.instant().isBefore(run.expiresAt) ? State.EXPIRED : State.CANCELLED;
  }

  private void reap() {
    for (Run run :
        runs.values().stream().filter(r -> !clock.instant().isBefore(r.expiresAt)).toList()) {
      run.active = false;
      runs.remove(run.id);
    }
  }

  private static final class Run {
    final String id, owner, view;
    final Instant createdAt;
    Instant expiresAt;
    boolean active = true;
    int availableOrdinals;

    Run(String id, String owner, String view, Instant createdAt) {
      this.id = id;
      this.owner = owner;
      this.view = view;
      this.createdAt = createdAt;
      expiresAt = createdAt.plusSeconds(300);
    }
  }
}
