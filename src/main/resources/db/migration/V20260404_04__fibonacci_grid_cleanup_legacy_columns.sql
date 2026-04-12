ALTER TABLE fibonacci_grid_strategy_settings
    ALTER COLUMN chat_id SET NOT NULL,
    ALTER COLUMN grid_levels SET NOT NULL,
    ALTER COLUMN distance_pct SET NOT NULL,
    ALTER COLUMN take_profit_pct SET NOT NULL,
    ALTER COLUMN stop_loss_pct SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;

-- legacy-колонки от старой версии Fibonacci делаем необязательными,
-- чтобы новая entity могла нормально вставляться.
ALTER TABLE fibonacci_grid_strategy_settings
    ALTER COLUMN active DROP NOT NULL;

-- если эти колонки у тебя есть в таблице, тоже убираем обязательность
-- под новую упрощённую модель.
DO $$
    BEGIN
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'fibonacci_grid_strategy_settings' AND column_name = 'symbol'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN symbol DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'fibonacci_grid_strategy_settings' AND column_name = 'timeframe'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN timeframe DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'fibonacci_grid_strategy_settings' AND column_name = 'exchange_name'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN exchange_name DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'fibonacci_grid_strategy_settings' AND column_name = 'network_type'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN network_type DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'fibonacci_grid_strategy_settings' AND column_name = 'selected_asset'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN selected_asset DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'fibonacci_grid_strategy_settings' AND column_name = 'cached_candles_limit'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN cached_candles_limit DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'fibonacci_grid_strategy_settings' AND column_name = 'leverage'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN leverage DROP NOT NULL';
        END IF;
    END $$;