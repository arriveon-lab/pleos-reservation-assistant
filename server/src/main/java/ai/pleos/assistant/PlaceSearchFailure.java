package ai.pleos.assistant;

/** Internal failure kinds; public HTTP mapping is part of the later live-integration contract. */
public enum PlaceSearchFailure {
  AUTHENTICATION,
  FORBIDDEN,
  RATE_LIMITED,
  TIMEOUT,
  CANCELLED,
  UPSTREAM_UNAVAILABLE,
  INVALID_RESPONSE,
  INVALID_QUERY
}
