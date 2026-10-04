CREATE TABLE outbox (
    id              BIGSERIAL    PRIMARY KEY,

    event_id        UUID         NOT NULL UNIQUE,
    bank_code       VARCHAR(16)  NOT NULL,

    aggregate_type  VARCHAR(32)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    payload         TEXT         NOT NULL,

    destination     TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);

CREATE INDEX idx_outbox_due ON outbox (next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id, created_at DESC);
