CREATE TABLE auth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    created_by VARCHAR(120) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(120) NOT NULL,
    CONSTRAINT ck_auth_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_session_revocation CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX ix_auth_session_user_active
    ON auth_session (user_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE INDEX ix_auth_session_expiry
    ON auth_session (expires_at);

COMMENT ON COLUMN auth_session.token_hash IS
    'SHA-256 hex digest of an opaque session token. Raw session credentials are never persisted.';
