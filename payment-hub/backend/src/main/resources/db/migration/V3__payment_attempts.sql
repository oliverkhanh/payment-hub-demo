-- Increment 4: Hub chuyển CREATED -> IN_PROGRESS rồi gọi sang Bank tạo giao dịch.
-- Thiết kế: docs/specs/03-bank-capture-spec.md §2.3

-- Ana chọn ngân hàng ở trang checkout của Hub, KHÔNG phải merchant chọn (spec §2.4).
-- Vì vậy cột này NULL cho tới lúc Ana bấm "Thanh toán".
ALTER TABLE payments ADD COLUMN bank_code VARCHAR(16);

-- ─────────────────────────────────────────────────────────────────────────────
-- payment_attempts — mỗi dòng là MỘT lần Hub gọi sang Bank.
--
-- payments        lưu KẾT LUẬN  (cuối cùng tiền có chuyển hay không)
-- payment_attempts lưu QUÁ TRÌNH (đã gọi mấy lần, mỗi lần ra sao)
--
-- Không có bảng này thì khi timeout Hub mất dấu: nó không biết đã gửi khoá
-- idempotency nào sang Bank để mà đi hỏi lại.
--
-- ⭐ Dòng này được GHI VÀ COMMIT *TRƯỚC* khi gọi HTTP (cùng transaction với
-- CREATED -> IN_PROGRESS). Hub sập ngay sau commit thì dòng outcome=NULL còn đó,
-- job đối soát biết phải ĐI HỎI Bank bằng khoá nào.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE payment_attempts (
    id              BIGSERIAL   PRIMARY KEY,
    payment_id      BIGINT      NOT NULL REFERENCES payments(id),

    -- Lần thứ mấy của CHÍNH payment này.
    attempt_no      INT         NOT NULL,
    bank_code       VARCHAR(16) NOT NULL,

    -- Khoá Hub gửi sang Bank. Sinh MỘT LẦN rồi lưu lại: gửi lại phải GIỮ NGUYÊN khoá,
    -- nếu không Bank coi là giao dịch mới và trừ tiền hai lần.
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,

    -- Bank đã trả lời gì. NULL = CHƯA CÓ TRẢ LỜI (đang bay, hoặc Hub sập giữa đường).
    -- Danh sách giá trị: enum BankOutcome (domain/bank/BankOutcome.java) — để một nguồn
    -- sự thật duy nhất, không liệt kê lại ở đây rồi lệch nhau.
    outcome         VARCHAR(16),

    bank_txn_id     VARCHAR(64),
    redirect_url    TEXT,
    http_status     INT,
    error_code      VARCHAR(64),

    requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at     TIMESTAMPTZ,

    UNIQUE (payment_id, attempt_no)
);

CREATE INDEX idx_attempt_payment ON payment_attempts (payment_id, attempt_no DESC);

-- Job đối soát quét đúng những dòng CÒN TREO: chưa có trả lời, hoặc trả lời "không biết".
-- Hai thứ đó cần cùng một hành động (đi hỏi Bank) nên cùng một index.
CREATE INDEX idx_attempt_in_doubt ON payment_attempts (requested_at)
    WHERE outcome IS NULL OR outcome = 'INDETERMINATE';
