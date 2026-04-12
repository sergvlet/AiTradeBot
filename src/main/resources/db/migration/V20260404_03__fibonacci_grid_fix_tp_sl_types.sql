ALTER TABLE fibonacci_grid_strategy_settings
    ALTER COLUMN take_profit_pct TYPE NUMERIC(38,18)
        USING take_profit_pct::numeric(38,18);

ALTER TABLE fibonacci_grid_strategy_settings
    ALTER COLUMN stop_loss_pct TYPE NUMERIC(38,18)
        USING stop_loss_pct::numeric(38,18);

UPDATE fibonacci_grid_strategy_settings
SET take_profit_pct = COALESCE(take_profit_pct, 0.80),
    stop_loss_pct   = COALESCE(stop_loss_pct, 1.20);

ALTER TABLE fibonacci_grid_strategy_settings
    ALTER COLUMN take_profit_pct SET NOT NULL,
    ALTER COLUMN stop_loss_pct SET NOT NULL;