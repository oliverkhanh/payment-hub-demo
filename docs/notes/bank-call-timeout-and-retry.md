# Ghi chú — Gọi Bank: timeout, retry & bảng `payment_attempts`

> Bài học trung tâm của Increment 4 (Hub ↔ Bank), phát hiện khi thiết kế bảng `payments`
> ở Inc 3. Đọc kèm: `docs/specs/02-payment-init-spec.md`,
> `docs/notes/payment-state-machine-walkthrough.md`.

---

## 0. Hai sửa đổi thiết kế phát hiện trong buổi này

| # | Thiết kế cũ (sai/thiếu) | Sửa thành | Vì sao |
|---|---|---|---|
| 1 | `UNIQUE(merchant_id, order_code)` **cứng** | **partial index** theo `status` | UNIQUE cứng chặn luôn việc **thử lại hợp lệ** sau `EXPIRED`/`FAILED` — mà state machine đã chốt "thử lại = payment MỚI" ⇒ Ana không bao giờ trả được đơn đó nữa |
| 2 | State machine ghi *"Bank từ chối / **timeout** → FAILED"* | **timeout KHÔNG dẫn tới FAILED**; ở lại `IN_PROGRESS` | timeout ≠ thất bại (xem §1). Đoán FAILED khi tiền đã trừ ⇒ khách bị trừ **hai lần** |

```sql
-- Sửa #1: chỉ chặn payment ĐANG SỐNG
CREATE UNIQUE INDEX uq_payment_active
  ON payments (merchant_id, order_code)
  WHERE status IN ('CREATED', 'IN_PROGRESS');
```
→ `EXPIRED` / `FAILED` / `CANCELLED` không tính ⇒ tạo lại được.
`CAPTURED` chặn bằng luật nghiệp vụ riêng (đơn đã trả rồi).

---

## 1. Bản chất: timeout **không phải là thất bại**

Khi gọi Bank mà timeout, có **ba** khả năng — và ta **không phân biệt được**:

```
Hub ──request──▶ Bank
  ① request chết giữa đường      → Bank KHÔNG nhận → tiền KHÔNG trừ
  ② Bank nhận, đang xử lý dở     → CHƯA biết
  ③ Bank xử lý XONG, đã trừ tiền,
    response chết giữa đường     → tiền ĐÃ TRỪ
```

Trạng thái này có tên riêng: **in-doubt / indeterminate** (khả nghi).

| | Bank **từ chối** (DECLINED) | Bank **timeout** |
|---|---|---|
| Ta biết gì | biết chắc: **không trừ tiền** | **không biết gì cả** |
| Kết luận được? | ✅ `FAILED` | ❌ chưa |

> **Luật vàng:** chỉ chuyển sang **trạng thái kết thúc** khi có **câu trả lời dứt khoát**
> từ Bank. Không có trả lời ⇒ ở lại trạng thái "đang xử lý".

⇒ `IN_PROGRESS` chính là **ô chứa sự khả nghi**. Đó mới là lý do tồn tại thật sự của nó.

**Hậu quả nếu đoán sai:** timeout → set `FAILED` → Ana thấy "thất bại" → trả lại →
payment mới → Bank trừ lần 2. **Ana mất tiền hai lần, DB không có bằng chứng nào.**

---

## 2. Xử lý timeout — ba tầng

### Tầng 1 — Retry, nhưng phải **idempotent**

Retry mù = cách nhanh nhất để trừ tiền khách hai lần. Muốn retry an toàn, **Hub tự sinh
một khóa cho LẦN GỌI** và gửi kèm; retry thì **gửi lại đúng khóa cũ**:

```
POST /bank/transactions
Idempotency-Key: att_7f3c9a...      ← Hub sinh, GIỮ NGUYÊN khi retry
{ "amount": 355000, "currency": "VND", ... }
```

Bank thấy khóa đã xử lý ⇒ **không trừ lần nữa**, trả lại **đúng kết quả cũ**.
→ Cùng cơ chế Inc 2, nhưng lần này **Hub đứng ở vai client**.

**Retry cái gì, không retry cái gì:**

| Bank trả về | Retry? | Vì sao |
|---|---|---|
| timeout / lỗi mạng / 5xx | ✅ | chưa có kết luận |
| 429 (quá tải) | ✅ theo `Retry-After` | tạm thời |
| `DECLINED` (không đủ tiền, sai thẻ) | ❌ | **đã có kết luận** — retry vô nghĩa |
| 400 (request sai) | ❌ | lỗi của ta, retry vẫn sai |

