ALTER TABLE research_scenario_run
    ADD COLUMN fallback_tier VARCHAR(40);

ALTER TABLE research_scenario_run
    ADD CONSTRAINT ck_research_scenario_fallback_tier CHECK (
        fallback_tier IS NULL OR fallback_tier IN (
            'EXACT_VOLATILITY_AND_VOLUME',
            'SAME_VOLATILITY',
            'ALL_ELIGIBLE'
        )
    );
