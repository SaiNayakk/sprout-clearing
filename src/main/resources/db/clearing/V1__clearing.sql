-- Settlements: one per member per trade date, with a line per client and share. Each step of a
-- settlement is recorded as it completes, so a restart picks up where it stopped.

CREATE TABLE clients (
    member         text NOT NULL,
    client_code    text NOT NULL,
    bo_id          text NOT NULL,
    registered_at  timestamptz NOT NULL,
    PRIMARY KEY (member, client_code)
);

-- the next trade date to look for trades on
CREATE TABLE cursor (
    id         int PRIMARY KEY CHECK (id = 1),
    next_date  date NOT NULL
);

CREATE TABLE settlements (
    id               uuid PRIMARY KEY,
    member           text NOT NULL,
    trade_date       date NOT NULL,
    status           text NOT NULL CHECK (status IN ('COMPUTED', 'SECURITIES_IN', 'AWAITING_FUNDS', 'FUNDS_IN', 'FUNDS_OUT', 'SETTLED')),
    bought_paise     bigint NOT NULL,
    sold_paise       bigint NOT NULL,
    close_out_paise  bigint NOT NULL DEFAULT 0,
    funds_direction  text CHECK (funds_direction IN ('PAY', 'RECEIVE', 'NONE')),
    funds_paise      bigint,
    pay_reference    text NOT NULL UNIQUE,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    UNIQUE (member, trade_date)
);

CREATE INDEX settlements_unfinished ON settlements (updated_at) WHERE status <> 'SETTLED';

CREATE TABLE lines (
    settlement_id    uuid NOT NULL REFERENCES settlements (id),
    client_code      text NOT NULL,
    symbol           text NOT NULL,
    bought           bigint NOT NULL,
    sold             bigint NOT NULL,
    bought_paise     bigint NOT NULL,
    sold_paise       bigint NOT NULL,
    status           text NOT NULL CHECK (status IN ('PENDING', 'PAID_IN', 'SHORT', 'DELIVERED', 'NOTHING_TO_MOVE')),
    short_quantity   bigint NOT NULL DEFAULT 0,
    close_out_paise  bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (settlement_id, client_code, symbol)
);

-- What members must be told, delivered until they acknowledge.
CREATE TABLE outbox (
    id              uuid PRIMARY KEY,
    member          text NOT NULL,
    callback_url    text NOT NULL,
    body            text NOT NULL,
    created_at      timestamptz NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    attempts        int NOT NULL DEFAULT 0,
    delivered_at    timestamptz,
    last_error      text
);

CREATE INDEX outbox_due ON outbox (next_attempt_at) WHERE delivered_at IS NULL;
