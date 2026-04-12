DO $$
    DECLARE
        col RECORD;
    BEGIN
        FOR col IN
            SELECT c.column_name
            FROM information_schema.columns c
            WHERE c.table_schema = 'public'
              AND c.table_name = 'fibonacci_grid_strategy_settings'
              AND c.is_nullable = 'NO'
              AND c.column_name NOT IN (
                                        'id',
                                        'chat_id',
                                        'created_at',
                                        'updated_at',
                                        'version',
                                        'grid_levels',
                                        'distance_pct',
                                        'order_volume',
                                        'take_profit_pct',
                                        'stop_loss_pct'
                )
            LOOP
                EXECUTE format(
                        'ALTER TABLE public.fibonacci_grid_strategy_settings ALTER COLUMN %I DROP NOT NULL',
                        col.column_name
                        );
            END LOOP;
    END $$;