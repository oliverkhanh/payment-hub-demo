-- Increment 4: Fake Bank giả lập 3 ngân hàng (VCB / TCB / ACB) trong MỘT service.
-- Hub đối xử với chúng như 3 hệ thống độc lập; ở đây chỉ khác nhau qua bank_code.

-- ─────────────────────────────────────────────────────────────────────────────
-- bank_accounts: SỐ DƯ LÀ TRẠNG THÁI, phải lưu DB (không fix cứng trong code).
-- Nhờ có số dư thật mới ĐO ĐƯỢC idempotency: Hub retry 3 lần -> số dư giảm 1 lần.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE bank_accounts (
    code      VARCHAR(16)  PRIMARY KEY,
    bank_code VARCHAR(8)   NOT NULL,
    holder    VARCHAR(100) NOT NULL,
    -- minor unit (CLAUDE.md #4). CHECK là chốt chặn cuối phòng khi code trừ sai.
    balance   BIGINT       NOT NULL CHECK (balance >= 0),
    pin       VARCHAR(8)   NOT NULL,            -- ⚠️ DEV-ONLY: bank thật KHÔNG lưu PIN dạng này
    status    VARCHAR(16)  NOT NULL,            -- ACTIVE | LOCKED
    -- "núm vặn" giả lập, KHÔNG phải trạng thái nghiệp vụ:
    behavior  VARCHAR(16)  NOT NULL DEFAULT 'NORMAL'   -- NORMAL | SLOW | ERROR
);

-- ─────────────────────────────────────────────────────────────────────────────
-- bank_transactions: Bank phải IDEMPOTENT — đó là tiền đề để Hub dám retry.
-- Muốn nhận ra "cái này xử lý rồi" thì phải NHỚ ⇒ phải có bảng.
-- Bảng này cũng để trả lời câu hỏi đối soát của Hub:
--   GET /transactions?idempotency_key=...  → "giao dịch này ra sao rồi?"
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE bank_transactions (
    bank_txn_id     VARCHAR(40)  PRIMARY KEY,
    bank_code       VARCHAR(8)   NOT NULL,
    account_code    VARCHAR(16)  REFERENCES bank_accounts(code),  -- NULL tới khi khách nhập
    amount          BIGINT       NOT NULL CHECK (amount > 0),
    currency        CHAR(3)      NOT NULL,
    merchant_name   VARCHAR(200) NOT NULL,       -- để hiện trên trang thanh toán

    status          VARCHAR(16)  NOT NULL,       -- PENDING|SUCCESS|DECLINED|CANCELLED
    decline_reason  VARCHAR(40),                 -- insufficient_funds|account_locked|invalid_pin

    -- Khoá Hub gửi sang. UNIQUE theo bank ⇒ Hub retry cùng khoá KHÔNG tạo giao dịch thứ hai.
    idempotency_key VARCHAR(64)  NOT NULL,

    callback_url    TEXT         NOT NULL,       -- kênh A: bank -> Hub (server→server)
    return_url      TEXT         NOT NULL,       -- kênh B: bank -> trình duyệt Ana

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,

    UNIQUE (bank_code, idempotency_key)          -- ⭐ chốt chặn chống trùng
);

CREATE INDEX idx_bank_txn_lookup ON bank_transactions (bank_code, idempotency_key);

-- ─────────────────────────────────────────────────────────────────────────────
-- Tài khoản thử nghiệm — cùng ý tưởng thẻ test của Stripe / VNPay sandbox.
-- Mỗi tài khoản kích hoạt MỘT kịch bản, để test được cả những ca mà số dư
-- không tạo ra được (timeout, lỗi 5xx).
-- PIN đúng cho mọi tài khoản: 123456  (DEV-ONLY)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO bank_accounts (code, bank_code, holder, balance, pin, status, behavior) VALUES
  -- VCB — ngân hàng "khoẻ"
  ('ACC-001', 'VCB', 'Nguyen Thi Ana',   5000000, '123456', 'ACTIVE', 'NORMAL'),
  ('ACC-002', 'VCB', 'Tran Van Beo',       50000, '123456', 'ACTIVE', 'NORMAL'),  -- không đủ tiền
  ('ACC-003', 'VCB', 'Le Thi Cuc',       9999999, '123456', 'LOCKED', 'NORMAL'),  -- tài khoản khoá
  ('ACC-004', 'VCB', 'Pham Van Dung',    9999999, '123456', 'ACTIVE', 'SLOW'),    -- bank treo -> timeout
  ('ACC-005', 'VCB', 'Hoang Thi Em',     9999999, '123456', 'ACTIVE', 'ERROR'),   -- bank trả 5xx

  -- TCB — ngân hàng "chậm"
  ('ACC-101', 'TCB', 'Nguyen Thi Ana',   5000000, '123456', 'ACTIVE', 'NORMAL'),
  ('ACC-102', 'TCB', 'Tran Van Beo',       50000, '123456', 'ACTIVE', 'NORMAL'),

  -- ACB — ngân hàng "hay lỗi"
  ('ACC-201', 'ACB', 'Nguyen Thi Ana',   5000000, '123456', 'ACTIVE', 'NORMAL'),
  ('ACC-202', 'ACB', 'Tran Van Beo',       50000, '123456', 'ACTIVE', 'NORMAL');
