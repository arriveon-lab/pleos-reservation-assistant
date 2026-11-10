package ai.pleos.assistant;

/** Basic listing data. Missing cuisine/contact information is null, never an availability fact. */
public record PlaceSummary(
    Provider provider,
    String providerPlaceId,
    String name,
    String cuisine,
    double latitude,
    double longitude,
    String address,
    String phone,
    String placeUrl,
    Integer distanceMeters) {
  public enum Provider {
    MOCK,
    KAKAO,
    SEOUL_OPEN_DATA
  }

  public PlaceSummary {
    if (provider == null
        || providerPlaceId == null
        || providerPlaceId.isBlank()
        || name == null
        || name.isBlank()
        || !Double.isFinite(latitude)
        || latitude < -90
        || latitude > 90
        || !Double.isFinite(longitude)
        || longitude < -180
        || longitude > 180
        || (distanceMeters != null && distanceMeters < 0)) {
      throw new IllegalArgumentException("Invalid place listing");
    }
    providerPlaceId = providerPlaceId.strip();
    name = name.strip();
    cuisine = optional(cuisine);
    address = optional(address);
    phone = optional(phone);
    placeUrl = optional(placeUrl);
  }

  private static String optional(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
