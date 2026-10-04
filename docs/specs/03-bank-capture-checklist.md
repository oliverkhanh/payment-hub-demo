# Checklist thực hiện — Increment 4 (Hub ↔ Bank)

> Bản rút gọn để **tick khi code**. Chi tiết nghiệp vụ xem
> [03-bank-capture-spec.md](03-bank-capture-spec.md) · luồng xem
> [03-bank-capture-flows.md](03-bank-capture-flows.md).
> Kiến thức nền: [compare-and-set.md](../notes/compare-and-set.md) ·
> [bank-call-timeout-and-retry.md](../notes/bank-call-timeout-and-retry.md) ·
> [hmac-request-signing.md](../notes/hmac-request-signing.md)

---

## Chuẩn bị (đã có từ Inc 3)

- [x] Merchant gọi `POST /v1/payments` → bản ghi `payments` status `CREATED` + `checkout_url`
- [ ] Inc 4 **không tạo** `payments`, chỉ làm việc trên bản ghi đã có

## Chọn ngân hàng

- [ ] Danh sách bank đọc từ file properties, thuộc về Hub — merchant không biết, không chọn
- [ ] `enabled: false` → bank **không hiện ra** trong danh sách (dùng để bảo trì)
- [ ] Thêm một bank trỏ `base-url` vào cổng chết → demo ca **gọi là lỗi ngay** (khác `enabled`)
- [ ] Validate `bank_code` từ trình duyệt: không tin client

## Click Thanh toán — thứ tự bắt buộc

- [ ] ① **CAS** `UPDATE payments SET status='IN_PROGRESS' WHERE id=? AND status='CREATED'` — chống hai tab cùng bấm
- [ ] ② Ghi `payment_attempts` (intent log): `bank_code`, **`idempotency_key` sinh một lần**, chưa có kết quả
- [ ] ③ **COMMIT** — ① và ② cùng một transaction
- [ ] ④ **Rồi mới** gọi bank — **ngoài mọi transaction**
- [ ] ⑤ Ghi kết quả vào `payment_attempts` ở transaction thứ hai

> ⚠️ `IN_PROGRESS` nghĩa là *"đã bắt đầu nói chuyện với bank, chưa biết kết quả"* —
> **không phải** *"bank đã nhận"*. Sau một cú timeout, câu đó vẫn đúng.

## Gọi bank

- [ ] Strategy chọn adapter theo `bank_code` (registry, không `if/else`)
- [ ] Adapter dịch sang format riêng từng bank
- [ ] Mỗi bank khác nhau thật: đường dẫn, kiểu tên tham số, đơn vị tiền, bảng mã lỗi
- [ ] Ký **HMAC** bằng secret **do bank cấp**, mỗi bank một bộ riêng
- [ ] **Timeout + retry + circuit breaker** — retry phải **giữ nguyên** `idempotency_key`
- [ ] Ký trên **đúng byte sẽ gửi**, không serialize lại

## Ba nhánh kết quả — không phải hai

| Hub biết gì | Ví dụ | Status |
|---|---|---|
| **Biết chắc chưa gửi** | connect refused, DNS sai | về `CREATED` — thử lại / đổi bank |
| **Không biết** | timeout, 5xx, mã lỗi lạ | **giữ `IN_PROGRESS`** — chờ đối soát |
| **Bank trả lời không** | DECLINED, 400 | `FAILED` |

- [ ] Mã lỗi lạ chưa từng gặp → **không biết**, tuyệt đối không mặc định thành từ chối
- [ ] Bank trả `redirect_url` → Hub dùng **nguyên xi**, không tự ghép từ `transactionId`

## Phía bank — nhận request từ Hub

- [ ] Verify HMAC trên **byte thô** trước khi parse, parse trước khi chạm nghiệp vụ
- [ ] Idempotent theo `UNIQUE(bank_code, idempotency_key)` — Hub retry không đẻ giao dịch thứ hai
- [ ] Tạo `bank_transactions` status `PENDING`, trả `bank_txn_id` + `redirect_url`
- [ ] Hiển thị form **không đổi status** — `GET` không được thay đổi dữ liệu

## Phía bank — Ana nhập tài khoản

