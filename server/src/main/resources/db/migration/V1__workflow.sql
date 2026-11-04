CREATE TABLE command_mutex (id INTEGER PRIMARY KEY);
INSERT INTO command_mutex (id) VALUES (1);
CREATE TABLE searches (id VARCHAR(36) PRIMARY KEY, payload CLOB NOT NULL);
CREATE TABLE reservations (
  id VARCHAR(36) PRIMARY KEY,
  search_id VARCHAR(36) NOT NULL REFERENCES searches(id),
  payload CLOB NOT NULL
);
CREATE TABLE calls (
  id VARCHAR(36) PRIMARY KEY,
  search_id VARCHAR(36) NOT NULL REFERENCES searches(id),
  payload CLOB NOT NULL
);
CREATE TABLE commands (
  scope VARCHAR(160) NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  response_json CLOB NOT NULL,
  PRIMARY KEY(scope, idempotency_key)
);
CREATE TABLE events (
  event_id VARCHAR(100) PRIMARY KEY,
  request_hash VARCHAR(64) NOT NULL,
  call_id VARCHAR(36) NOT NULL REFERENCES calls(id),
  payload CLOB NOT NULL
);
