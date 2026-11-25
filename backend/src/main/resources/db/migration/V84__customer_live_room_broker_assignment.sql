-- LIVE broker selection is an owner-scoped preference, not a broker grant,
-- money transfer, order authorization, or cross-mode PAPER account.
-- Composite foreign key prevents assigning any broker to a PAPER Room.
CREATE TABLE customer_live_room_broker (
    room_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    mode VARCHAR(8) NOT NULL DEFAULT 'LIVE' CHECK (mode = 'LIVE'),
    broker VARCHAR(12) NOT NULL CHECK (broker IN ('ANGEL_ONE', 'UPSTOX')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_customer_live_room_broker_owner
        FOREIGN KEY (room_id, user_id, mode)
        REFERENCES customer_trading_room (id, user_id, mode) ON DELETE CASCADE
);
CREATE INDEX ix_customer_live_room_broker_owner
    ON customer_live_room_broker (user_id, room_id);

COMMENT ON TABLE customer_live_room_broker IS
    'Explicit owner-selected LIVE broker; never grants real-money execution';
