# Increment 4 — Luồng nghiệp vụ Hub ↔ Bank *(chốt D2)*

> Sơ đồ tuần tự đầy đủ cho 6 luồng. Đọc kèm:
> [03-bank-capture-spec.md](03-bank-capture-spec.md) ·
> [bank-call-timeout-and-retry.md](../notes/bank-call-timeout-and-retry.md)
>
> **Quy ước đọc:** `⬛` = thời điểm payment ĐỔI TRẠNG THÁI · `🔒` = ghi DB ·
> `⚡` = chỗ có thể hỏng.

---

## 0. Bốn nguyên tắc chi phối mọi luồng

**① Hai đường về, độc lập với nhau.**

```
Ana xác nhận ở Bank
   ├──[A] Bank ──callback (server→server, ký HMAC)──▶ Hub    ⬛ ĐỔI TRẠNG THÁI
   └──[B] Bank ──redirect (trình duyệt)──▶ Hub /result        👁 chỉ HIỂN THỊ
```

- **[A] là nguồn sự thật.** Nó đi server-to-server, có chữ ký, Bank retry được nếu trượt.
- **[B] chỉ để đưa người dùng về.** Ana đóng tab là mất, và **Ana sửa URL được**.

> **Không bao giờ đổi trạng thái theo [B].** Tin trình duyệt = Ana gõ
> `/result?status=success` là hàng được giao miễn phí.

**② Hai đường KHÔNG đảm bảo thứ tự.** Callback thường tới **trước** khi Ana kịp quay về
(server nhanh hơn người). Cả hai thứ tự đều phải chạy đúng.

**③ Ghi ý định TRƯỚC khi gọi** (BR-B5). Hub sập giữa cú gọi vẫn còn dấu vết để lần ra.

**④ Timeout = KHÔNG BIẾT.** Không có câu trả lời dứt khoát ⇒ **không kết luận**
(BR-B1, BR-B2).

---

## 1. Máy trạng thái (cập nhật ở Inc 4)

```
                  ╔ CREATED ╗
   Ana bấm trả ↙       │ job quét      ↘ Ana bấm quay lại (ở Hub)
                       │
   IN_PROGRESS      EXPIRED            CANCELLED
    ↙    ↓    ↘
CAPTURED │  CANCELLED        ← MỚI ở Inc 4: Ana bấm Hủy trên trang Bank
       FAILED
```

| Từ | Sang | Ai kích hoạt |
|---|---|---|
| `CREATED` | `IN_PROGRESS` | Ana bấm thanh toán (Hub, **trước** khi gọi Bank) |
| `CREATED` | `EXPIRED` | job quét của Hub |
| `CREATED` | `CANCELLED` | Ana bấm "quay lại" trên trang checkout của Hub |
| `IN_PROGRESS` | `CAPTURED` | **callback của Bank** (hoặc job đối soát) |
| `IN_PROGRESS` | `FAILED` | **callback của Bank** (hoặc job đối soát) |
| `IN_PROGRESS` | `CANCELLED` | **callback của Bank** — Ana bấm Hủy ở trang Bank |

### Vì sao thêm `IN_PROGRESS → CANCELLED`

Ana hủy ở trang Bank là **câu trả lời DỨT KHOÁT** (Bank khẳng định không trừ tiền) —
khác hẳn timeout. Và `CANCELLED` mang **nghĩa nghiệp vụ khác** `FAILED`:

```
CANCELLED = khách ĐỔI Ý          → nhắc khách quay lại thì có ích
FAILED    = bị TỪ CHỐI (hết tiền, sai thông tin) → nhắc lại cũng vô ích
```

Gộp vào `FAILED` là mất thông tin mà merchant cần cho phân tích tỉ lệ rơi.

---

## 2. Luồng 1 — THÀNH CÔNG

