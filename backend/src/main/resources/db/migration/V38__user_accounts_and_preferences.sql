CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    external_subject VARCHAR(200) NOT NULL UNIQUE,
    display_name VARCHAR(120),
    email VARCHAR(320),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(120) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(120) NOT NULL,
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE UNIQUE INDEX ux_app_user_email_normalized
    ON app_user (lower(email))
    WHERE email IS NOT NULL;

CREATE TABLE user_preference (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    version BIGINT NOT NULL DEFAULT 0,
    experience_mode VARCHAR(20) NOT NULL DEFAULT 'NEON_AI',
    theme_mode VARCHAR(20) NOT NULL DEFAULT 'SYSTEM',
    density_mode VARCHAR(20) NOT NULL DEFAULT 'AUTO',
    spacing_scale NUMERIC(3,2) NOT NULL DEFAULT 1.00,
    motion_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    haptics_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    prompt_mobile_downloads BOOLEAN NOT NULL DEFAULT TRUE,
    default_route VARCHAR(40) NOT NULL DEFAULT 'FUTURE',
    default_interval VARCHAR(20) NOT NULL DEFAULT '5m',
    default_candle_count INTEGER NOT NULL DEFAULT 500,
    diagnostics_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    stale_refresh_attempts INTEGER NOT NULL DEFAULT 1,
    stale_threshold_seconds INTEGER NOT NULL DEFAULT 5,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(120) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(120) NOT NULL,
    CONSTRAINT ck_user_preference_experience CHECK (experience_mode IN ('NEON_AI', 'CLEAN')),
    CONSTRAINT ck_user_preference_theme CHECK (theme_mode IN ('SYSTEM', 'LIGHT', 'DARK')),
    CONSTRAINT ck_user_preference_density CHECK (density_mode IN ('AUTO', 'COMFORTABLE', 'COMPACT')),
    CONSTRAINT ck_user_preference_spacing CHECK (spacing_scale BETWEEN 0.75 AND 1.50),
    CONSTRAINT ck_user_preference_candles CHECK (default_candle_count BETWEEN 1 AND 5000),
    CONSTRAINT ck_user_preference_stale_attempts CHECK (stale_refresh_attempts BETWEEN 0 AND 10),
    CONSTRAINT ck_user_preference_stale_threshold CHECK (stale_threshold_seconds BETWEEN 1 AND 300)
);

CREATE TABLE user_notification_preference (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    version BIGINT NOT NULL DEFAULT 0,
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    push_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    weekly_digest_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    security_alerts_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    aggregate_operator_alerts_by_root_cause BOOLEAN NOT NULL DEFAULT TRUE,
    operator_coverage_alert_threshold NUMERIC(5,2) NOT NULL DEFAULT 80.00,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(120) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(120) NOT NULL,
    CONSTRAINT ck_notification_security_alerts_required CHECK (security_alerts_enabled),
    CONSTRAINT ck_notification_coverage_threshold CHECK (operator_coverage_alert_threshold BETWEEN 0 AND 100)
);

CREATE INDEX ix_app_user_status ON app_user(status);
CREATE INDEX ix_user_preference_updated_at ON user_preference(updated_at DESC);
CREATE INDEX ix_user_notification_preference_updated_at ON user_notification_preference(updated_at DESC);
