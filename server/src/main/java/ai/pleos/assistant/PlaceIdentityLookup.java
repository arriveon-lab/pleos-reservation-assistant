package ai.pleos.assistant;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Optional exact-branch lookup after independent web research. Only names go to the provider. */
interface PlaceIdentityLookup {
  record Identity(String name, String address) {}

  PlaceSearchResult lookup(
      PlaceQuery destination, List<Identity> identities, BooleanSupplier current);
}
