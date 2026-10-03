-- Increment 2 (Phase 1): catalog sản phẩm + order (tạo khi checkout).
-- Nguyên tắc (CLAUDE.md): tiền = số nguyên minor unit + currency (ISO 4217). KHÔNG float.

CREATE TABLE products (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(160) NOT NULL,
    description   VARCHAR(300),
    price_amount  BIGINT       NOT NULL CHECK (price_amount >= 0),   -- minor unit
    currency      VARCHAR(3)   NOT NULL,
    image_emoji   VARCHAR(16),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id               BIGSERIAL   PRIMARY KEY,
    order_code       VARCHAR(40)  NOT NULL UNIQUE,          -- mã công khai "ord_..."
    status           VARCHAR(24)  NOT NULL,                 -- state machine (OrderStatus)
    total_amount     BIGINT       NOT NULL CHECK (total_amount >= 0),  -- tính ở SERVER
    currency         VARCHAR(3)   NOT NULL,
    idempotency_key  VARCHAR(80)  NOT NULL UNIQUE,          -- chống trùng (chốt chặn atomic ở DB)
    hub_payment_ref  VARCHAR(80),                           -- chừa cho Phase 2 (tham chiếu Hub)
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    id                 BIGSERIAL    PRIMARY KEY,
    order_id           BIGINT       NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id         BIGINT       NOT NULL,               -- tham chiếu lỏng (đã snapshot bên dưới)
    product_name       VARCHAR(160) NOT NULL,               -- SNAPSHOT tên tại lúc đặt
    unit_price_amount  BIGINT       NOT NULL CHECK (unit_price_amount >= 0),  -- SNAPSHOT giá tại lúc đặt
    currency           VARCHAR(3)   NOT NULL,
    quantity           INT          NOT NULL CHECK (quantity > 0)
);
CREATE INDEX idx_order_items_order ON order_items(order_id);

-- Seed 6 sản phẩm cố định (giá VND, số nguyên đồng)
INSERT INTO products (name, description, price_amount, currency, image_emoji) VALUES
  ('Cà phê Robusta 500g', 'Rang mộc, pha phin đậm vị',   145000, 'VND', '☕'),
  ('Trà sen Tây Hồ 200g', 'Ướp sen thủ công, hộp thiếc', 220000, 'VND', '🍵'),
  ('Bánh đậu xanh (hộp)',  'Đặc sản Hải Dương, 240g',      65000, 'VND', '🍪'),
  ('Ấm gốm Bát Tràng',     'Men lam vẽ tay, 350ml',       380000, 'VND', '🏺'),
  ('Nón lá thủ công',      'Lá cọ khâu tay, quai lụa',     90000, 'VND', '👒'),
  ('Mứt gừng 300g',        'Cay ấm, ngọt thanh, Tết',      55000, 'VND', '🫚');
