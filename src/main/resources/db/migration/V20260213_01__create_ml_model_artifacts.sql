create table if not exists ml_model_artifacts
(
    id               bigserial primary key,

    chat_id          bigint       not null,
    strategy_type    varchar(64)  not null,
    exchange         varchar(32),
    network          varchar(32),
    symbol           varchar(32),
    timeframe        varchar(16),

    model_key        varchar(255),
    model_version    varchar(255),

    schema_hash      varchar(128),
    metrics_json     text,

    file_path        varchar(512),
    file_size_bytes  bigint,

    created_at       timestamp without time zone not null default now(),
    updated_at       timestamp without time zone not null default now()
);

create index if not exists idx_ml_model_artifacts_chat
    on ml_model_artifacts(chat_id);

create index if not exists idx_ml_model_artifacts_chat_type
    on ml_model_artifacts(chat_id, strategy_type);

create index if not exists idx_ml_model_artifacts_key
    on ml_model_artifacts(model_key);

create index if not exists idx_ml_model_artifacts_symbol
    on ml_model_artifacts(symbol);
