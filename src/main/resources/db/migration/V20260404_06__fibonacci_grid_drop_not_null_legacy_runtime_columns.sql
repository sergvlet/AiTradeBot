DO $$
    BEGIN
        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'fibonacci_grid_strategy_settings'
              AND column_name = 'candle_limit'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN candle_limit DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'fibonacci_grid_strategy_settings'
              AND column_name = 'cached_candles_limit'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN cached_candles_limit DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'fibonacci_grid_strategy_settings'
              AND column_name = 'timeframe'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN timeframe DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'fibonacci_grid_strategy_settings'
              AND column_name = 'symbol'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN symbol DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'fibonacci_grid_strategy_settings'
              AND column_name = 'active'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN active DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'fibonacci_grid_strategy_settings'
              AND column_name = 'base_order_volume'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN base_order_volume DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'fibonacci_grid_strategy_settings'
              AND column_name = 'volume_per_order'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN volume_per_order DROP NOT NULL';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'fibonacci_grid_strategy_settings'
              AND column_name = 'base_amount'
        ) THEN
            EXECUTE 'ALTER TABLE fibonacci_grid_strategy_settings ALTER COLUMN base_amount DROP NOT NULL';
        END IF;
    END $$;