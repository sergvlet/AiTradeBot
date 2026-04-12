create table if not exists strategy_positions (
                                                  id bigserial primary key,
                                                  position_uid varchar(80) not null,
                                                  chat_id bigint not null,
                                                  strategy_type varchar(64) not null,
                                                  exchange_name varchar(32) not null,
                                                  network_type varchar(16) not null,
                                                  symbol varchar(32) not null,
                                                  status varchar(32) not null,
                                                  side varchar(8) not null,
                                                  qty numeric(28,12),
                                                  avg_entry_price numeric(28,12),
                                                  quote_spent numeric(28,12),
                                                  tp_price numeric(28,12),
                                                  sl_price numeric(28,12),
                                                  source varchar(32) not null,
                                                  entry_order_id bigint,
                                                  exit_order_id bigint,
                                                  entry_client_order_id varchar(128),
                                                  exit_client_order_id varchar(128),
                                                  entry_exchange_order_id varchar(128),
                                                  exit_exchange_order_id varchar(128),
                                                  last_exchange_sync_at timestamp,
                                                  opened_at timestamp not null,
                                                  closed_at timestamp,
                                                  created_at timestamp not null,
                                                  updated_at timestamp not null,
                                                  version integer
);

create unique index if not exists ux_strategy_positions_position_uid
    on strategy_positions(position_uid);

create index if not exists ix_strategy_positions_ctx
    on strategy_positions(chat_id, strategy_type, exchange_name, network_type, symbol);

create index if not exists ix_strategy_positions_status
    on strategy_positions(status);

alter table orders add column if not exists position_uid varchar(80);
alter table orders add column if not exists order_type varchar(16);
alter table orders add column if not exists intent varchar(24);
alter table orders add column if not exists client_order_id varchar(128);
alter table orders add column if not exists exchange_order_id varchar(128);
alter table orders add column if not exists exchange_status varchar(64);
alter table orders add column if not exists requested_qty numeric(28,12);
alter table orders add column if not exists requested_price numeric(28,12);
alter table orders add column if not exists executed_qty numeric(28,12);
alter table orders add column if not exists executed_quote_qty numeric(28,12);
alter table orders add column if not exists avg_executed_price numeric(28,12);
alter table orders add column if not exists fee_total numeric(28,12);
alter table orders add column if not exists fee_asset varchar(16);
alter table orders add column if not exists parent_order_id bigint;
alter table orders add column if not exists is_reduce_only boolean;
alter table orders add column if not exists is_close_order boolean;
alter table orders add column if not exists source varchar(24);
alter table orders add column if not exists correlation_id varchar(80);
alter table orders add column if not exists reject_code varchar(64);
alter table orders add column if not exists reject_message varchar(512);

create index if not exists idx_orders_position_uid on orders(position_uid);
create index if not exists idx_orders_client_order_id on orders(client_order_id);
create index if not exists idx_orders_exchange_order_id on orders(exchange_order_id);

