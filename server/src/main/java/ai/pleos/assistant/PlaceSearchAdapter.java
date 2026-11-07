package ai.pleos.assistant;

/** Restaurant discovery only; implementations do not rank, book, or persist places. */
public interface PlaceSearchAdapter {
  PlaceSearchResult search(PlaceQuery query);
}
