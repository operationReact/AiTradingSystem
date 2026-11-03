-- V4-01: mutable emergency control state is deliberately separate from immutable model identity.
-- These controls govern model intelligence only and never grant LIVE/capital authority.
CREATE TABLE model_governance_emergency_control (
    control_type VARCHAR(32) NOT NULL
        CHECK (control_type IN ('MODEL_VERSION_DISABLED', 'MODEL_FAMILY_DISABLED', 'STATIC_FALLBACK')),
    scope_key VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL,
    reason VARCHAR(512) NOT NULL,
    state_fingerprint CHAR(64) NOT NULL
        CHECK (state_fingerprint ~ '^[a-f0-9]{64}$'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(120) NOT NULL,
    PRIMARY KEY (control_type, scope_key),
    CONSTRAINT ck_model_governance_control_scope CHECK (
        (control_type = 'STATIC_FALLBACK' AND scope_key = 'GLOBAL')
        OR (control_type <> 'STATIC_FALLBACK' AND scope_key <> 'GLOBAL')
    )
);

CREATE INDEX ix_model_governance_emergency_active
    ON model_governance_emergency_control(active, control_type, scope_key);