- [ ] Seed account phủ đủ ca: active · khoá · không đủ tiền · chậm (timeout) · lỗi 5xx
- [ ] Xác thực theo thứ tự: **tồn tại → PIN → đang mở**
- [ ] Sai PIN và tài khoản không tồn tại trả **CÙNG một thông báo**
- [ ] "Tài khoản bị khoá" chỉ nói ra **sau khi** PIN đúng
- [ ] **Không ghi PIN vào log, không render PIN ra lại**

## Phía bank — trừ tiền: hai CAS + outbox, MỘT transaction

- [ ] CAS ① `UPDATE bank_accounts SET balance = balance - :amt WHERE code=? AND balance >= :amt`
- [ ] CAS ② `UPDATE bank_transactions SET status='SUCCESS' WHERE id=? AND status='PENDING'`
- [ ] ③ `INSERT bank_outbox` — payload **đóng băng dạng TEXT**, không JSONB
- [ ] Cả ba cùng commit hoặc cùng rollback

> JSONB chuẩn hoá JSON (sắp xếp lại khoá, bỏ khoảng trắng) ⇒ byte đọc ra khác byte đã ký ⇒ 401.

- [ ] CAS ① chống thấu chi · CAS ② chống chốt hai lần — **hai việc khác nhau, cần cả hai**
- [ ] Hết tiền = **một lần thử hỏng**, giao dịch vẫn `PENDING`, Ana đổi tài khoản khác
- [ ] Giao dịch chỉ kết thúc khi Ana **bấm Huỷ** hoặc **hết hạn**

## Webhook Bank → Hub

- [ ] Job quét `bank_outbox` còn `PENDING` và đã tới hạn
- [ ] Backoff + jitter khi lỗi (jitter để tránh thundering herd)
- [ ] Ký HMAC bằng **khoá riêng cho chiều webhook**, khác khoá chiều Hub→Bank
- [ ] `event_id` đi trong header `Idempotency-Key`

## Hub nhận webhook

- [ ] Chuỗi security riêng cho `/bank-callbacks/**`, filter HMAC merchant không được chạm vào
- [ ] Verify chữ ký + timestamp → tra `payment_attempts` theo `idempotency_key`
- [ ] **Đối chiếu `amount` + `currency`** — lệch thì không capture, báo lỗi to
- [ ] CAS `WHERE status='IN_PROGRESS'` → `CAPTURED` / `CANCELLED` / `FAILED`
- [ ] **Webhook trùng phải trả 2xx** — trả lỗi thì bank retry vô tận
- [ ] `4xx` = sai vĩnh viễn, bank ngừng retry · `5xx` = bank retry

## Quay về Hub

- [ ] Trang kết quả hiện trạng thái Hub **đang biết** + nút **Kiểm tra lại**
- [ ] Luôn có nút về cửa hàng, ở **mọi** trạng thái

> ⚠️ **Redirect trình duyệt chỉ là UX, webhook mới là sự thật.** Ana gõ tay URL redirect được,
> nên Hub tuyệt đối không đổi trạng thái theo nó.

## Ca không ai bấm nút nào

- [ ] Ana đóng trình duyệt giữa chừng → job hết hạn ở bank + job đối soát ở Hub
- [ ] Không được phụ thuộc vào việc người dùng bấm nút để hệ thống về trạng thái đúng

---

## Ba lỗi hay mắc nhất

1. **Tưởng Inc 4 tạo `payments`** — không, nó có sẵn từ Inc 3. Inc 4 tạo `payment_attempts`.
2. **Đổi status SAU khi gọi bank thành công** — sai thứ tự. Timeout là mất dấu hoàn toàn:
   bank đã tạo giao dịch, Hub vẫn `CREATED` và không có bản ghi nào cho thấy mình từng gọi.
3. **"Gọi thất bại → FAILED"** — gộp timeout vào thất bại là tự nhận *"chắc chắn không mất tiền"*
   trong khi không biết gì. Đó là cách khách bị trừ tiền mà không có đơn.

> Cả ba cùng một gốc: **đổi trạng thái là ĐIỀU KIỆN để được gọi, không phải KẾT QUẢ của cú gọi.**
