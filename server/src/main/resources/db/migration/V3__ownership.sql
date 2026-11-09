-- Existing payloads remain intact, but NULL journey_id quarantines unverifiable ownership.
CREATE TABLE app_users (
  id VARCHAR(36) PRIMARY KEY,
  subject VARCHAR(100) NOT NULL UNIQUE
);
CREATE TABLE journeys (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL REFERENCES app_users(id),
  mode VARCHAR(16) NOT NULL CHECK (mode = 'MOCK'),
  client_trip_id VARCHAR(100) NOT NULL,
  UNIQUE (user_id, mode, client_trip_id)
);
ALTER TABLE searches ADD COLUMN journey_id VARCHAR(36) REFERENCES journeys(id);
CREATE INDEX searches_journey_idx ON searches(journey_id);
-- Old snapshots have no verified owner and must never be replayed to a new user.
ALTER TABLE commands RENAME TO legacy_commands;
CREATE TABLE commands (
  user_id VARCHAR(36) NOT NULL REFERENCES app_users(id),
  mode VARCHAR(16) NOT NULL CHECK (mode = 'MOCK'),
  scope VARCHAR(160) NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  response_json CLOB NOT NULL,
  PRIMARY KEY(user_id, mode, scope, idempotency_key)
);
