-- Store the English UI projection separately from the publisher's source-language text.
-- The source headline and excerpt remain intact for traceability; the app prefers this projection.
ALTER TABLE news_analyses
    ADD COLUMN headline_english VARCHAR(500),
    ADD COLUMN summary_english VARCHAR(2000);
