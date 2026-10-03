-- Increment 3: khởi tạo thanh toán (Merchant -> Hub).
-- Thiết kế: docs/specs/02-payment-init-design.md

-- ─────────────────────────────────────────────────────────────────────────────
-- merchants: đối tác gọi API của Hub. Xác thực bằng API key + HMAC.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE merchants (
    id                     BIGSERIAL    PRIMARY KEY,
    merchant_ref           VARCHAR(32)  NOT NULL UNIQUE,   -- mch_xxx, định danh công khai
    name                   VARCHAR(200) NOT NULL,

    api_key                VARCHAR(64)  NOT NULL UNIQUE,   -- định danh (public), đi trong header
    -- ⚠️ DEV-ONLY: lưu PLAINTEXT.
    -- KHÔNG hash được vì Hub phải TÍNH LẠI HMAC(secret, canonical) — hash là một chiều.
    -- Production: mã hoá cột (AES) + khoá ở KMS. Xem docs/notes/hmac-request-signing.md §1.
    api_secret             VARCHAR(128) NOT NULL,
    api_secret_previous    VARCHAR(128),                   -- cửa sổ chồng lấn khi xoay khoá
    previous_expires_at    TIMESTAMPTZ,

    -- whitelist domain của return_url -> chống open redirect
    allowed_return_domains TEXT         NOT NULL DEFAULT '',  -- phân tách bằng dấu phẩy

    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | SUSPENDED
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- payments: aggregate trung tâm. Lưu KẾT LUẬN (quá trình gọi Bank -> Inc 4).
-- Bốn định danh, bốn khán giả (docs/notes/inc3-data-model.md §2).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE payments (
    id                  BIGSERIAL   PRIMARY KEY,           -- ① nội bộ: FK, join
    payment_ref         VARCHAR(40) NOT NULL UNIQUE,        -- ② merchant: vĩnh viễn, đối soát
    checkout_token      VARCHAR(64) NOT NULL UNIQUE,        -- ③ Ana: vé vào cửa, có hạn
    checkout_expires_at TIMESTAMPTZ NOT NULL,

    merchant_id         BIGINT      NOT NULL REFERENCES merchants(id),
    order_code          VARCHAR(64) NOT NULL,               -- ④ của MERCHANT (không unique toàn cục)

    amount              BIGINT      NOT NULL CHECK (amount > 0),  -- minor unit (CLAUDE.md #4)
    currency            CHAR(3)     NOT NULL,
    status              VARCHAR(20) NOT NULL,

    return_url          TEXT        NOT NULL,
    description         VARCHAR(255),
    metadata            JSONB,

    -- Inc 4 sẽ dùng (giữ sẵn để không phải migrate lại):
    bank_txn_id         VARCHAR(64),
    attempt_count       INT         NOT NULL DEFAULT 0,
    last_attempt_at     TIMESTAMPTZ,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Lớp chống trùng #2 (tầng nghiệp vụ): một đơn hàng chỉ có MỘT payment ĐANG SỐNG.
-- PARTIAL index: phải loại các trạng thái đã chết, nếu không sẽ chặn luôn việc
-- thử lại hợp lệ sau EXPIRED/FAILED/CANCELLED.
CREATE UNIQUE INDEX uq_payment_active
    ON payments (merchant_id, order_code)
    WHERE status IN ('CREATED', 'IN_PROGRESS');

CREATE INDEX idx_payment_merchant_order ON payments (merchant_id, order_code);
CREATE INDEX idx_payment_expiry         ON payments (status, checkout_expires_at);

-- ─────────────────────────────────────────────────────────────────────────────
-- idempotency_records: lớp chống trùng #1 (tầng giao thức).
-- Ở CÙNG database với payments -> ghi trong CÙNG transaction -> không có dual-write.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE idempotency_records (
    id                  BIGSERIAL   PRIMARY KEY,
    merchant_id         BIGINT      NOT NULL REFERENCES merchants(id),
    idempotency_key     VARCHAR(64) NOT NULL,
    endpoint            VARCHAR(80) NOT NULL,

    -- "có đúng là CÙNG một request không?" -> phát hiện cùng key nhưng body khác
    request_fingerprint CHAR(64)    NOT NULL,

    state               VARCHAR(16) NOT NULL,               -- IN_PROGRESS | COMPLETED
    response_status     INT,
    response_body       TEXT,                               -- NGUYÊN VĂN, không dựng lại từ DB
    payment_id          BIGINT      REFERENCES payments(id),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ NOT NULL,

    UNIQUE (merchant_id, idempotency_key)   -- gồm merchant_id: chống rò rỉ xuyên tenant
);

CREATE INDEX idx_idem_expiry ON idempotency_records (expires_at);

-- ─────────────────────────────────────────────────────────────────────────────
-- Seed merchant cho môi trường dev. DEV-ONLY.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO merchants (merchant_ref, name, api_key, api_secret, allowed_return_domains)
VALUES ('mch_demo', 'Chợ Phiên (merchant-demo)', 'mk_dev_demo',
        'sk_dev_demo_secret_do_not_use_in_prod',
        'localhost,127.0.0.1,shop.example.com');
