ALTER TABLE window_scalping_strategy_settings
    ADD COLUMN IF NOT EXISTS exchange_name VARCHAR(32),
    ADD COLUMN IF NOT EXISTS network_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS symbol VARCHAR(40),
    ADD COLUMN IF NOT EXISTS timeframe VARCHAR(16);

UPDATE window_scalping_strategy_settings ws
SET exchange_name = COALESCE(ws.exchange_name, UPPER(ss.exchange_name)),
    network_type = COALESCE(ws.network_type, UPPER(CAST(ss.network_type AS TEXT))),
    symbol = COALESCE(ws.symbol, UPPER(ss.symbol)),
    timeframe = COALESCE(ws.timeframe, LOWER(ss.timeframe))
FROM strategy_settings ss
WHERE ss.chat_id = ws.chat_id
  AND UPPER(CAST(ss.type AS TEXT)) = 'WINDOW_SCALPING';

UPDATE window_scalping_strategy_settings
SET exchange_name = COALESCE(NULLIF(UPPER(exchange_name), ''), 'BINANCE'),
    network_type = COALESCE(NULLIF(UPPER(network_type), ''), 'TESTNET'),
    symbol = COALESCE(NULLIF(UPPER(symbol), ''), 'BTCUSDT'),
    timeframe = COALESCE(NULLIF(LOWER(timeframe), ''), '1m');

ALTER TABLE window_scalping_strategy_settings
    ALTER COLUMN exchange_name SET NOT NULL,
    ALTER COLUMN network_type SET NOT NULL,
    ALTER COLUMN symbol SET NOT NULL,
    ALTER COLUMN timeframe SET NOT NULL;

DROP INDEX IF EXISTS ix_window_scalping_chat;
DROP INDEX IF EXISTS ix_window_scalping_ctx;
CREATE INDEX IF NOT EXISTS ix_window_scalping_chat ON window_scalping_strategy_settings (chat_id);
CREATE INDEX IF NOT EXISTS ix_window_scalping_ctx
    ON window_scalping_strategy_settings (chat_id, exchange_name, network_type, symbol, timeframe);

ALTER TABLE window_scalping_strategy_settings
    DROP CONSTRAINT IF EXISTS uk_window_scalping_settings_chat;

DO $$
    BEGIN
        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conname = 'uk_window_scalping_settings_context'
        ) THEN
            ALTER TABLE window_scalping_strategy_settings
                ADD CONSTRAINT uk_window_scalping_settings_context
                    UNIQUE (chat_id, exchange_name, network_type, symbol, timeframe);
        END IF;
    END $$;
