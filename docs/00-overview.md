# 00 — Overview & Nguyên tắc thiết kế

> Đọc file này trước. Nó định nghĩa *chúng ta đang xây cái gì, tại sao, và giới hạn ở đâu.*
> Mọi tài liệu sau đều dựa trên các nguyên tắc ở đây.

---

## 1. Mục tiêu & phi-mục tiêu

### Mục tiêu
Xây một **Payment Gateway mô phỏng gần production nhất có thể**, để một người học được
sâu về **Backend Architecture** và **Distributed Systems** thông qua việc *tự tay dựng*
các bài toán mà một gateway thật phải giải:

- Tiền không được mất, không được nhân đôi (consistency, idempotency).
- Hệ thống bên ngoài (bank, merchant) không đáng tin: chậm, lỗi, mất mạng, trả trùng.
- Trạng thái giao dịch phải luôn hội tụ về đúng (reconciliation).
- Mọi thứ observability được: đo, trace, alert.

### Phi-mục tiêu (rất quan trọng — để không lạc)
- ❌ Không xây e-commerce đầy đủ (merchant chỉ là công cụ tạo dữ liệu).
- ❌ Không xây core banking thật (fake bank chỉ đủ để test tích hợp).
- ❌ Không xử lý thẻ thật, không đụng PCI-DSS thật (dùng token giả lập).
- ❌ Không tối ưu hiệu năng cực đại (không phải mục tiêu học ở đây).
- ❌ Không đẹp về UI (UI chỉ để thao tác flow).

> **Nguyên tắc vàng:** khi phân vân "có nên làm phần này không?", hỏi *"nó có dạy mình
> một khái niệm distributed system / payment thật không?"*. Nếu không → cắt.

---

## 2. Vì sao "payment" là domain tuyệt vời để học distributed systems

Payment ép bạn đối mặt với gần như **mọi bài toán kinh điển** của hệ phân tán, vì tiền
làm cho lỗi trở nên *không thể chấp nhận*:

| Bài toán distributed system | Xuất hiện ở đâu trong payment |
|---|---|
| **Exactly-once (thực chất là at-least-once + idempotency)** | Callback từ bank có thể tới 2 lần → không được trừ tiền 2 lần |
| **Dual-write problem** | Ghi DB "payment=PAID" + publish event "đã thanh toán" phải nguyên tử → Outbox |
| **Distributed transaction** | Payment Hub cập nhật trạng thái, Bank trừ tiền, Merchant cập nhật order — 3 hệ thống, không có 2PC → Saga |
| **Eventual consistency & reconciliation** | Hub nghĩ "thành công", Bank nghĩ "thất bại" → phải đối soát |
| **Out-of-order / duplicate messages** | Callback "success" tới trước "authorized" → state machine phải chịu được |
| **Failure & retry** | Merchant webhook 500 → retry với backoff, đến DLQ |
| **Idempotency & concurrency** | User bấm "Pay" 2 lần → chỉ tạo 1 giao dịch |
| **Timeout & circuit breaking** | Bank treo 30s → không được kéo sập cả Hub |
| **Observability** | Một payment đi qua 3 hệ thống → cần trace xuyên suốt |

Đây chính là lý do đề bài chọn payment gateway thay vì "todo app phân tán".

---

## 3. Ba hệ thống — mô tả & ranh giới

### 3.1 Merchant Demo (`merchant-demo/`) — *công cụ, không phải trọng tâm*

**Có:** trang 5–10 sản phẩm giả → giỏ hàng → checkout → nút Thanh toán → gọi API Hub →
redirect sang Hub → nhận webhook → cập nhật trạng thái order → trang danh sách order.

**Trang "Chaos" (giả lập lỗi phía merchant):** một trang bật/tắt các chế độ để test khả
năng chịu lỗi của Hub khi *merchant* là bên yếu:
- Trả HTTP 500 khi nhận webhook.
- Timeout (không phản hồi).
- Xử lý webhook rất chậm (sleep N giây).
- Giả "down" (từ chối kết nối).

→ Dùng để chứng minh Hub **retry webhook** đúng, có **DLQ**, không mất event.

**Không có:** login, user management, inventory, voucher, promotion, shipping, admin, dashboard.

### 3.2 Payment Hub (`payment-hub/`) — *hệ thống chính*

Trái tim của project. Chi tiết thành phần ở Mục 5. Đây là nơi ta thực sự học kiến trúc.
Nó đóng vai trò **payment orchestrator**: nhận yêu cầu từ merchant, điều phối tới bank,
quản lý vòng đời giao dịch, giao tiếp ngược lại merchant qua webhook.

### 3.3 Fake Bank (`fake-bank/`) — *acquirer/issuer giả lập*

