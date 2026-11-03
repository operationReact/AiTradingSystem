-- V4-01: Java-authoritative immutable model definition/version registry.
-- Routine lifecycle/environment controls are separate mutable governance contracts; registry identity is append-only.
CREATE TABLE model_definition (
    definition_id UUID PRIMARY KEY,
    model_key VARCHAR(64) NOT NULL UNIQUE
        CHECK (model_key ~ '^[a-z0-9][a-z0-9._-]{1,63}$'),
    display_name VARCHAR(120) NOT NULL,
    family VARCHAR(40) NOT NULL
        CHECK (family ~ '^[A-Z0-9][A-Z0-9_]{1,39}$'),
    model_role VARCHAR(20) NOT NULL
        CHECK (model_role IN ('ALPHA', 'TEMPORAL', 'REGIME', 'NLP', 'DECISION', 'ANOMALY', 'RL')),
    provider VARCHAR(120) NOT NULL,
    license_status VARCHAR(24) NOT NULL
        CHECK (license_status IN ('UNKNOWN', 'REVIEW_REQUIRED', 'APPROVED_COMMERCIAL', 'RESEARCH_ONLY', 'REJECTED')),
    description VARCHAR(512),
    definition_fingerprint CHAR(64) NOT NULL UNIQUE
        CHECK (definition_fingerprint ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(120) NOT NULL
);

CREATE TABLE model_version (
    version_id UUID PRIMARY KEY,
    definition_id UUID NOT NULL REFERENCES model_definition(definition_id),
    version_label VARCHAR(128) NOT NULL,
    artifact_fingerprint CHAR(64) NOT NULL
        CHECK (artifact_fingerprint ~ '^[a-f0-9]{64}$'),
    feature_schema_fingerprint CHAR(64) NOT NULL
        CHECK (feature_schema_fingerprint ~ '^[a-f0-9]{64}$'),
    training_dataset_fingerprint CHAR(64) NOT NULL
        CHECK (training_dataset_fingerprint ~ '^[a-f0-9]{64}$'),
    training_config_fingerprint CHAR(64) NOT NULL
        CHECK (training_config_fingerprint ~ '^[a-f0-9]{64}$'),
    code_sha VARCHAR(64) NOT NULL
        CHECK (code_sha ~ '^[a-f0-9]{40,64}$'),
    research_registry_fingerprint CHAR(64)
        CHECK (research_registry_fingerprint IS NULL OR research_registry_fingerprint ~ '^[a-f0-9]{64}$'),
    training_cutoff TIMESTAMPTZ,
    version_fingerprint CHAR(64) NOT NULL UNIQUE
        CHECK (version_fingerprint ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(120) NOT NULL,
    CONSTRAINT uq_model_version_label UNIQUE (definition_id, version_label)
);

CREATE INDEX ix_model_definition_role_family ON model_definition(model_role, family, model_key);
CREATE INDEX ix_model_version_definition_created ON model_version(definition_id, created_at DESC, version_id DESC);
