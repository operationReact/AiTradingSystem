-- D20-04A: immutable verified object-version manifest custody, no S3 resources.
-- A metadata row alone cannot assert that a real raw object exists:
-- the Java publisher requires independent physical object inspection.
-- No source grants, cloud provisioning, bucket lifecycle policies or worker.

CREATE TABLE golden_dataset_object_manifest (
    manifest_id UUID PRIMARY KEY,
    chunk_id UUID NOT NULL REFERENCES golden_dataset_backfill_chunk(chunk_id) ON DELETE RESTRICT,
    layer VARCHAR(10) NOT NULL CHECK (layer IN ('BRONZE', 'SILVER', 'GOLD')),
    parent_manifest_id UUID REFERENCES golden_dataset_object_manifest(manifest_id) ON DELETE RESTRICT,
    manifest_digest CHAR(64) NOT NULL UNIQUE CHECK (manifest_digest ~ '^[a-f0-9]{64}$'),
    object_uri VARCHAR(1024) NOT NULL,
    object_version_id VARCHAR(240) NOT NULL,
    object_sha256 CHAR(64) NOT NULL CHECK (object_sha256 ~ '^[a-f0-9]{64}$'),
    object_size_bytes BIGINT NOT NULL CHECK (object_size_bytes > 0),
    schema_version VARCHAR(120) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    source_evidence_digest CHAR(64) NOT NULL
        CHECK (source_evidence_digest ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (length(trim(object_uri)) > 0),
    CHECK (length(trim(object_version_id)) > 0),
    CHECK (length(trim(schema_version)) > 0),
    CHECK (
        (layer = 'BRONZE' AND parent_manifest_id IS NULL)
        OR (layer IN ('SILVER', 'GOLD') AND parent_manifest_id IS NOT NULL)
    ),
    CONSTRAINT uq_golden_dataset_manifest_layer UNIQUE (chunk_id, layer)
);
CREATE INDEX ix_golden_manifest_asof
    ON golden_dataset_object_manifest (chunk_id, layer, published_at);
CREATE INDEX ix_golden_manifest_parent
    ON golden_dataset_object_manifest (parent_manifest_id);

-- Never silently replace a historical source record, backtest input hash or
-- published version. Corrections use a new dataset job revision and manifest.
-- A legally required purge needs a separate reviewed retention/removal workflow.
CREATE FUNCTION golden_reject_manifest_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Golden Source manifests are immutable; publish a new revision'
        USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER tr_golden_manifest_no_update_delete
BEFORE UPDATE OR DELETE ON golden_dataset_object_manifest
FOR EACH ROW EXECUTE FUNCTION golden_reject_manifest_mutation();

-- Intentionally no INSERTs, AWS S3 buckets, IAM permissions, KMS keys,
-- automated compaction, source fetches, display endpoints or model grants.