```
Ana                 Hub                      Hub DB              Bank
 │                   │                         │                  │
 │ GET /checkout/{token}                       │                  │
 │──────────────────▶│                         │                  │
 │◀── trang: số tiền + chọn ngân hàng ─────────│                  │
 │                   │                         │                  │
 │ POST /checkout/{token}/pay                  │                  │
 │   { bank_code: "VCB" }                      │                  │
 │──────────────────▶│                         │                  │
 │                   │ ① validate bank_code (BR-B13)              │
 │                   │ ② kiểm payment còn CREATED & chưa hết hạn  │
 │                   │                         │                  │
 │                   │──🔒 INSERT payment_attempt(PENDING) ──▶     │
 │                   │──⬛ payment: CREATED → IN_PROGRESS ──▶      │
 │                   │     (ghi bank_code)     │                  │
 │                   │                         │                  │
 │                   │── POST /banks/VCB/v1/transactions ────────▶│  ⚡ TIMEOUT Ở ĐÂY
 │                   │   X-Hub-Key, X-Signature (khoá BANK cấp)   │
 │                   │   Idempotency-Key: att_7f3c                │
 │                   │   { amount, currency, callback_url,        │
 │                   │     return_url, merchant_name }            │
 │                   │                         │       🔒 Bank tạo giao dịch
 │                   │◀── 201 { bank_txn_id, redirect_url } ──────│
 │                   │                         │                  │
 │                   │──🔒 UPDATE attempt: SUCCESS, bank_txn_id ─▶ │
 │                   │                         │                  │
 │◀── 302 redirect_url ──────────────────────────────────────────│
 │                                                                │
 │ GET /banks/VCB/checkout/{bank_txn}                             │
 │───────────────────────────────────────────────────────────────▶│
 │◀── trang Bank: tên bank, merchant, số tiền, chọn tài khoản ────│
 │                                                                │
 │ POST chọn ACC-001 + [Thành công]                               │
 │───────────────────────────────────────────────────────────────▶│
 │                   │                         │   🔒 kiểm số dư, TRỪ TIỀN
 │                   │                         │   🔒 txn: SUCCESS
 │                   │                         │                  │
 │         ┌─────────┴── [A] server→server ────┴──────────────────┤
 │         │ POST /v1/bank-callbacks                              │
 │         │ X-Bank-Signature (khoá HUB cấp)  ← KHÁC khoá chiều đi│
 │         │ { bank_txn_id, status: SUCCESS, amount, signed_at }  │
 │         ▼                                                      │
 │        Hub: ① verify chữ ký  ⚠️ TRƯỚC KHI CHẠM NGHIỆP VỤ       │
 │             ② chống trùng (BR-B12)                             │
 │             ③ kiểm số tiền khớp                                │
 │             ④⬛ payment: IN_PROGRESS → CAPTURED                 │
 │             ⑤🔒 ghi bank_txn_id lên payments                    │
 │        ◀── 200 OK ────────────────────────────────────────────▶│
 │                                                                │
 │◀── [B] 302 về Hub /checkout/{token}/result ───────────────────│
 │                   │                                            │
 │ GET /checkout/{token}/result                                   │
 │──────────────────▶│ ĐỌC trạng thái từ DB (KHÔNG tin query param)│
 │◀── "Thanh toán thành công" + nút về cửa hàng ──│               │
 │                   │                                            │
 │◀── 302 return_url?payment_ref=...&status=CAPTURED ─────────────│
```

### Ba điểm cốt lõi của luồng này

**① `CREATED → IN_PROGRESS` xảy ra TRƯỚC khi gọi Bank.**

Vì nếu đổi trạng thái **sau** khi có response, thì khi timeout Hub sẽ không có gì trong DB
nói rằng *"có một lệnh đang bay"*. Đổi trước = **ghi ý định trước khi hành động**
(cùng họ với Outbox và WAL của DB).

Hệ quả phụ nhưng quan trọng: `IN_PROGRESS` nằm trong partial index `uq_payment_active`
⇒ ngay khi Ana bấm thanh toán, **không ai tạo được payment thứ hai cho đơn đó**.

**② `IN_PROGRESS → CAPTURED` do CALLBACK [A], không phải do Ana quay về [B].**

| | [A] callback | [B] Ana quay về |
|---|---|---|
| Có chữ ký của Bank | ✅ | ❌ |
| Ana sửa được không | ❌ | ✅ **sửa URL là xong** |
| Có xảy ra khi Ana đóng tab | ✅ | ❌ |
| Bank retry được nếu trượt | ✅ | ❌ |

**③ Trang `/result` ĐỌC trạng thái từ DB.** Không đọc query param. Ana gõ
`?status=CAPTURED` cũng vô ích — Hub nhìn `payments.status`.

---

## 3. Luồng 2 — ANA HỦY Ở TRANG BANK

Giống luồng 1 tới bước Ana ở trang Bank, rồi:

```
 │ POST [Hủy]                                                     │
 │───────────────────────────────────────────────────────────────▶│
 │                                         🔒 txn: CANCELLED (KHÔNG trừ tiền)
 │         ┌── [A] POST /v1/bank-callbacks { status: CANCELLED } ──┤
 │         ▼                                                       │
 │        Hub: verify → chống trùng                                │
 │             ⬛ payment: IN_PROGRESS → CANCELLED                  │
 │                                                                 │
 │◀── [B] 302 về Hub /result ─────────────────────────────────────│
 │                   │                                             │
 │◀── "Bạn đã hủy giao dịch" + nút về cửa hàng ──│                │
 │◀── 302 return_url?status=CANCELLED ───────────│                │
```

