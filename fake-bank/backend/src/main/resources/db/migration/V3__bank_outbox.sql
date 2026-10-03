-- Increment 4 — T1: Outbox cho callback Bank -> Hub.
-- Thiết kế: docs/specs/03-bank-capture-spec.md · CLAUDE.md #2
--
-- ⭐ VẤN ĐỀ OUTBOX GIẢI QUYẾT
-- Bank vừa phải (a) ghi "đã trừ tiền" vào DB, vừa phải (b) báo cho Hub biết.
-- Hai việc ở hai hệ thống khác nhau ⇒ không có transaction nào bao được cả hai.
--   ghi DB rồi gọi HTTP, sập giữa chừng -> tiền đã trừ mà Hub KHÔNG BAO GIỜ biết
--   gọi HTTP rồi ghi DB, sập giữa chừng -> Hub tưởng xong mà bank chưa ghi gì
-- Outbox gộp (a) và (b) thành MỘT thao tác ghi DB duy nhất: ý định gửi được ghi
-- trong CÙNG transaction với việc trừ tiền. Hoặc cả hai cùng có, hoặc cả hai cùng không.
-- Việc gửi thật do một worker riêng làm sau, và nó có thể thất bại thoải mái.

CREATE TABLE bank_outbox (
    id              BIGSERIAL    PRIMARY KEY,

    -- Định danh SỰ KIỆN (không phải của giao dịch). Hub nhận nó qua header
    -- Idempotency-Key để nhận ra callback trùng. UNIQUE: một sự kiện chỉ sinh một lần.
    event_id        VARCHAR(64)  NOT NULL UNIQUE,

    bank_code       VARCHAR(8)   NOT NULL,
    bank_txn_id     VARCHAR(40)  NOT NULL REFERENCES bank_transactions(bank_txn_id),

    -- CHỤP LẠI lúc tạo sự kiện, không join sang bank_transactions lúc gửi.
    -- Worker gửi 10 phút sau phải gửi đúng địa chỉ của LÚC ĐÓ.
    callback_url    TEXT         NOT NULL,

    -- ⚠️ TEXT, KHÔNG PHẢI JSONB — và đây là chi tiết dễ sai nhất bảng này.
    -- Postgres chuẩn hoá JSONB: sắp xếp lại khoá, bỏ khoảng trắng. Chữ ký HMAC tính
    -- trên BYTE, nên JSONB sẽ trả về một chuỗi byte KHÁC chuỗi ta đã ký
    -- -> Hub tính ra chữ ký khác -> 401 mà không hiểu vì sao.
    -- Body phải được ĐÓNG BĂNG nguyên văn, giống hệt idempotency_records.response_body
    -- bên Hub ở Inc 3.
    payload         TEXT         NOT NULL,

    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',   -- PENDING | SENT | FAILED
    attempts        INT          NOT NULL DEFAULT 0,

    -- Worker chỉ lấy dòng đã tới hạn. Backoff = đẩy mốc này về tương lai.
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      VARCHAR(200),

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);

-- Worker quét đúng những dòng CÒN PHẢI GỬI. Partial index: gửi xong rồi thì
-- dòng nằm im mãi mãi, không có lý do gì để nằm trong index.
CREATE INDEX idx_outbox_due ON bank_outbox (next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_txn ON bank_outbox (bank_txn_id);
