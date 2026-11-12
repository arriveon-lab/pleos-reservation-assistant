-- User-owned input and direct feedback only. No provider report, merchant, URL, review or AI verdict.
CREATE TABLE web_recommendation_requests (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL REFERENCES app_users(id),
  mode VARCHAR(8) NOT NULL CHECK (mode = 'MOCK'),
  idempotency_key VARCHAR(100) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  view_id VARCHAR(36) NOT NULL,
  generation BIGINT NOT NULL CHECK (generation > 0),
  request_json CHARACTER LARGE OBJECT,
  feedback_json CHARACTER LARGE OBJECT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (user_id, mode, idempotency_key),
  UNIQUE (user_id, mode, view_id, generation)
);
CREATE INDEX web_recommendation_view_idx ON web_recommendation_requests(user_id, view_id, generation);