**Hệ quả:** `CANCELLED` là **terminal**. Ana muốn trả lại ⇒ **payment MỚI**
(link checkout cũ đã chết). Merchant gọi lại `POST /v1/orders/{code}/payment`, và vì
payment cũ không còn "đang sống" nên partial index cho tạo mới — **đúng cơ chế đã làm ở Inc 3**.

### Phân biệt với "Ana bấm quay lại ở trang Hub"

| | Hủy ở **Hub** (Inc 3) | Hủy ở **Bank** (Inc 4) |
|---|---|---|
| Payment đang ở | `CREATED` | `IN_PROGRESS` |
| Bank có biết không | **chưa gọi Bank** | Bank đã tạo giao dịch, giờ hủy nó |
| Ai báo Hub | chính Hub | **callback của Bank** |

---

## 4. Luồng 3 — BANK TIMEOUT ⭐ (luồng quan trọng nhất)

```
 │ POST /checkout/{token}/pay { bank_code: "TCB" }                 │
 │──────────────────▶│                                             │
 │                   │──🔒 INSERT attempt #1 (PENDING, key=att_7f3c)│
 │                   │──⬛ payment → IN_PROGRESS                     │
 │                   │                                             │
 │                   │── POST /banks/TCB/v1/transactions ─────────▶│
 │                   │   Idempotency-Key: att_7f3c                 │
 │                   │        ⏳ 8 giây… im lặng                    │
 │                   │── ⚡ TIMEOUT ──                              │
 │                   │──🔒 UPDATE attempt #1: outcome = TIMEOUT ──▶ │
 │                   │                                             │
 │                   │  ── chờ backoff ~200ms + jitter ──          │
 │                   │──🔒 INSERT attempt #2 (PENDING)             │
 │                   │── POST … Idempotency-Key: att_7f3c ────────▶│  ⚠️ KHOÁ CŨ
 │                   │── ⚡ TIMEOUT ──                              │
 │                   │                                             │
 │                   │  ── chờ backoff ~400ms + jitter ──          │
 │                   │──🔒 INSERT attempt #3 (PENDING)             │
 │                   │── POST … Idempotency-Key: att_7f3c ────────▶│  ⚠️ VẪN KHOÁ CŨ
 │                   │── ⚡ TIMEOUT ──                              │
 │                   │                                             │
 │                   │  HẾT RETRY. KHÔNG KẾT LUẬN GÌ.              │
 │                   │  payment: VẪN IN_PROGRESS  ⬛ (không đổi)    │
 │                   │                                             │
 │◀── 302 /checkout/{token}/result ──│                             │
 │◀── "Đang xử lý, chúng tôi sẽ báo kết quả" ──│                   │
 │      ⚠️ KHÔNG nói thành công, KHÔNG nói thất bại                 │
```

### Vì sao retry giữ NGUYÊN `Idempotency-Key`

Timeout có 3 khả năng, không phân biệt được:

```
① request chết giữa đường  → Bank không nhận  → tiền KHÔNG trừ
② Bank đang xử lý dở        → chưa biết
③ Bank XONG rồi, response chết → tiền ĐÃ TRỪ
```

Nếu retry mà **sinh khoá mới**, ở ca ③ Bank sẽ coi đó là giao dịch **thứ hai**
⇒ **trừ tiền Ana hai lần**. Giữ nguyên khoá ⇒ Bank nhận ra đã xử lý ⇒ trả lại kết quả cũ.

> **Người đảm bảo idempotency là BÊN NHẬN — nên ta phải gửi khoá đi.**

### Vì sao ghi attempt TRƯỚC mỗi lần gọi

Hub sập giữa bước gọi ⇒ khởi động lại, job quét thấy dòng `PENDING` treo ⇒ biết *"có lệnh
có thể đang bay"* ⇒ đi hỏi Bank. Không ghi trước ⇒ Hub **mất trí nhớ**, tiền có thể đã trừ
mà không ai hay.

---

## 5. Luồng 3b — ĐỐI SOÁT cứu payment treo

