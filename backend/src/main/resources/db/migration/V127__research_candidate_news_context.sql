-- #1809 PR 2: advisory as-of news context on research candidates.
-- Additive and nullable. Historical candidates keep NULL and must be presented as "not recorded",
-- never as "no news". No backfill is performed.
ALTER TABLE research_scan_candidate
    ADD COLUMN news_context JSONB,
    ADD COLUMN news_context_contract VARCHAR(40),
    ADD CONSTRAINT ck_research_scan_candidate_news_contract
        CHECK (news_context IS NULL OR news_context_contract = 'news.context.v1');
