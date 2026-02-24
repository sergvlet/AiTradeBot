-- ============================================================================
-- ML samples (dataset rows)
-- PostgreSQL
-- ============================================================================

create table if not exists ml_samples
(
    id               bigserial primary key,

    -- multi-tenant / routing
    chat_id          bigint       not null,
    strategy_type    varchar(64)  not null,
    exchange         varchar(32),
    network          varchar(32),

    -- market identity
    symbol           varchar(32)  not null,
    timeframe        varchar(16),
    ts              timestamptz,            -- timestamp of candle / sample time

    -- ML data
    label            varchar(32),           -- e.g. "1"/"0"/"TP"/"SL"/"WIN"/"LOSE"
    target           varchar(32),           -- optional (what you predict)
    proba            double precision,      -- predicted probability (or stored)
    features_json    jsonb,                 -- features snapshot
    meta_json        jsonb,                 -- any extra: thresholds, window, etc.

    -- audit
    created_at       timestamptz not null default now()
);

-- useful indexes
create index if not exists ix_ml_samples_chat_strategy_time
    on ml_samples (chat_id, strategy_type, created_at desc);

create index if not exists ix_ml_samples_symbol_time
    on ml_samples (symbol, created_at desc);

create index if not exists ix_ml_samples_ts
    on ml_samples (ts desc);
