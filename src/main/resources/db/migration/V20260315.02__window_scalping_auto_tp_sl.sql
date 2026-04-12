ALTER TABLE window_scalping_strategy_settings
    ADD COLUMN IF NOT EXISTS auto_tp_sl_enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS auto_sl_from_range_factor numeric(19,8) NOT NULL DEFAULT 1.80000000,
    ADD COLUMN IF NOT EXISTS auto_tp_from_range_factor numeric(19,8) NOT NULL DEFAULT 5.50000000,
    ADD COLUMN IF NOT EXISTS auto_min_risk_reward numeric(19,8) NOT NULL DEFAULT 2.40000000,
    ADD COLUMN IF NOT EXISTS auto_sl_min_pct numeric(19,8) NOT NULL DEFAULT 0.04000000,
    ADD COLUMN IF NOT EXISTS auto_sl_max_pct numeric(19,8) NOT NULL DEFAULT 0.18000000,
    ADD COLUMN IF NOT EXISTS auto_tp_min_pct numeric(19,8) NOT NULL DEFAULT 0.10000000,
    ADD COLUMN IF NOT EXISTS auto_tp_max_pct numeric(19,8) NOT NULL DEFAULT 0.80000000,
    ADD COLUMN IF NOT EXISTS auto_tp_ml_boost_factor numeric(19,8) NOT NULL DEFAULT 1.15000000,
    ADD COLUMN IF NOT EXISTS auto_tp_weak_signal_factor numeric(19,8) NOT NULL DEFAULT 0.90000000;

UPDATE window_scalping_strategy_settings
SET auto_tp_sl_enabled = COALESCE(auto_tp_sl_enabled, true),
    auto_sl_from_range_factor = COALESCE(auto_sl_from_range_factor, 1.80000000),
    auto_tp_from_range_factor = COALESCE(auto_tp_from_range_factor, 5.50000000),
    auto_min_risk_reward = COALESCE(auto_min_risk_reward, 2.40000000),
    auto_sl_min_pct = COALESCE(auto_sl_min_pct, 0.04000000),
    auto_sl_max_pct = COALESCE(auto_sl_max_pct, 0.18000000),
    auto_tp_min_pct = COALESCE(auto_tp_min_pct, 0.10000000),
    auto_tp_max_pct = COALESCE(auto_tp_max_pct, 0.80000000),
    auto_tp_ml_boost_factor = COALESCE(auto_tp_ml_boost_factor, 1.15000000),
    auto_tp_weak_signal_factor = COALESCE(auto_tp_weak_signal_factor, 0.90000000);