```
 ┌── Job chạy mỗi 60 giây ─────────────────────────────────────┐
 │                                                             │
 │ SELECT payment WHERE status = IN_PROGRESS                   │
 │                  AND last_attempt_at < now() - 1 phút       │
 │                                                             │
 │ Với mỗi payment: lấy idempotency_key từ payment_attempts    │
 │                                                             │
 │ ── GET /banks/{bank}/v1/transactions?idempotency_key=att_7f3c ──▶ Bank
 │                                                             │
 │ Bank trả về:                                                │
 │   "SUCCESS"   → ⬛ CAPTURED   + 🔒 ghi bank_txn_id            │
 │   "DECLINED"  → ⬛ FAILED                                    │
 │   "CANCELLED" → ⬛ CANCELLED                                 │
 │   "NOT_FOUND" → Bank CHƯA TỪNG NHẬN ⇒ an toàn:              │
 │                 ⬛ FAILED  (hoặc gọi lại — xem dưới)          │
 │   vẫn timeout → giữ IN_PROGRESS, giãn lần hỏi sau           │
 └─────────────────────────────────────────────────────────────┘
```

### Lịch hỏi lại (giãn dần)

```
1 phút → 2 → 5 → 15 → 30 → 60 phút → mỗi 2 giờ
```

Không hỏi dày, vì Bank đang yếu thì dội thêm chỉ làm nó yếu hơn.

### Bao lâu thì bỏ cuộc

**Sau 24 giờ** vẫn không rõ ⇒ **không tự quyết**. Chuyển sang:
- gắn cờ `NEEDS_MANUAL_REVIEW` (cờ vận hành, **không phải** trạng thái payment),
- đưa vào **file đối soát cuối ngày** (Inc 10),
- cảnh báo cho vận hành.

> **Khi dính tới tiền, "treo và báo người" luôn đúng hơn "tự đoán".**

### `NOT_FOUND` — phải cẩn thận

`NOT_FOUND` nghĩa là *Bank không có giao dịch với khoá này* ⇒ ca ① (request chết giữa
đường) ⇒ **an toàn để kết luận `FAILED`**.

⚠️ Nhưng chỉ đúng nếu Bank đảm bảo: *"tôi ghi giao dịch NGAY khi nhận, trước khi xử lý"*.
Nếu Bank ghi **sau** khi trừ tiền thì có cửa sổ mà `NOT_FOUND` **là sai**. → **Đây là điều
khoản phải nằm trong hợp đồng API với Bank** (đưa vào D7).

---

## 6. Luồng 4 — CALLBACK TỚI TRƯỚC KHI ANA QUAY VỀ

Đây là ca **thường gặp nhất**, không phải ngoại lệ: server nhanh hơn người.

```
t0      Ana bấm [Thành công] ở Bank
t0+50ms [A] callback tới Hub  → ⬛ payment = CAPTURED
t0+900ms[B] Ana quay về /result → đọc DB thấy CAPTURED → hiện "Thành công" ✅
```

**Không cần xử lý gì đặc biệt** — vì `/result` **đọc DB**, không đọc query param. Trạng thái
đã đúng sẵn.

### Ngược lại: Ana về TRƯỚC khi callback tới

```
t0      Ana bấm [Thành công]
t0+200ms[B] Ana về /result → DB vẫn IN_PROGRESS → hiện "Đang xử lý…" 
t0+1.5s [A] callback tới   → ⬛ CAPTURED
```

Trang `/result` cho payment `IN_PROGRESS` phải:
- hiện **"Đang xử lý"**, tuyệt đối không đoán,
- **tự làm mới** sau vài giây (meta refresh hoặc polling 2s, tối đa ~30s),
- sau đó vẫn chưa rõ ⇒ *"Chúng tôi sẽ báo kết quả qua email/đơn hàng"* + nút về cửa hàng.

> Ana **không cần** ngồi chờ. Kết quả được chốt bằng [A] + đối soát, không phụ thuộc
> trình duyệt còn mở hay không (BR-B12).

---

## 7. Luồng 5 — CALLBACK TRÙNG

Bank không nhận được `200` ⇒ Bank retry callback. Hoặc kẻ tấn công phát lại.

```
lần 1: verify ✅ → chưa xử lý → ⬛ CAPTURED → ghi vào sổ callback → 200
lần 2: verify ✅ → ĐÃ xử lý  → KHÔNG đổi gì → 200          ⚠️ vẫn trả 200
lần 3-5: như lần 2
```

**Vẫn phải trả `200`.** Trả lỗi thì Bank tưởng chưa tới nơi và retry mãi.

Chống trùng bằng **hai lớp** (giống hệt Inc 3):

```
Lớp 1: UNIQUE trên (bank_code, bank_txn_id, status)  → chốt chặn atomic ở DB
Lớp 2: transition guard của state machine            → CAPTURED không đi tiếp đâu được
```

