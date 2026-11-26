-- The official NSE RSS feed is a distinct source with default-deny permissions.
-- Activation requires an explicit owner-attested policy through the audited admin API.
INSERT INTO news_source_policy (source_id, publisher, license_state, permitted_uses)
VALUES ('nse-announcements', 'NSE India', 'REVIEW_PENDING', '{}')
ON CONFLICT (source_id) DO NOTHING;
