CREATE TABLE accounts (
    id               BIGSERIAL    PRIMARY KEY,
    username         VARCHAR(32)  NOT NULL UNIQUE,
    pin              VARCHAR(12)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    balance          BIGINT       NOT NULL DEFAULT 0,
    is_slow          BOOLEAN      NOT NULL DEFAULT false,
    is_error_account BOOLEAN      NOT NULL DEFAULT false,
    created_date     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_date     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO accounts (username, pin, status, balance, is_slow, is_error_account) VALUES
    ('user_active',   '123456', 'ACTIVE', 10000000, false, false),
    ('user_locked',   '123456', 'LOCKED',  5000000, false, false),
    ('user_poor',     '123456', 'ACTIVE',    50000, false, false),
    ('user_slow',     '123456', 'ACTIVE', 10000000, true,  false),
    ('user_error',    '123456', 'ACTIVE', 10000000, false, true);
