-- Исправление устаревшего CHECK constraint, который блокирует новые значения StrategyType
DO $$
    BEGIN
        IF EXISTS (
            SELECT 1
            FROM information_schema.table_constraints
            WHERE table_schema = 'public'
              AND table_name = 'trade_intent_event'
              AND constraint_name = 'trade_intent_event_strategy_type_check'
        ) THEN
            ALTER TABLE public.trade_intent_event
                DROP CONSTRAINT trade_intent_event_strategy_type_check;
        END IF;
    END $$;

ALTER TABLE public.trade_intent_event
    ALTER COLUMN strategy_type TYPE varchar(64);
