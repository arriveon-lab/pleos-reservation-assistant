CREATE TABLE auth_sessions (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL REFERENCES app_users(id),
  revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE auth_tokens (
  token_hash VARCHAR(64) PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL REFERENCES auth_sessions(id),
  token_kind VARCHAR(8) NOT NULL CHECK (token_kind IN ('ACCESS','REFRESH')),
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  retry_key_hash VARCHAR(64),
  retry_until TIMESTAMP WITH TIME ZONE,
  next_access_hash VARCHAR(64),
  next_refresh_hash VARCHAR(64)
);
CREATE INDEX auth_tokens_session_idx ON auth_tokens(session_id);
CREATE INDEX auth_tokens_expiry_idx ON auth_tokens(expires_at);
