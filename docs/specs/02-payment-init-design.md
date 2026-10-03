# Thiết kế kỹ thuật — Increment 3 (Payment Init)

> Bản thiết kế đã **chốt**, dùng làm nguồn đối chiếu khi code.
> Nghiệp vụ (cái gì / vì sao) ở [02-payment-init-spec.md](02-payment-init-spec.md).
> Kiến thức nền: [hmac-request-signing.md](../notes/hmac-request-signing.md) ·
> [inc3-data-model.md](../notes/inc3-data-model.md) ·
> [bank-call-timeout-and-retry.md](../notes/bank-call-timeout-and-retry.md)

---

## PHẦN 0 — Tổng quan

### 0.1 Endpoint

| Method | Path | Client | Chuỗi filter | Mục đích |
|---|---|---|---|---|
| POST | `/v1/payments` | merchant server | `/v1/**` (HMAC) | tạo lệnh thanh toán |
| GET | `/v1/payments/{payment_ref}` | merchant server | `/v1/**` (HMAC) | tra trạng thái |
| GET | `/checkout/{checkout_token}` | trình duyệt Ana | `/checkout/**` | trang thanh toán (HTML) |
| POST | `/checkout/{checkout_token}/cancel` | trình duyệt Ana | `/checkout/**` | Ana hủy, về merchant |

> **Nguyên tắc:** đường dẫn phải khớp biên xác thực. Nhìn prefix là biết ai được gọi.
> `/v1/**` = server-to-server (HMAC) · `/checkout/**` = trình duyệt (capability token).

### 0.2 State machine (đã chốt, bản demo)

```
              merchant gọi API
                     ↓
                ╔ CREATED ╗
   Ana nhập thẻ ↙    │ job Hub quét   ↘ Ana bấm quay lại
                     │
  IN_PROGRESS     EXPIRED            CANCELLED
   ↙        ↘
CAPTURED   FAILED          ← terminal; thử lại = payment MỚI
```

- **Inc 3 chạm:** `CREATED` · `EXPIRED` · `CANCELLED`
- **Inc 4 thêm:** `IN_PROGRESS` · `CAPTURED` · `FAILED`
- Enum giữ nhưng chưa dùng: `AUTHORIZED` · `VOIDED` · `REFUNDED` (Inc 6)
- ⚠️ **Bank timeout KHÔNG dẫn tới `FAILED`** — giữ `IN_PROGRESS`, job đối soát hỏi lại.
- Job quét hết hạn nằm **trong Hub** (Hub sở hữu payment ⇒ sở hữu vòng đời của nó).

> **Trade-off:** production tách `AUTHORIZED`/`CAPTURED`/`VOIDED` vì luật card network cấm
> capture trước khi giao hàng. Demo gộp auth+capture (mô hình *sale*, như hàng số giao ngay)
> vì không có kho và không có vận chuyển.

### 0.3 Chuỗi filter

```
┌─ /v1/** ────────────────────────────────────────────┐
│ G   trace-id vào MDC          (OpenTelemetry tự làm) │
│ D   cache raw body            ← BẮT BUỘC đầu tiên    │
│ E1  rate limit theo IP        ← chưa biết là ai       │
│ F   tra merchant (DB) + kiểm ACTIVE                  │
│ C   kiểm cửa sổ 5 phút        ← rẻ, làm trước         │
│ B   verify HMAC               ← đắt                   │
│     → SecurityContextHolder.setAuthentication(...)    │
│ E2  quota theo merchant       ← giờ mới biết chắc     │
└──────────────────────────────────────────────────────┘
        ↓  A   Spring MVC parse + @Valid
        ↓  H   @Idempotent (AOP quanh controller)
        ↓  I   service: tạo payment, sinh checkout_token
```

Ba nguyên tắc: **rẻ trước – đắt sau** · **mọi thứ chạm DB nằm sau rate limit** ·
**hạn mức chỉ trừ vào danh tính đã chứng minh**.

---

## PHẦN 1 — KHUÔN MẪU: `POST /v1/payments`

> Đây là **bản mẫu đầy đủ 6 mục**. Ba endpoint còn lại viết theo **đúng 6 mục này**.

