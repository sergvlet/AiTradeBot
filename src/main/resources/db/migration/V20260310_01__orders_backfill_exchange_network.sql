ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS exchange_name VARCHAR(32);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS network_type VARCHAR(32);

UPDATE orders
SET exchange_name = 'BINANCE'
WHERE exchange_name IS NULL OR TRIM(exchange_name) = '';

UPDATE orders
SET network_type = 'MAINNET'
WHERE network_type IS NULL OR TRIM(network_type) = '';

CREATE INDEX IF NOT EXISTS idx_orders_ctx_runtime
    ON orders (chat_id, strategy_type, symbol, exchange_name, network_type, "timestamp");