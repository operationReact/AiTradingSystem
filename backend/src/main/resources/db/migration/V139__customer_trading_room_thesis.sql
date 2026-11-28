-- Owner-written room thesis and notes. This is planning context only; it is
-- not a signal, recommendation, risk authorization, or order instruction.
CREATE TABLE customer_trading_room_thesis (
    room_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    mode VARCHAR(8) NOT NULL CHECK (mode IN ('PAPER', 'LIVE')),
    content TEXT NOT NULL DEFAULT '',
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision >= 1),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_customer_room_thesis_content_length
        CHECK (char_length(content) <= 5000),
    CONSTRAINT fk_customer_room_thesis_owner_mode
        FOREIGN KEY (room_id, user_id, mode)
        REFERENCES customer_trading_room (id, user_id, mode) ON DELETE CASCADE
);

COMMENT ON TABLE customer_trading_room_thesis IS
    'Owner-authored room planning notes; never used as execution authority';
