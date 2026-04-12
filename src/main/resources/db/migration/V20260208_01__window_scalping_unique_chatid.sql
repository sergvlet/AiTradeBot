-- 1) Удаляем дубли, оставляя последнюю запись по chat_id (самый большой id)
DELETE FROM window_scalping_strategy_settings
WHERE id NOT IN (
    SELECT MAX(id)
    FROM window_scalping_strategy_settings
    GROUP BY chat_id
);

-- 2) Добавляем UNIQUE(chat_id), если ещё нет
DO $$
    BEGIN
        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conname = 'uk_window_scalping_settings_chat'
        ) THEN
            ALTER TABLE window_scalping_strategy_settings
                ADD CONSTRAINT uk_window_scalping_settings_chat UNIQUE (chat_id);
        END IF;
    END $$;