### 1.1 Mục đích & ai gọi

Merchant server tạo một lệnh thanh toán cho một đơn hàng, nhận về `checkout_url` để
chuyển hướng khách. Gọi **server-to-server**, không bao giờ từ trình duyệt.

### 1.2 Request

```http
POST /v1/payments
Content-Type:        application/json
X-Merchant-Key:      mk_dev_demo
X-Timestamp:         1758892800
X-Signature:         4a7f2b9c...
X-Signature-Version: v1
Idempotency-Key:     6f1c2e9a-1111-2222-3333-444455556666
```

```json
{
  "order_code":  "ord_8222",
  "amount":      355000,
  "currency":    "VND",
  "return_url":  "https://shop.example.com/orders/ord_8222/result",
  "description": "Chợ Phiên - 3 sản phẩm",
  "expires_in_seconds": 900,
  "metadata":    { "cart_id": "c_771" }
}
```

| Field | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `order_code` | string | ✅ | ≤64 ký tự; **của merchant**, Hub không sinh |
| `amount` | integer (long) | ✅ | > 0, **minor unit** (355000 = 355.000₫). Không float |
| `currency` | string | ✅ | 3 ký tự, ISO 4217, IN HOA |
| `return_url` | string | ✅ | HTTPS, domain phải thuộc whitelist của merchant |
| `description` | string | ➖ | ≤255; hiện trên trang checkout |
| `expires_in_seconds` | integer | ➖ | 300–3600, mặc định **900** |
| `metadata` | object | ➖ | ≤8 khóa, Hub lưu & trả lại nguyên xi, không xử lý |

**KHÔNG nhận từ client:** `payment_ref` · `checkout_token` · `status` · `created_at` ·
`merchant_id` *(lấy từ `SecurityContextHolder`, không lấy từ body — nếu lấy từ body thì
merchant A khai là B được)*.

> **client gửi ý định, không gửi kết quả.** Mọi thứ Hub tự suy ra được thì không nhận từ client.

### 1.3 Response `201 Created`

```http
HTTP/1.1 201 Created
Location: /v1/payments/pay_9f3a7c1b2d
Content-Type: application/json
```
```json
{
  "payment_ref":  "pay_9f3a7c1b2d",
  "status":       "CREATED",
  "order_code":   "ord_8222",
  "amount":       355000,
  "currency":     "VND",
  "checkout_url": "https://hub.local/checkout/ctk_5e8b4f...",
  "expires_at":   "2026-09-26T14:15:00Z",
  "created_at":   "2026-09-26T14:00:00Z",
  "metadata":     { "cart_id": "c_771" }
}
```

**Không trả:** `id` (khóa nội bộ) · `checkout_token` (đã nằm trong URL) · `merchant_id`.

Quy ước:
- `checkout_url` là **opaque** — merchant dùng nguyên xi, **không** phân tích, **không** dựng lại.
- Mọi timestamp là **ISO-8601 UTC có `Z`**.
- **Echo lại** `order_code`/`amount`/`currency` để merchant phát hiện ngay lỗi hiểu sai
  đơn vị tiền (gửi `3550.00` → Hub đọc `3550`).

### 1.4 Replay (idempotent)

```http
HTTP/1.1 200 OK
Idempotent-Replay: true
```
Body = **nguyên văn** `response_body` đã lưu, không dựng lại từ DB
(sau 16 phút payment đã `EXPIRED`, dựng lại sẽ ra kết quả khác ⇒ mất tính idempotent).

### 1.5 Lỗi

| Ca | Status | `error_code` |
|---|---|---|
| thiếu / sai `X-Signature`; merchant key không tồn tại | 401 | `invalid_signature` |
| `X-Timestamp` lệch > 5 phút | 401 | `request_expired` |
| merchant `SUSPENDED` | 403 | `merchant_suspended` |
| vượt rate limit | 429 | `rate_limit_exceeded` |
| thiếu header `Idempotency-Key` | 400 | `idempotency_key_required` |
| `amount ≤ 0` | 422 | `invalid_amount` |
| `currency` sai ISO 4217 | 422 | `invalid_currency` |
| `return_url` không thuộc whitelist | 422 | `invalid_return_url` |
| cùng key, **body khác** | 422 | `idempotency_key_reuse` |
| cùng key, **đang xử lý** | 409 | `idempotency_key_in_progress` |
| đơn đã có payment **đang sống** | 409 | `payment_already_exists` |
| đơn đã `CAPTURED` | 409 | `order_already_paid` |

