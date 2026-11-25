CREATE TABLE user_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    csrf_token_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_agent_hash CHAR(64),
    ip_hash CHAR(64),
    CONSTRAINT ck_user_session_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_user_session_user_active
    ON user_session(user_id, expires_at DESC)
    WHERE revoked_at IS NULL;

CREATE INDEX ix_user_session_expiry
    ON user_session(expires_at)
    WHERE revoked_at IS NULL;
