-- Increment 4 — INTENT LOG: mỗi lần Hub định gọi sang Bank là một dòng ở đây.
-- Thiết kế: docs/specs/03-bank-capture-spec.md · docs/notes/bank-call-timeout-and-retry.md

-- ─────────────────────────────────────────────────────────────────────────────
-- Vì sao phải có bảng RIÊNG, không nhồi vào payments?
--
--   payments         lưu KẾT LUẬN   — cuối cùng tiền có chuyển hay không
--   payment_attempts lưu QUÁ TRÌNH  — đã gọi mấy lần, mỗi lần qua bank nào, ra sao
--
-- Không có bảng này thì khi timeout Hub mất dấu hoàn toàn: nó không biết mình đã gửi
-- khoá idempotency NÀO sang Bank, nên không có gì để đi hỏi lại.
--
-- ⭐ Dòng này được GHI VÀ COMMIT *TRƯỚC* khi gọi HTTP, cùng transaction với
-- CAS CREATED -> IN_PROGRESS. Hub sập ngay sau commit thì dòng "chưa có kết quả"
-- vẫn còn, và job đối soát biết phải đi hỏi Bank bằng khoá nào.
-- Ngược lại (gọi trước, ghi sau) mà sập giữa chừng: Bank có thể đã trừ tiền
-- mà Hub không còn gì để tra.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE payment_attempts (
    id              BIGSERIAL   PRIMARY KEY,
    payment_id      BIGINT      NOT NULL REFERENCES payments(id),

    -- Lần thử thứ mấy của CHÍNH payment này. Bắt đầu từ 1.
    attempt_no      INT         NOT NULL,

    -- Ngân hàng Ana chọn ở lần thử này. Nằm ở ĐÂY chứ không phải ở payments:
    -- lần 1 có thể chọn VCB, lần 2 đổi sang TCB — payments không mô tả được điều đó.
    bank_code       VARCHAR(16) NOT NULL,

    -- Khoá Hub gửi sang Bank. SINH ĐÚNG MỘT LẦN rồi lưu lại: mọi lần gửi lại HTTP
    -- của cùng attempt này phải dùng LẠI chính khoá này, nếu không Bank coi là giao
    -- dịch mới và trừ tiền hai lần.
    -- UNIQUE toàn cục (không phải theo payment): một khoá = một lần gửi đi, không bao
    -- giờ tái sử dụng, và tra ngược từ khoá về attempt luôn ra đúng một dòng.
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,

    -- ── Các cột dưới đây để NULL cho tới khi có câu trả lời từ Bank ──
    -- NULL = CHƯA BIẾT GÌ. Đây chính là trạng thái "in-doubt", không cần enum riêng.
    -- Danh sách giá trị: enum BankOutcome (sẽ thêm ở bước gọi Bank).
    outcome         VARCHAR(16),
    bank_txn_id     VARCHAR(64),
    redirect_url    TEXT,
    http_status     INT,
    error_code      VARCHAR(64),

    requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at     TIMESTAMPTZ,

    -- Chặn hai luồng cùng tạo attempt #1 cho một payment. CAS ở payments đã lọc phần
    -- lớn, nhưng ràng buộc ở DB mới là chốt chặn cuối — không dựa vào thứ tự chạy.
    UNIQUE (payment_id, attempt_no)
);

CREATE INDEX idx_attempt_payment ON payment_attempts (payment_id, attempt_no DESC);

-- Job đối soát quét đúng những dòng CÒN TREO: chưa có trả lời, hoặc trả lời "không biết".
-- Hai thứ đó cần cùng một hành động (đi hỏi Bank) nên dùng chung một index.
-- Partial index: tuyệt đại đa số dòng đã có kết luận, không có lý do nằm trong index.
CREATE INDEX idx_attempt_in_doubt ON payment_attempts (requested_at)
    WHERE outcome IS NULL OR outcome = 'INDETERMINATE';
