-- Add exact ORB scanner-input custody without changing the legacy MA_RECLAIM contract.
ALTER TABLE mind_research_input_custody
    DROP CONSTRAINT IF EXISTS mind_research_input_custody_strategy_code_check;

ALTER TABLE mind_research_input_custody
    DROP CONSTRAINT IF EXISTS mind_research_input_custody_candle_count_check;

ALTER TABLE mind_research_input_custody
    ADD CONSTRAINT mind_research_input_custody_strategy_count_check
    CHECK (
        (strategy_code = 'MA_RECLAIM_V1' AND candle_count = 21)
        OR
        (strategy_code = 'OPENING_RANGE_BREAKOUT_V1' AND candle_count = 64)
    );

COMMENT ON CONSTRAINT mind_research_input_custody_strategy_count_check
    ON mind_research_input_custody IS
    'MA_RECLAIM freezes 21 five-minute inputs; ORB freezes the exact 64-candle scanner input. Both are research-only custody.';
