-- Additional English market publishers and official macro feeds. Rights remain
-- default-deny until an owner records source-specific permission and effective dates.
INSERT INTO news_source_policy (source_id, publisher, license_state, permitted_uses) VALUES
    ('business-standard-markets', 'Business Standard · Markets', 'REVIEW_PENDING', '{}'),
    ('business-standard-stock-market', 'Business Standard · Stock Market News', 'REVIEW_PENDING', '{}'),
    ('livemint-markets', 'Mint · Markets', 'REVIEW_PENDING', '{}'),
    ('livemint-companies', 'Mint · Companies', 'REVIEW_PENDING', '{}'),
    ('rbi-press-releases', 'Reserve Bank of India · Press Releases', 'REVIEW_PENDING', '{}'),
    ('rbi-notifications', 'Reserve Bank of India · Notifications', 'REVIEW_PENDING', '{}'),
    ('rbi-speeches', 'Reserve Bank of India · Speeches', 'REVIEW_PENDING', '{}')
ON CONFLICT (source_id) DO NOTHING;