Mô phỏng phía ngân hàng thu (acquiring bank) / tổ chức phát hành (issuer). Đủ để Hub
tích hợp thật:

**Chức năng:** Payment Authorization, Payment Confirmation (capture), Refund, Transaction
Query, Callback về Hub. Có tài khoản giả + balance giả.

**UI đơn giản:** trang cho người dùng chọn Thanh toán thành công / thất bại / hủy (mô
phỏng màn hình OTP/Internet Banking).

**Trình giả lập lỗi (Chaos Engine)** — đây là *giá trị học tập* của fake bank:
Delay, Timeout, HTTP 500, Duplicate Callback, Out-of-order Callback, Mất mạng
(connection reset), Random Failure (tỉ lệ %), Insufficient Balance, Manual Review (treo
giao dịch chờ duyệt).

→ Mỗi chế độ này ánh xạ tới một business flow ở `docs/02-business-flows.md`.

---

## 4. Nguyên tắc thiết kế xuyên suốt (design principles)

Những nguyên tắc này lặp lại trong mọi tài liệu sau. Nhớ chúng = hiểu được 80% quyết định.

1. **Assume everything fails.** Mọi lời gọi mạng có thể chậm/lỗi/mất/trùng. Thiết kế cho lỗi *trước*, cho happy-path sau.
2. **At-least-once + Idempotency = exactly-once (thực dụng).** Ta không cố đạt exactly-once trên network (bất khả thi). Ta chấp nhận nhận trùng, rồi khử trùng bằng idempotency.
3. **Nhất quán qua Outbox, không qua may mắn.** Không bao giờ tin "ghi DB xong rồi chắc publish event cũng xong".
4. **State machine là nguồn chân lý cho vòng đời.** Trạng thái chỉ đổi qua transition hợp lệ.
5. **Tiền là số nguyên minor unit + currency.** `10000` VND, không phải `100.00`. Không float.
6. **Money movement phải có ledger (sổ cái).** Double-entry: mỗi dịch chuyển tiền là cặp bút toán cân bằng. Đây là chuẩn của mọi hệ thống tài chính thật.
7. **Reconciliation là công dân hạng nhất, không phải cron phụ.** Vì hệ phân tán *sẽ* lệch, phải có cơ chế phát hiện & chữa.
8. **Observability không phải nghĩ sau.** Structured log + metrics + distributed trace từ ngày đầu.
9. **Security theo chiều sâu.** API key + signature (HMAC) cho merchant↔Hub; mTLS/allowlist cho Hub↔Bank (mô phỏng); secrets ở Secrets Manager.
10. **Đơn giản có chủ đích.** Mỗi lần thêm phức tạp phải trả lời được "học được gì / production có thật không". Nếu không → cắt và ghi chú.

---

## 5. Thành phần của Payment Hub: **Bắt buộc / Nên có / Nâng cao**

Đây là phần trả lời trực tiếp câu hỏi của bạn. Phân loại theo *giá trị học tập* và *tính
cốt lõi của một gateway thật*, kèm lý do.

### 🔴 BẮT BUỘC (MVP — không có thì không phải payment gateway)

| Thành phần | Vì sao bắt buộc |
|---|---|
| **Merchant Management** | Phải biết ai đang gọi. Là gốc của auth, settlement, đối soát. Không có thì không có khái niệm "khách hàng của gateway". |
| **API Key + Authentication** | Cách merchant tự định danh với Hub. Nền tảng bảo mật cơ bản nhất. |
| **Signature Verification (HMAC)** | Chống giả mạo & tampering request. Đây là *chuẩn thật* của Stripe/VNPay (ký payload bằng secret). Dạy bạn về message integrity. |
| **Payment Session / Payment Creation** | Trái tim nghiệp vụ: khởi tạo một ý định thanh toán, tạo `payment_id`, trạng thái ban đầu. |
| **Redirect flow** | Mô hình hosted-checkout kinh điển: merchant redirect user sang gateway. Dạy stateful cross-system flow. |
| **Callback (Bank → Hub)** | Cách Hub biết kết quả từ bank. Đây là nơi phát sinh *duplicate/out-of-order* — bài học lớn nhất. |
| **Webhook (Hub → Merchant)** | Cách merchant biết kết quả. Dạy at-least-once delivery, retry, HMAC signing, DLQ. |
| **Idempotency** | Không thể thiếu trong payment. Mọi create/refund/callback phải idempotent. |
| **State Machine** | Vòng đời payment (CREATED→AUTHORIZED→CAPTURED…). Không có thì trạng thái loạn. |
| **Query Payment / Transaction History** | Merchant & vận hành phải tra được. Cũng là "read side" nếu học CQRS. |
| **Retry (có backoff)** | Với callback/webhook/gọi bank. Bài học cốt lõi về resilience. |
| **Audit Log** | Payment = tiền = phải truy vết được ai làm gì, khi nào. Append-only. |
| **Outbox Pattern** | Cơ chế bảo đảm nhất quán DB↔event. Bắt buộc vì ta có event-driven. |