Ghi chú:
- 401 dùng **một** `error_code` cho mọi nguyên nhân — không tiết lộ key nào có thật.
- **400** = *không đọc hiểu nổi request*; **422** = *đọc hiểu rồi, giá trị sai luật*;
  **409** = *request hợp lệ, xung đột trạng thái* (⇒ retry có ý nghĩa).
- 409 kèm `existing_payment_ref` + `existing_status` để merchant tự xử.

### 1.6 Edge case & bất biến

| Tình huống | Hành vi |
|---|---|
| 2 request cùng key **đồng thời** | 1×`201` + 1×`409`. **Không bao giờ** 2×201, không 500 |
| retry sau khi payment cũ `EXPIRED`/`FAILED`/`CANCELLED` (key mới) | `201`, payment **mới**, `payment_ref` mới |
| `expires_in_seconds` không gửi | dùng 900 |
| `metadata` gửi khóa lạ | lưu nguyên xi, không validate nội dung |

**Bất biến phải luôn đúng:**
1. Một `(merchant_id, order_code)` có **tối đa 1** payment ở `CREATED`/`IN_PROGRESS`
   (partial unique index).
2. Một `(merchant_id, Idempotency-Key)` sinh ra **tối đa 1** payment.
3. Không request nào chạm DB nghiệp vụ trước khi HMAC được verify.

---

## PHẦN 2 — BÀI TẬP: ba endpoint còn lại

> Viết theo **đúng 6 mục** của Phần 1. Không cần văn vẻ, cần **đủ mục và đúng nguyên tắc**.
> Nguyên tắc để tự kiểm: định danh đúng khán giả · không tin client · lỗi phải phân biệt
> 400/422/409 · trạng thái chuyển phải hợp lệ · nghĩ ca đồng thời.

### 2.1 `GET /v1/payments/{payment_ref}`  ← làm cái này trước (dễ nhất)

Gợi ý những chỗ phải quyết:
- Chuỗi ký của request **không có body** thì dòng nào rỗng, dòng nào là hằng số?
- Merchant A tra `payment_ref` của merchant B ⇒ trả `404` hay `403`? **Vì sao?**
- Có cần `Idempotency-Key` không? Vì sao?
- Trả những field nào — giống `201` hay khác?

### 2.2 `GET /checkout/{checkout_token}`

Gợi ý:
- Đây là **HTML**, không phải JSON ⇒ mục 1.5 (bảng lỗi) trả về cái gì?
- Token: không tồn tại / hết hạn / payment đã `CANCELLED` / đã `CAPTURED` — **4 ca, 4 trang khác nhau?**
- Trang này hiện những thông tin gì của payment? Cái gì **tuyệt đối không** được hiện?
- Ana refresh 10 lần thì sao? (bất biến gì phải giữ)

### 2.3 `POST /checkout/{checkout_token}/cancel`

Gợi ý:
- Chuyển trạng thái nào → nào? Từ trạng thái nào thì **không** cho hủy?
- Ana bấm hủy 2 lần (double-click) ⇒ hành vi? Có cần idempotency không, và **cơ chế nào** ở đây?
- Sau khi hủy, Hub đưa Ana đi đâu — và **lấy URL đó từ đâu**? (bẫy bảo mật nào?)
- Response là JSON hay redirect `302`?

---

## PHẦN 3 — Còn treo

- [ ] Trang HTML checkout: chọn Thymeleaf (server-rendered) theo CLAUDE.md §3.
- [ ] Job quét hết hạn: chu kỳ, cách tránh 2 instance quét trùng (Inc 3 chạy 1 instance —
      ghi chú trade-off; production dùng lock/leader election).
- [ ] Automated test (DoD): signature (đúng/sai/thiếu/quá hạn/body sửa 1 byte),
      idempotency (replay + **đồng thời**), partial unique, expiry job.
