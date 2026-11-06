-- Provider-neutral identity directory. Application data always belongs to app_user.id.
-- Authentication credentials and token material are never stored here.
CREATE TABLE app_auth_identity (
    provider VARCHAR(40) NOT NULL,
    issuer VARCHAR(255) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    last_verified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, issuer, subject),
    CONSTRAINT ck_auth_identity_provider CHECK (provider ~ '^[a-z][a-z0-9_-]{1,39}$')
);

-- Disallow assigning two accounts from the same issuer to one application user
-- accidentally; additional verified providers can still be linked later.
CREATE UNIQUE INDEX ux_app_auth_identity_provider_user
    ON app_auth_identity(provider, issuer, user_id);

CREATE INDEX ix_app_auth_identity_user
    ON app_auth_identity(user_id);