### 🟡 NÊN CÓ (làm hệ thống "thật" hơn nhiều, độ khó vừa)

| Thành phần | Vì sao nên có |
|---|---|
| **Refund + Partial Refund** | Nghiệp vụ payment thật luôn có. Partial refund dạy state machine phức tạp hơn (một payment nhiều refund, tổng refund ≤ captured). |
| **Authorization** (phân quyền) | Merchant A không được xem payment của merchant B. Multi-tenant isolation — bài học kiến trúc quan trọng. |
| **Scheduler** | Chạy retry queue, reconciliation, hết hạn payment session, settlement batch. |
| **Reconciliation** | Đối soát Hub↔Bank. *Đặc sản* của distributed systems: hệ sẽ lệch, phải phát hiện & chữa. Rất đáng học. |
| **Event Publishing (Kafka)** | Backbone cho event-driven, audit stream, read-model, reconciliation feed. |
| **Notification** | Gửi thông báo (email giả/log) khi payment xong/lỗi. Dạy fan-out qua SNS. |
| **Monitoring / Metrics / Alert** | Prometheus + Grafana + alert. Không có observability thì không vận hành nổi hệ phân tán. |
| **Distributed Lock** | Chống double-processing khi nhiều instance. Dạy Redisson/Redlock. |
| **Rate Limiting** | Bảo vệ Hub khỏi merchant spam. Dạy token bucket + Redis. |
| **Ledger (double-entry)** | ⭐ *Bổ sung của mình.* Sổ cái kép — cách hệ thống tài chính thật ghi nhận tiền. Biến "status=PAID" thành bút toán cân bằng. Cực kỳ đáng học, xem Mục 6. |

### 🟢 NÂNG CAO (phiên bản sau — khi core đã vững)

| Thành phần | Vì sao để sau |
|---|---|
| **Settlement** | Gom giao dịch trong ngày, tính phí, tạo payout cho merchant. Nghiệp vụ thật nhưng phức tạp, chỉ ý nghĩa khi đã có ledger + reconciliation. |
| **Fraud Detection** | Rule engine đơn giản (velocity check, amount threshold, blacklist). Hay nhưng không cốt lõi để hiểu kiến trúc; làm sau như một service tiêu thụ event. |
| **3DS / OTP Challenge** | ⭐ *Bổ sung.* Mô phỏng bước xác thực mạnh (redirect sang trang OTP của bank). Dạy multi-step async auth. |
| **Payment Orchestration / Routing** | ⭐ *Bổ sung.* Định tuyến sang nhiều "bank" theo rule (chi phí, tỉ lệ thành công). Dạy Strategy + Adapter ở quy mô. Cần ≥2 fake bank. |
| **Tokenization / Vault** | ⭐ *Bổ sung.* Lưu "token" thay vì thông tin nhạy cảm. Dạy cách né PCI scope. Bản tối giản. |
| **Chargeback / Dispute** | ⭐ *Bổ sung.* Luồng khiếu nại đảo tiền. Dạy long-running saga + ledger reversal. |
| **CQRS + Read Model tách biệt** | Tách read/write cho transaction history/report. Chỉ đáng khi dữ liệu lớn; ở đây học ở mức khái niệm. |
| **Multi-currency / FX** | Đổi tiền tệ. Thêm chiều phức tạp cho ledger. Optional. |

> **Lộ trình khuyến nghị:** hoàn thành toàn bộ 🔴 → thêm dần 🟡 → chỉ chạm 🟢 khi bạn
> muốn đào sâu một chủ đề cụ thể. Chi tiết trong `docs/06-roadmap.md`.

---

## 6. ⭐ Những thứ mình chủ động bổ sung (gateway thật có, đề bài chưa nêu)

Đề bài yêu cầu "nếu thiếu thành phần/flow quan trọng thì chủ động bổ sung". Đây là danh
sách + lý do:

1. **Ledger / Double-entry Accounting.**
   *Vấn đề nếu thiếu:* chỉ có `payment.status = PAID` thì bạn không bao giờ trả lời được
   "merchant này đang được giữ bao nhiêu tiền, đã trả phí bao nhiêu, còn nợ payout bao
   nhiêu". Mọi hệ thống tài chính thật ghi tiền bằng **bút toán kép** (mỗi giao dịch =
   debit tài khoản này + credit tài khoản kia, tổng = 0). Nó là nền tảng cho settlement,
   refund, chargeback, reconciliation. *Học được:* invariants, immutability, cách "tiền"
   được mô hình hóa đúng.