Chính sách (CLAUDE.md #6 — *timeout + retry + circuit breaker*):
- **exponential backoff + jitter**: 1s → 2s → 4s, cộng ngẫu nhiên
  (tránh cả đàn retry cùng nhịp — *thundering herd*).
- **tối đa 2–3 lần** rồi dừng. Retry vô hạn = tự DDoS Bank đúng lúc nó yếu nhất.
- **circuit breaker**: Bank hỏng liên tục ⇒ ngắt cầu dao, fail nhanh.

### Tầng 2 — Hết retry mà vẫn không rõ ⇒ **KHÔNG đoán**

```
payment.status = IN_PROGRESS                        ← ở nguyên đây
Ana thấy:  "Đang xử lý, chúng tôi sẽ báo kết quả"   ← KHÔNG nói thành công/thất bại
merchant:  chưa nhận webhook, đơn vẫn AWAITING_PAYMENT
```

**Không để người dùng hay merchant "đoán hộ". Thà treo còn hơn sai.**

### Tầng 3 — **Reconciliation** (đối soát): đi HỎI LẠI Bank

Mảnh ghép người mới hay quên. Làm **cả hai** đường:

```
(a) PULL — job nền quét payment IN_PROGRESS quá ~1 phút
    GET /bank/transactions?idempotency_key=att_7f3c9a
      → "SUCCESS"   ⇒ CAPTURED  (Bank đã trừ, chỉ mất response)
      → "DECLINED"  ⇒ FAILED
      → "NOT_FOUND" ⇒ Bank chưa từng nhận ⇒ an toàn để FAILED / gọi lại
      → vẫn timeout ⇒ thử lại lần sau, giãn dần

(b) PUSH — Bank callback khi xong (Inc 5 webhook)
```

**Luôn phải có (a)** vì (b) cũng mất gói được.
Nguyên tắc: *Bank là nguồn sự thật; Hub đi hỏi cho tới khi biết.*
Đến cuối ngày vẫn không rõ → vào **file đối soát cuối ngày** (Inc 10).

---

## 3. Vì sao dữ liệu Bank **không** nằm trên `payments`

Cám dỗ đầu tiên:

```sql
ALTER TABLE payments ADD COLUMN bank_txn_id    VARCHAR;  -- ❌
ALTER TABLE payments ADD COLUMN bank_resp_code VARCHAR;  -- ❌
```

Kịch bản làm vỡ nó:

```
14:00:00  Hub → Bank: thu 355.000₫
14:00:30  timeout, không biết Bank có nhận không
14:00:35  retry → "TIMEOUT, thử lại sau"
14:00:50  retry → "OK, txn_id=BNK-77"
```

Quan hệ thực tế là **1–n**: một payment có **nhiều lần gọi Bank**.

### Bốn lý do phải lưu từng lần gọi

**① Không lưu khóa ⇒ payment treo VĨNH VIỄN.**
Timeout = không có response ⇒ **không có `bank_txn_id`**. Thứ duy nhất nối Hub với giao
dịch bên Bank là **khóa do chính Hub sinh trước khi gọi**. Vứt đi ⇒ không đối soát được
⇒ mãi mãi `IN_PROGRESS`, không ai biết Ana có bị trừ tiền không.
→ Bảng attempts trước hết là **sổ ghi nhớ "tôi đã gọi cái gì"**, chứ không phải sổ kết quả.

**② Ghi TRƯỚC khi gọi — chống mất trí nhớ khi Hub chết.**
```
1. INSERT payment_attempts (outcome='PENDING', key=att_7f3c9a)   ← COMMIT trước
2. gọi Bank
3. UPDATE dòng đó: outcome, bank_txn_id, finished_at
```
Hub sập giữa bước 2 (deploy, OOM, mất điện) → khởi động lại, job thấy dòng `PENDING`
treo ⇒ biết *"có một lệnh có thể đang bay"* ⇒ đi hỏi Bank.
Không có bảng này ⇒ Hub tỉnh dậy **không biết mình vừa làm gì**; tiền có thể đã trừ.
→ Kỹ thuật: **intent log / write-ahead** — *ghi ý định trước khi hành động*.
Cùng họ với **Outbox** (CLAUDE.md #2) và **WAL** của DB.

**③ Khiếu nại — bằng chứng để đối chất với Bank.**

| attempt | key | outcome | bank_txn_id | lúc |
|---|---|---|---|---|
| 1 | att_7f3c | TIMEOUT | — | 14:00:00 |
| 2 | att_7f3c | TIMEOUT | — | 14:00:35 |
| 3 | att_7f3c | SUCCESS | BNK-77 | 14:00:50 |

⇒ *"Cả 3 lần cùng khóa `att_7f3c`. Nếu trừ 2 lần thì lỗi dedupe bên các anh."*
Một cột bị UPDATE đè 3 lần ⇒ chỉ còn `BNK-77` ⇒ **cãi nhau bằng trí nhớ**.
→ CLAUDE.md #7: audit **append-only**. UPDATE đè lên chính nó là **phản** append-only.

**④ Vận hành (Inc 9, 11).** Tỉ lệ timeout theo giờ · `latency_ms` p95 · phân bố
`bank_response_code` · chọn ngưỡng circuit breaker từ **số liệu thật** thay vì đoán mò.

### Schema

```sql
CREATE TABLE payment_attempts (
    id                  BIGSERIAL PRIMARY KEY,
    payment_id          BIGINT NOT NULL REFERENCES payments(id),

    attempt_no          INT NOT NULL,                  -- 1, 2, 3
    idempotency_key     VARCHAR(64) NOT NULL UNIQUE,   -- khóa Hub gửi SANG Bank
    request_payload     JSONB,                         -- đã che dữ liệu nhạy cảm

    outcome             VARCHAR(20) NOT NULL,          -- PENDING|SUCCESS|DECLINED|TIMEOUT|ERROR
    bank_txn_id         VARCHAR(64),                   -- NULL nếu timeout
    bank_response_code  VARCHAR(32),
    bank_response_body  JSONB,

    started_at          TIMESTAMPTZ NOT NULL,
    finished_at         TIMESTAMPTZ,
    latency_ms          INT,

    UNIQUE (payment_id, attempt_no)
);
```

**Append-only** — mỗi lần gọi ghi **một dòng mới** (dòng đó chỉ được cập nhật *kết quả của
chính nó*), không bao giờ đè dòng của lần gọi khác.

Cái gì **vẫn ở lại** `payments`? Chỉ **kết luận**, không phải quá trình:

```sql
payments.status            -- CAPTURED / FAILED / IN_PROGRESS
payments.bank_txn_id       -- (phi chuẩn hóa) mã của lần THÀNH CÔNG, tra cứu nhanh
payments.attempt_count     -- job biết đã thử mấy lần
payments.last_attempt_at
```

> **Trade-off có ý thức:** `bank_txn_id` lặp ở hai nơi (**phi chuẩn hóa**). Đổi lại: màn
> hình chi tiết payment không phải join sang attempts. Chấp nhận được vì nó chỉ được ghi
> **một lần duy nhất** lúc thành công.

---

## 4. Sơ đồ tổng

```
       Hub gọi Bank
            │
    ┌───────┼────────────────┬──────────────────┐
  SUCCESS  DECLINED      TIMEOUT / 5xx        400
    │        │                │                │
 CAPTURED  FAILED     retry ≤3 (backoff+jitter, FAILED
                       CÙNG idempotency-key)    (lỗi ta)
                              │
                       vẫn không rõ
                              │
                      ★ IN_PROGRESS ★   ← treo, KHÔNG đoán
                              │
                    job đối soát hỏi lại Bank
                       ┌──────┴──────┐
                   CAPTURED       FAILED
```

---

## 5. Ba câu chốt

1. **Timeout = không biết, không phải thất bại.**
2. **Muốn retry an toàn thì phải idempotent — người đảm bảo là *bên nhận*, nên ta phải
   gửi khóa đi** (và vì thế phải lưu khóa lại).
3. **Không biết thì đi hỏi (reconciliation), tuyệt đối không đoán.**

> `payments` lưu **kết luận**; `payment_attempts` lưu **quá trình**.
> Khi tiền dính vào, **quá trình cũng là dữ liệu nghiệp vụ**, không phải log vứt đi được.

Cùng nguyên tắc với Inc 2: lưu `order_items` snapshot giá thay vì join sang `products` —
*chuyện đã xảy ra như thế nào* là sự thật độc lập, không được suy diễn lại từ hiện tại.

---

## 6. Checklist khi implement (Inc 4)

- [ ] `INSERT` attempt (`PENDING`) và **COMMIT** *trước* khi gọi Bank.
- [ ] Gửi `Idempotency-Key` sang Bank; **retry giữ nguyên khóa**.
- [ ] Phân loại response: chỉ retry nhóm **không kết luận** (timeout/5xx/429).
- [ ] Timeout cạn retry ⇒ **giữ `IN_PROGRESS`**, không set `FAILED`.
- [ ] Job đối soát quét `IN_PROGRESS` quá hạn, hỏi lại Bank, giãn dần khoảng cách.
- [ ] Circuit breaker + timeout cho mọi external call (CLAUDE.md #6).
- [ ] UI/webhook **không** báo thành công/thất bại khi còn `IN_PROGRESS`.
- [ ] Không log PAN/CVV trong `request_payload` (CLAUDE.md #5).
