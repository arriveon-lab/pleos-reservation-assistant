package ai.pleos.assistant;

import static ai.pleos.assistant.Model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Keeps fictional condition facts out of generic place data and out of the ranking engine. */
@Component
public class MockCandidateFactory {
  public List<Candidate> create(PlaceSearchResult.Success result) {
    List<Candidate> candidates = new ArrayList<>();
    for (PlaceSummary place : result.places()) {
      if (place.provider() != PlaceSummary.Provider.MOCK) {
        throw new IllegalArgumentException("Only fictional places may enter the MOCK workflow");
      }
      Candidate candidate = new Candidate();
      candidate.id = place.providerPlaceId();
      candidate.name = place.name();
      candidate.cuisine = Objects.requireNonNullElse(place.cuisine(), "");
      switch (candidate.id) {
        case "mock-1" -> put(candidate, "childFriendly", result);
        case "mock-2" -> {
          put(candidate, "childFriendly", result);
          put(candidate, "parking", result);
        }
        case "mock-3" -> put(candidate, "parking", result);
        default -> {
          /* Additional fictional listings carry no invented condition facts. */
        }
      }
      candidates.add(candidate);
    }
    return candidates;
  }

  private void put(Candidate candidate, String key, PlaceSearchResult.Success result) {
    candidate.facts.put(
        key,
        new Fact(
            true,
            Mode.MOCK,
            "MOCK_FIXTURE",
            candidate.id,
            result.fetchedAt(),
            result.fetchedAt(),
            result.fetchedAt().plusSeconds(300),
            Certainty.CONFIRMED,
            Scope.GENERAL));
  }
}
