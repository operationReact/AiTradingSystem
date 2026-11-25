-- News ingestion and AI analysis. Analyses are advisory: they never write to orders or risk decisions.

CREATE TABLE news_articles (
    id              UUID PRIMARY KEY,
    url_hash        CHAR(64)     NOT NULL,
    source          VARCHAR(80)  NOT NULL,
    external_id     VARCHAR(200),
    title           VARCHAR(500) NOT NULL,
    url             VARCHAR(2000) NOT NULL,
    excerpt         VARCHAR(2000),
    published_at    TIMESTAMPTZ  NOT NULL,
    ingested_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_news_articles_url_hash UNIQUE (url_hash)
);

CREATE INDEX ix_news_articles_published ON news_articles (published_at DESC);

CREATE TABLE news_analyses (
    id                UUID PRIMARY KEY,
    article_id        UUID         NOT NULL REFERENCES news_articles (id),
    provider          VARCHAR(20)  NOT NULL,
    model             VARCHAR(80)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    sentiment         VARCHAR(20),
    category          VARCHAR(40),
    confidence        NUMERIC(4,3),
    impacted_symbols  VARCHAR(500),
    evidence          VARCHAR(600),
    risks             VARCHAR(1000),
    error_code        VARCHAR(60),
    latency_ms        INTEGER      NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_news_analyses_status CHECK (status IN ('ANALYZED', 'FAILED')),
    CONSTRAINT ck_news_analyses_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT ck_news_analyses_latency CHECK (latency_ms >= 0)
);

CREATE INDEX ix_news_analyses_article_created ON news_analyses (article_id, created_at DESC);
CREATE INDEX ix_news_analyses_created ON news_analyses (created_at DESC);
