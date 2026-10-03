# Ghi chú — Mô hình dữ liệu Payment Hub (Increment 3)

> Tổng hợp toàn bộ phần thiết kế dữ liệu Inc 3 (các câu hỏi B1→B6), sắp lại **theo bảng**
> thay vì theo thứ tự hỏi, cho dễ tra.
> Đọc kèm: [02-payment-init-spec.md](../specs/02-payment-init-spec.md) ·
> [bank-call-timeout-and-retry.md](bank-call-timeout-and-retry.md) ·
> [payment-state-machine-walkthrough.md](payment-state-machine-walkthrough.md)

---

## 0. Bản đồ — bốn bảng, bốn nhiệm vụ

```
merchants ──1:n──▶ payments ──1:n──▶ payment_attempts
    │                  ▲
    └──1:n──▶ idempotency_records ──┘
```

| Bảng | Lưu gì | Câu hỏi nó trả lời |
|---|---|---|
| `merchants` | danh tính + khóa HMAC của đối tác | *"Ai gửi request này? Có thật không?"* |
| `payments` | **kết luận** nghiệp vụ | *"Đơn này trả tiền tới đâu rồi?"* |
| `payment_attempts` | **quá trình** nói chuyện với Bank | *"Ta đã nói gì với Bank, lúc nào?"* |
| `idempotency_records` | **quá trình** phục vụ merchant | *"Ta đã trả lời merchant cái gì?"* |

> **Nguyên tắc xuyên suốt:** `payments` giữ **kết luận**; hai bảng kia giữ **quá trình**.
> Khi tiền dính vào, *quá trình cũng là dữ liệu nghiệp vụ* — không phải log vứt đi được.

---

## 1. `merchants` — vì sao secret **không hash được** (B1)

```sql
CREATE TABLE merchants (
    id           BIGSERIAL PRIMARY KEY,
    merchant_ref VARCHAR(32)  NOT NULL UNIQUE,   -- mch_xxx, công khai
    name         VARCHAR(200) NOT NULL,
    api_key      VARCHAR(64)  NOT NULL UNIQUE,   -- định danh (public)
    api_secret   VARCHAR(128) NOT NULL,          -- ⚠️ PLAINTEXT — xem ghi chú
    status       VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### Lý do gốc: **toán học**, không phải lựa chọn

| | Inc 1 — `client_secret` (OAuth2) | Inc 3 — `api_secret` (HMAC) |
|---|---|---|
| Hub cần làm gì với secret | **SO KHỚP**: "chuỗi này đúng không?" | **TÍNH TOÁN**: `HMAC(secret, payload)` |
| Cần gì | chỉ cần **hash** để đối chiếu | **bản thân secret gốc** |
| Hash được? | ✅ BCrypt — `matches(input, hash)` | ❌ hash **một chiều**, không lấy ngược ra để tính |

> **Câu nhớ:** cái gì chỉ cần **so khớp** → hash được.
> Cái gì phải **dùng lại để tính** → không hash được.

### HMAC ≠ chữ ký số (dùng đúng từ)

| | HMAC | Chữ ký số (RS256, Inc 1) |
|---|---|---|
| Khóa | **1 khóa chung** (đối xứng) | private ký / public verify |
| Bên verify làm giả được? | **CÓ** (có cùng khóa) | Không |
| Chống chối bỏ (non-repudiation) | ❌ | ✅ |

⇒ Lộ `api_secret` = kẻ trộm **ký được như merchant thật** ⇒ tạo lệnh thu tiền giả.
Rủi ro **cao hơn Inc 1** (lộ hash BCrypt thì kẻ trộm vẫn vô dụng).

### Cách vá đúng

```
Inc 1:  DB lưu BCrypt(secret)          → lộ cũng vô hại
Inc 3:  DB lưu AES(secret), khóa ở KMS → lộ DB vẫn vô hại
        DB lưu secret trần              → lộ DB = thảm họa  ⚠️
