CREATE TABLE bank_transactions (
    id              BIGSERIAL    PRIMARY KEY,
    bank_code       VARCHAR(16)  NOT NULL,
    bank_txn_ref    VARCHAR(64)  NOT NULL UNIQUE,
    idempotency_key VARCHAR(64)  NOT NULL,
    amount          BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,
    merchant_name   VARCHAR(128) NOT NULL,
    callback_url    TEXT         NOT NULL,
    return_url      TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    UNIQUE (bank_code, idempotency_key)
);

CREATE INDEX idx_bank_txn_ref ON bank_transactions (bank_txn_ref);