Lớp 2 tự nó đã chặn: payment đã `CAPTURED` là **terminal**, `canTransitionTo` trả `false`.

### Callback cho payment đã ở trạng thái kết thúc

```
payment = EXPIRED (job quét đã chạy), rồi callback SUCCESS mới tới
```

**Không đổi trạng thái** (BR-B8), nhưng **phải ghi log ở mức cảnh báo** — đây là dấu hiệu
tiền đã bị trừ mà hệ thống coi là hết hạn ⇒ **cần người xem lại** và có thể phải hoàn tiền.

> Chặn transition sai **không có nghĩa là im lặng bỏ qua**. Chặn xong phải **la lên**.

---

## 8. Luồng 6 — BANK SẬP HOÀN TOÀN (circuit breaker)

```
Giao dịch 1-5 qua ACB:  timeout, timeout, 500, timeout, 500
   │
   ▼
Breaker của ACB MỞ  (tỉ lệ lỗi > 50% trong 10 lần gần nhất)
   │
Giao dịch 6 qua ACB:  ⚡ fail NGAY, không gọi Bank, không chờ 8 giây
   │                   → Hub báo Ana: "Ngân hàng ACB đang gián đoạn,
   │                                    vui lòng chọn ngân hàng khác"
   │                   → payment: ⬛ FAILED (đây là kết luận DỨT KHOÁT:
   │                      ta biết chắc CHƯA HỀ gọi Bank ⇒ tiền không trừ)
   │
Giao dịch 7 qua VCB:  ✅ BÌNH THƯỜNG   ← ⭐ cách ly theo bank
   │
Sau 30 giây: breaker ACB → HALF_OPEN, cho vài request thử
   thành công → đóng lại;  thất bại → mở tiếp
```

### ⭐ Vì sao breaker phải TÁCH THEO BANK

Một breaker dùng chung cho "Bank" nói chung là **thiết kế sai**: ACB sập sẽ chặn luôn
giao dịch qua VCB đang khoẻ ⇒ **một ngân hàng hỏng làm chết toàn bộ doanh thu**.

Với một bank duy nhất thì **không bao giờ phát hiện ra lỗi này** — đó chính là lý do
spec quyết định giả lập 3 bank.

### Khác biệt tinh tế: breaker mở ⇒ `FAILED`, timeout ⇒ `IN_PROGRESS`

| | Ta biết gì | Kết luận |
|---|---|---|
| **Timeout** | **không biết** Bank có nhận không | `IN_PROGRESS` — chờ đối soát |
| **Breaker mở** | **biết chắc** chưa hề gửi request đi | `FAILED` — an toàn, tiền không thể trừ |

Đây là lý do breaker không chỉ để bảo vệ Bank — nó còn cho Hub một câu trả lời **dứt khoát**
thay vì một dấu hỏi.

---

## 9. Bảng tổng: ai đổi trạng thái, ở đâu

| Transition | Kích hoạt bởi | Nguồn sự thật | Có thể xảy ra khi Ana offline? |
|---|---|---|---|
| `CREATED → IN_PROGRESS` | Ana bấm trả (Hub) | hành động người dùng | ❌ |
| `CREATED → CANCELLED` | Ana bấm quay lại (Hub) | hành động người dùng | ❌ |
| `CREATED → EXPIRED` | job quét | đồng hồ | ✅ |
| `IN_PROGRESS → CAPTURED` | **callback Bank** / đối soát | **Bank** | ✅ |
| `IN_PROGRESS → FAILED` | **callback Bank** / đối soát / breaker mở | **Bank** (hoặc "chưa gửi") | ✅ |
| `IN_PROGRESS → CANCELLED` | **callback Bank** | **Bank** | ✅ |

> Mọi transition rời `IN_PROGRESS` đều do **Bank** quyết, và đều **không phụ thuộc trình duyệt**.

---

## 10. Sáu câu chốt

1. **Đổi trạng thái theo callback [A], không theo redirect [B].** [B] Ana sửa được.
2. **`IN_PROGRESS` trước khi gọi Bank** — ghi ý định trước khi hành động.
3. **Timeout không kết luận gì** — ở lại `IN_PROGRESS`, để đối soát chốt.
4. **Retry giữ nguyên `Idempotency-Key`** — đổi khoá = trừ tiền hai lần.
5. **Breaker tách theo bank** — ACB sập không được kéo VCB chết theo.
6. **Chặn transition sai xong phải la lên**, đừng im lặng bỏ qua.