```

- **Mã hóa cột + khóa ở KMS** là chuẩn cho secret **per-merchant** (hàng nghìn dòng, xoay liên tục).
- **Secrets Manager** hợp hơn cho secret của *ứng dụng* (ít, cố định).

> 🔖 **DEV-ONLY:** dự án học tập ⇒ lưu plaintext. Phải ghi chú trong migration + spec:
> `production = AES + KMS`. (CLAUDE.md: *"production làm X, ở đây làm Y vì Z"*.)

---

## 2. `payments` — **bốn** định danh, bốn khán giả (B2)

```sql
CREATE TABLE payments (
    id             BIGSERIAL   PRIMARY KEY,        -- ① nội bộ
    payment_ref    VARCHAR(40) NOT NULL UNIQUE,    -- ② merchant
    checkout_token VARCHAR(64) NOT NULL UNIQUE,    -- ③ Ana (vé vào cửa)
    checkout_expires_at TIMESTAMPTZ NOT NULL,

    merchant_id    BIGINT      NOT NULL REFERENCES merchants(id),
    order_code     VARCHAR(64) NOT NULL,           -- ④ của MERCHANT

    amount         BIGINT      NOT NULL,           -- minor unit (CLAUDE.md #4)
    currency       CHAR(3)     NOT NULL,
    status         VARCHAR(20) NOT NULL,

    bank_txn_id    VARCHAR(64),                    -- phi chuẩn hóa, lần thành công
    attempt_count  INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

| Cột | Ai dùng | Vì sao tồn tại |
|---|---|---|
| `id` BIGSERIAL | **chỉ trong Hub**: FK, join, index | 8 byte, join nhanh, tăng dần → B-tree không phân mảnh |
| `payment_ref` `pay_xxx` | **merchant**, log, support | không đoán được; **sống vĩnh viễn** để đối soát |
| `checkout_token` `ctk_xxx` | **Ana** (người lạ, trên URL) | **vé vào cửa**, ngẫu nhiên ≥128 bit, **15 phút**, dùng 1 lần |
| `order_code` | **merchant sở hữu** | tham chiếu ngoại + **khóa nghiệp vụ chống trùng** |

### Vì sao KHÔNG dùng chung một cột

**Không dùng `id` ra ngoài** — `/checkout/1041` ⇒ **enumeration** (sửa thành 1042) +
**rò rỉ kinh doanh** (trừ id hai thời điểm là biết lưu lượng giao dịch/giờ).

> **Nguyên tắc:** *khóa nội bộ ≠ định danh công khai.* Đừng bao giờ để số tự tăng lọt ra ngoài.

**Không dùng `payment_ref` làm URL checkout** — ba lý do:

1. **Vòng đời lệch:** `payment_ref` sống **mãi mãi** (merchant lưu trong `orders.hub_payment_ref`);
   URL chỉ cần sống **15 phút**. Gộp làm một ⇒ không thể hết hạn cái này mà giữ cái kia.
2. **URL rò rỉ dễ:** Referer header, lịch sử trình duyệt, log proxy, khách copy link gửi Zalo.
3. **Nó là capability, không phải identifier:** trang checkout **không có login** ⇒ chuỗi
   trong URL chính là thứ duy nhất chứng minh "tôi có quyền trả đơn này" ⇒ phải **có hạn**
   và **dùng xong thì hỏng**.

> **Nguyên tắc:** *một cột định danh chỉ phục vụ một khán giả* — vì mỗi khán giả có yêu cầu
> **bảo mật + vòng đời** khác nhau.

### `order_code` thuộc về **merchant**

- Hub **không sinh, không kiểm soát định dạng** (A dùng `ord_xxx`, B dùng `DH-2026-001`).
- ⇒ **Không unique toàn cục** — A và B đều có thể có đơn `INV-001`.
- Công dụng **chủ động** (không chỉ đối soát): trả lời *"đơn này đã có payment chưa?"*
  ⇒ chặn khách bị trừ tiền hai lần cho cùng một đơn.

---

## 3. Chống trùng: **hai lớp**, không thay thế nhau (B3)

```
Idempotency-Key  → "cùng một LỜI GỌI"    → tầng giao thức · mọi API · có fingerprint
UNIQUE(m, code)  → "cùng một ĐƠN HÀNG"   → tầng nghiệp vụ · riêng payments · partial
```

### ⚠️ UNIQUE phải là **partial index**

State machine đã chốt: *"thử lại = payment MỚI"*. UNIQUE cứng sẽ phá luôn luồng đó:

```
14:00  ord_8222 → P1 (CREATED)
14:16  job quét → P1 = EXPIRED
14:20  Ana bấm trả lại → INSERT P2 ... ❌ UNIQUE VIOLATION → Ana KHÔNG BAO GIỜ trả được
```

```sql
CREATE UNIQUE INDEX uq_payment_active
  ON payments (merchant_id, order_code)
  WHERE status IN ('CREATED', 'IN_PROGRESS');   -- chỉ chặn payment ĐANG SỐNG
```
`CAPTURED` chặn bằng luật nghiệp vụ riêng (đơn đã trả rồi).

### Ba chỗ UNIQUE một mình để lọt

| # | Tình huống | Vì sao UNIQUE không đủ |
|---|---|---|
| ① | thử lại hợp lệ sau `EXPIRED` | UNIQUE cứng chặn nhầm ⇒ phải partial |
| ② | **cùng key, body khác** (amount 355.000 → 1.000) | UNIQUE chỉ thấy "trùng order_code" ⇒ **im lặng nuốt bug**. Idempotency-Key có **fingerprint** ⇒ trả **422**, gào lên |
| ③ | API không có `order_code` (refund từng phần, Inc 6) | không có cột nào để UNIQUE, nhưng retry do timeout vẫn phải chống |

CLAUDE.md #1: **idempotency-first cho MỌI API tạo/refund** ⇒ Idempotency-Key là **hợp đồng
chung của nền tảng**; UNIQUE là **luật riêng của một bảng**.

> Ẩn dụ: Idempotency-Key = *"thư này tôi gửi rồi"*; UNIQUE = *"đơn này đã có người đang xử lý"*.

---

## 4. `payment_attempts` — quá trình gọi Bank (B4)

**Quan hệ 1–n**: một payment có **nhiều lần gọi Bank** (retry). Nhét vào `payments` ⇒
UPDATE đè, mất sạch lịch sử.

```sql
CREATE TABLE payment_attempts (
    id                  BIGSERIAL PRIMARY KEY,
    payment_id          BIGINT NOT NULL REFERENCES payments(id),
    attempt_no          INT NOT NULL,
    idempotency_key     VARCHAR(64) NOT NULL UNIQUE,  -- khóa Hub gửi SANG Bank
    request_payload     JSONB,                        -- đã che dữ liệu nhạy cảm
    outcome             VARCHAR(20) NOT NULL,         -- PENDING|SUCCESS|DECLINED|TIMEOUT|ERROR
    bank_txn_id         VARCHAR(64),
    bank_response_code  VARCHAR(32),
    bank_response_body  JSONB,
    started_at          TIMESTAMPTZ NOT NULL,
    finished_at         TIMESTAMPTZ,
    latency_ms          INT,
    UNIQUE (payment_id, attempt_no)
);
```

| Nếu không có bảng này | Hậu quả |
|---|---|
| mất idempotency key gửi sang Bank | timeout ⇒ **không đối soát được** ⇒ payment treo vĩnh viễn |
| Hub sập giữa lúc gọi | tỉnh dậy **không biết mình vừa làm gì** ⇒ mất dấu tiền |
| khách khiếu nại "trừ 2 lần" | **không có bằng chứng** đối chất với Bank |
| chỉnh retry / circuit breaker | **đoán mò**, không có số liệu |

**Ghi TRƯỚC khi gọi** (intent log / write-ahead — cùng họ Outbox & WAL của DB):
```
1. INSERT attempt (outcome='PENDING')  ← COMMIT trước
2. gọi Bank
3. UPDATE chính dòng đó: outcome, bank_txn_id, finished_at
```

⚠️ **Timeout = không biết, KHÔNG phải thất bại** ⇒ giữ `IN_PROGRESS`, job đối soát hỏi lại Bank.
Chi tiết: [bank-call-timeout-and-retry.md](bank-call-timeout-and-retry.md).

---

## 5. `idempotency_records` — quá trình phục vụ merchant (B5)

Bản ghi phải trả lời **ba câu hỏi**:

| # | Câu hỏi | Cột |
|---|---|---|
| ① | *"Khóa này đã thấy chưa?"* | `idempotency_key` + `merchant_id` (UNIQUE) |
| ② | *"Có đúng CÙNG một request không?"* | `request_fingerprint` |
| ③ | *"Lần trước tới đâu, kết quả gì?"* | `state` + `response_status` + `response_body` |

```sql
CREATE TABLE idempotency_records (
    id                  BIGSERIAL PRIMARY KEY,
    merchant_id         BIGINT      NOT NULL REFERENCES merchants(id),
    idempotency_key     VARCHAR(64) NOT NULL,
    endpoint            VARCHAR(80) NOT NULL,      -- 'POST /v1/payments'
    request_fingerprint CHAR(64)    NOT NULL,      -- SHA-256(body chuẩn hóa)
    state               VARCHAR(16) NOT NULL,      -- IN_PROGRESS | COMPLETED
    response_status     INT,
    response_body       JSONB,
    payment_id          BIGINT REFERENCES payments(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ NOT NULL,      -- created_at + 24h
    UNIQUE (merchant_id, idempotency_key)
);
```

### Thuật toán — **INSERT trước, không SELECT trước**

```java
try {
    records.saveAndFlush(new IdempotencyRecord(
            merchantId, key, endpoint, fingerprint, State.IN_PROGRESS));
    // ① thắng → mình là người đầu tiên
} catch (DataIntegrityViolationException dup) {
    // đã có người khác → đọc dòng cũ, rẽ nhánh ②③④
}
```

```
                  request (k1)
                       │
                 INSERT k1  ──thành công──▶ ① xử lý thật → lưu response → 201
                       │
                  UNIQUE nổ
                       │
                  đọc dòng cũ
            ┌──────────┼──────────────┐
      fingerprint    state=          state=
        KHÁC        IN_PROGRESS     COMPLETED
          │              │               │
     ④ 422          ③ 409          ② phát lại response cũ
```

| Ca | Điều kiện | Trả về |
|---|---|---|
| ① | INSERT thành công | xử lý thật, xong thì `state=COMPLETED` + lưu response |
| ② | `COMPLETED` | **phát lại y nguyên** `response_status` + `response_body`, **không chạy lại gì** |
| ③ | `IN_PROGRESS` | **409** `idempotency_key_in_progress` + `Retry-After`. **Không chờ** (giữ connection, dễ deadlock), **không chạy song song** (tạo 2 payment) |
| ④ | fingerprint lệch | **422** `idempotency_key_reuse` — bug/tấn công phía merchant, phải **gào lên** chứ không giấu |

> Ca ③ là chỗ bảng riêng **hơn hẳn** cách Inc 2 (UNIQUE thẳng trên `orders`): Inc 2 chỉ có
> *chưa có dòng* / *đã có dòng hoàn chỉnh*, **không diễn tả được "đang làm dở"**.

### `expires_at` — TTL 24h

Bảng phình nhanh, giá trị chỉ tồn tại vài phút–vài giờ. Job dọn sau 24h.
⚠️ Xóa ⇒ mất lớp chống trùng số 1 ⇒ **vì thế mới cần lớp 2** (partial UNIQUE ở §3).

---

## 6. Vì sao phải lưu `response_body` (B6)

Inc 2 **dựng lại** response từ bảng `orders` (đọc đơn → map DTO). Được, vì response chỉ
phụ thuộc một bảng. Inc 3 **không xài lại mẹo đó được**:

| # | Lý do | Ví dụ |
|---|---|---|
| ① | Response chứa thứ **không nằm trong** `payments` | `checkout_url` = base URL (config) + token. Đổi domain ⇒ dựng lại ra **URL khác** |
| ② | **Trạng thái đã đổi** | lần đầu trả `CREATED`; 20 phút sau retry, payment đã `EXPIRED` ⇒ dựng lại ra kết quả **khác** ⇒ **không còn idempotent** |
| ③ | Có response **không có bản ghi để dựng lại** | lần đầu trả `400 empty_cart` — trong DB không có payment nào |
| ④ | **Rẻ và ổn định** | một cột JSONB, thay vì nhánh replay map DTO mà **gần như không ai test** ⇒ lệch dần |

> **Một câu:** `response_body` là **bản sao công chứng** của câu trả lời đã phát ra.
> Idempotency hứa *"gọi lại thì nhận lại đúng câu cũ"* ⇒ phải **giữ câu cũ**, chứ không
> đọc lại tình hình rồi nói lại.
> Idempotent = **byte-for-byte giống nhau**, không phải "đại khái giống".

---

## 7. Bảng nhớ nhanh — mỗi ý một câu

| # | Ý | Một câu |
|---|---|---|
| B1 | secret HMAC | **hash một chiều ⇒ không tính lại được HMAC** ⇒ phải mã hóa 2 chiều (AES+KMS), không hash |
| B2 | định danh | **một cột định danh, một khán giả** — id (nội bộ) / ref (merchant) / token (Ana) / order_code (của merchant) |
| B3 | chống trùng | **Idempotency-Key = cùng LỜI GỌI · UNIQUE = cùng ĐƠN HÀNG**; UNIQUE phải **partial theo status** |
| B4 | gọi Bank | 1 payment – **n lần gọi** ⇒ bảng riêng, **append-only**, **ghi trước khi gọi** |
| B5 | idempotency record | 3 câu hỏi: **thấy chưa · cùng request không · tới đâu rồi**; 4 ca: 201 / replay / **409** / **422** |
| B6 | response_body | idempotency = **phát lại câu cũ**, không phải **tính lại theo hiện tại** |

---

## 8. Còn treo

- [ ] **B7** — vì sao UNIQUE phải gồm cả `merchant_id`?
- [ ] **B8** — vì sao chọn Postgres thay Redis cho `idempotency_records`? (tên vấn đề?)
- [ ] Đề thiết kế còn lại của Inc 3: **3** (HMAC scheme) · **5** (filter chain order) ·
      **7** (API contract)
