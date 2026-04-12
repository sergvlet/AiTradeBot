ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS exchange_name VARCHAR(32);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS network_type VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_orders_ctx_runtime
    ON orders (chat_id, strategy_type, symbol, exchange_name, network_type, "timestamp");