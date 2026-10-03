-- Increment 2 Phase 2: merchant gọi Payment Hub để khởi tạo thanh toán.

ALTER TABLE orders
    -- Khoá idempotency dùng khi gọi HUB. KHÁC hoàn toàn orders.idempotency_key
    -- (cái kia là khoá của chặng trình duyệt -> merchant).
    -- SINH MỘT LẦN rồi LƯU LẠI: nếu sinh mới mỗi lần gọi thì retry sẽ tạo payment thứ hai.
    ADD COLUMN hub_idempotency_key VARCHAR(80),
    -- checkout_url do Hub trả về. Coi là OPAQUE: dùng nguyên xi, không phân tích, không dựng lại.
    ADD COLUMN hub_checkout_url    TEXT,
    ADD COLUMN hub_requested_at    TIMESTAMPTZ;

COMMENT ON COLUMN orders.hub_payment_ref IS
    'payment_ref do Hub cấp — định danh vĩnh viễn để đối soát/hỗ trợ.';
