alter table if exists ema_crossover_strategy_settings
    add column if not exists take_profit_pct numeric(10,4);

alter table if exists ema_crossover_strategy_settings
    add column if not exists stop_loss_pct numeric(10,4);

update ema_crossover_strategy_settings
set take_profit_pct = coalesce(take_profit_pct, 1.20),
    stop_loss_pct = coalesce(stop_loss_pct, 0.80);

alter table if exists ema_crossover_strategy_settings
    alter column take_profit_pct set not null;

alter table if exists ema_crossover_strategy_settings
    alter column stop_loss_pct set not null;