2. **Dead Letter Queue (DLQ).** Khi retry webhook/callback hết số lần → không vứt đi mà
   đẩy vào DLQ để xử lý tay/replay. *Thiếu thì:* mất event âm thầm — tối kỵ trong payment.

3. **3DS / OTP challenge (mô phỏng).** Bước "xác thực mạnh" khiến flow thành *async
   nhiều bước* — sát thực tế thẻ quốc tế và ngân hàng VN (OTP).

4. **Idempotency-Key chuẩn (như Stripe).** Header `Idempotency-Key` cho POST tạo/refund.

5. **Payment expiry / session timeout.** Payment session có TTL; hết hạn thì EXPIRED.
   Dạy scheduler + state machine + xử lý callback tới muộn sau khi đã expire.

6. **Webhook signing & replay protection.** Hub ký webhook bằng HMAC + timestamp để
   merchant verify; merchant chống replay bằng timestamp window.

7. **Distributed Tracing xuyên 3 hệ thống.** Truyền `trace_id` từ merchant → Hub → bank
   → webhook, để xem toàn bộ hành trình một payment. Cực kỳ đáng học.

8. **Chaos/Fault Injection có cấu hình.** Cả merchant lẫn bank có "chaos engine" bật/tắt
   được — biến việc test resilience thành thao tác chủ động thay vì chờ lỗi tự nhiên.

---

## 7. Từ điển thuật ngữ (glossary) — thống nhất cách gọi

| Thuật ngữ | Nghĩa trong project |
|---|---|
| **Payment / Payment Intent** | Một ý định thanh toán do merchant khởi tạo, có `payment_id`, vòng đời riêng. |
| **Authorization (authz tiền)** | Bank "giữ" tiền/duyệt giao dịch nhưng chưa trừ hẳn. |
| **Capture** | Xác nhận trừ tiền thật sau khi đã authorize. |
| **Refund** | Trả tiền lại cho người mua (toàn phần/một phần). |
| **Callback** | Bank → Hub báo kết quả (server-to-server). |
| **Webhook** | Hub → Merchant báo kết quả (server-to-server). |
| **Redirect / Return URL** | Trình duyệt user quay lại merchant sau khi thanh toán. |
| **Settlement** | Gom & chuyển tiền định kỳ cho merchant, sau khi trừ phí. |
| **Reconciliation** | Đối soát: so trạng thái/hồ sơ giữa Hub và Bank, phát hiện & chữa lệch. |
| **Ledger** | Sổ cái double-entry ghi mọi dịch chuyển tiền. |
| **Acquirer** | Ngân hàng thu (bên fake bank đóng vai). |
| **Idempotency Key** | Khóa để nhận diện & khử request/lệnh trùng. |

---

## 8. Bức tranh flow tổng (high level)

```
Người mua        Merchant Demo         Payment Hub                 Fake Bank
   │                  │                     │                          │
   │  duyệt sản phẩm  │                     │                          │
   │─────────────────▶│                     │                          │
   │  bấm "Thanh toán"│                     │                          │
   │─────────────────▶│  POST /v1/payments  │                          │
   │                  │  (API key + HMAC)   │                          │
   │                  │────────────────────▶│  tạo payment (CREATED)   │
   │                  │◀────────────────────│  trả redirect_url        │
   │  redirect ───────┼────────────────────▶│  trang hosted checkout   │
   │                  │                     │  ──▶ tạo giao dịch bank   │
   │                  │                     │─────────────────────────▶│ authorize
   │  nhập OTP / chọn ─┼─────────────────────┼─────────────────────────▶│ (UI bank)
   │                  │                     │◀─────────────────────────│ callback (success)
   │                  │                     │  cập nhật state, ledger   │
   │                  │  webhook (HMAC) ◀────│  + publish event         │
   │                  │◀────────────────────│                          │
   │                  │  cập nhật order     │                          │
   │  redirect return │◀────────────────────│                          │
   │◀─────────────────│                     │                          │
```

Chi tiết từng nhánh (success/fail/cancel/timeout/duplicate/…): xem
[docs/02-business-flows.md](02-business-flows.md).

---

## 9. Tiếp theo đọc gì

- Muốn biết **deploy ở đâu, dùng service AWS nào, vì sao** → [01-aws-architecture.md](01-aws-architecture.md)
- Muốn biết **từng flow chạy chi tiết ra sao** → [02-business-flows.md](02-business-flows.md)
- Muốn biết **chia service, DB, state machine, outbox/saga** → [03-system-design.md](03-system-design.md)
- Muốn **bắt tay làm theo sprint** → [06-roadmap.md](06-roadmap.md)
