# Outbox Pattern — bảng `outbox` của fake-bank

> Bảng tạo ở `fake-bank/.../db/migration/V6__outbox.sql`.
> Dùng chung cho mọi tính năng cần "đổi DB xong thì báo cho hệ thống khác",
> không riêng webhook thanh toán.

## 1. Vấn đề nó giải quyết: dual write

Trừ tiền xong phải báo cho Hub. Cách ngây thơ:

```java
@Transactional
void debit(...) {
    accountRepository.debit(...);          // ① trừ tiền
    bankTransactionRepository.markSuccess(...);  // ② chốt giao dịch
}
webhookClient.post(hubUrl, ...);           // ③ báo Hub — NGOÀI transaction
```

Hai kho dữ liệu, hai lần ghi, **không có cách nào làm chúng nguyên tử**:

| Sập ở đâu | Hậu quả |
|---|---|
| Sau ② trước ③ | Tiền đã trừ, Hub **không bao giờ biết**. Khách mất tiền, đơn chưa thanh toán |
| ③ chạy trước ①② rồi ①② rollback | Hub tưởng đã thu tiền, thực tế chưa trừ |

Đảo thứ tự không cứu được — chỉ đổi kiểu hỏng.

**Outbox giải cách khác:** ghi "ý định gửi" vào **cùng database, cùng transaction**.
Một DB, một transaction ⇒ nguyên tử thật.

```java
@Transactional
void debit(...) {
    accountRepository.debit(...);                 // ①
    bankTransactionRepository.markSuccess(...);   // ②
    outboxRepository.save(event);                 // ③ CÙNG transaction
}
// commit xong, một job khác đọc outbox rồi gửi đi
```

Sập bất cứ đâu trước commit ⇒ cả ba biến mất. Sập sau commit ⇒ dòng outbox còn đó,
job quét sẽ gửi. **Không bao giờ có chuyện tiền đi mà sự kiện mất.**

> Giống hệt bài học `payment_attempts` bên Hub: ghi ý định **trước**, commit, rồi mới
> chạm mạng. Khác chỗ: intent log là "đi mà hỏi", outbox là "gửi lại cho tới khi được".

## 2. Từng cột

### Danh tính

| Cột | Ý nghĩa |
|---|---|
| `id` | khoá tự tăng, dùng để sắp thứ tự gửi |
| `event_id` | **UUID của sự kiện**, `UNIQUE`. Đi trong header `Idempotency-Key` khi gọi webhook — để bên nhận chống xử lý trùng khi ta gửi lại |

`event_id` sinh **một lần duy nhất** lúc ghi dòng. Mọi lần gửi lại đều dùng lại
chính nó, nếu không bên nhận coi là sự kiện mới.

### Phong bì — ai, chuyện gì, chi tiết ra sao

| Cột | Ý nghĩa | Ví dụ |
|---|---|---|
| `aggregate_type` | loại đối tượng mà sự kiện xảy ra với nó | `BankTransaction` |
| `aggregate_id` | định danh đối tượng đó | `VCB-uV99g3d8ctq1EcCp` |
| `event_type` | **chuyện gì đã xảy ra**, thì quá khứ (CLAUDE.md §5) | `BankTransaction.Succeeded` |
| `payload` | toàn bộ nội dung sự kiện, JSON **dạng TEXT** | xem §3 |

Ba cột đầu là **siêu dữ liệu để tra cứu**; `payload` là thứ thực sự gửi đi.

⚠️ **`payload` bắt buộc `TEXT`, tuyệt đối không `JSONB`.** JSONB chuẩn hoá JSON —
sắp xếp lại khoá, bỏ khoảng trắng. Byte đọc ra sẽ khác byte đã ký HMAC ⇒ bên nhận
trả `401`. Chuỗi phải **đóng băng** từ lúc ghi tới lúc gửi.

### Giao hàng

| Cột | Ý nghĩa |
|---|---|
| `destination` | gửi đi đâu — URL webhook đầy đủ |
| `status` | `PENDING` chờ gửi · `SENT` đã gửi xong · `FAILED` hết lượt thử |
| `attempt_count` | đã thử mấy lần — dùng để tính backoff và để bỏ cuộc |
| `next_attempt_at` | **sớm nhất được thử lại lúc nào**. Job chỉ bốc dòng đã tới hạn |
| `last_error` | lý do lần gửi gần nhất hỏng — để chẩn đoán |
| `created_at` · `sent_at` | sinh lúc nào, gửi xong lúc nào |

`next_attempt_at` là cột quan trọng nhất của nhóm này. Thiếu nó, một dòng lỗi sẽ bị
thử lại **mỗi vòng quét, hết tốc lực**. Hub sập 10 phút = hàng nghìn request vô ích.

### `bank_code` — vì sao tách riêng

Relay cần biết **ký bằng khoá nào**. Để nó thành cột riêng thì relay chỉ việc
`bank.api-clients.<bank_code>.webhook-secret`, không phải mở `payload` ra đọc.
Đây là chiều hợp lệ của mọi message đi ra từ service này, không phải chi tiết của
riêng thanh toán.

## 3. Ví dụ một dòng thật

Khách `vcb_user_active` trả 300.000đ cho giao dịch `VCB-uV99g3d8ctq1EcCp`:

```
id              | 1
event_id        | 9f1c7c2e-5b4a-4d0e-9a11-2c6f8e3b71aa
bank_code       | VCB
aggregate_type  | BankTransaction
aggregate_id    | VCB-uV99g3d8ctq1EcCp
event_type      | BankTransaction.Succeeded
payload         | {"event_id":"9f1c7c2e-...","event_type":"BankTransaction.Succeeded",
                |  "occurred_at":"2026-10-10T03:12:44Z","bank_code":"VCB",
                |  "bank_txn_id":"VCB-uV99g3d8ctq1EcCp",
                |  "idempotency_key":"att_w0uwdKwK8LqR3nT1",
                |  "amount":300000,"currency":"VND","status":"SUCCESS"}
destination     | http://payment-hub:8080/bank-callbacks/VCB
status          | PENDING
attempt_count   | 0
next_attempt_at | 2026-10-10 03:12:44+00
last_error      | (null)
created_at      | 2026-10-10 03:12:44+00
sent_at         | (null)
```

Vì sao `payload` chứa `idempotency_key`: đó là **sợi dây duy nhất** nối ngược về
`payment_attempts` bên Hub. Hub tra theo khoá đó để biết đây là payment nào.

Vì sao `payload` chứa cả `amount` + `currency`: Hub phải **đối chiếu** với số tiền
nó đã yêu cầu. Lệch thì không được capture — báo lỗi to.

Vì sao `event_id` vừa là cột vừa nằm trong `payload`: cột để `UNIQUE` và đánh index;
trong payload để nó **được ký** cùng phần còn lại, không sửa được trên đường đi.

## 4. Vòng đời một dòng

```
         ghi cùng transaction trừ tiền
                    │
                    ▼
         ┌──────────────────────┐
         │ PENDING  attempt=0   │
         └──────────┬───────────┘
                    │ job bốc dòng đã tới hạn
                    ▼
            POST webhook sang Hub
              │              │
         2xx  │              │  lỗi / timeout
              ▼              ▼
    ┌─────────────┐   ┌──────────────────────────────┐
    │ SENT        │   │ PENDING  attempt+1           │
    │ sent_at=now │   │ next_attempt_at = now + lùi  │
    └─────────────┘   │ last_error = "..."           │
                      └──────────┬───────────────────┘
                                 │ quá số lần cho phép
                                 ▼
                         ┌──────────────┐
                         │ FAILED       │  cần người xem
                         └──────────────┘
```

Dòng `SENT` **giữ lại vĩnh viễn** (chốt ngày 2026-10-10) — CLAUDE.md #7 yêu cầu audit
append-only. Bảng sẽ phình; khi nào cần thì chuyển sang bảng lưu trữ, chưa làm bây giờ.

## 5. Hai index, mỗi cái một việc

```sql
CREATE INDEX idx_outbox_due ON outbox (next_attempt_at)
    WHERE status = 'PENDING';
```

Index **một phần** — chỉ chứa dòng còn chờ gửi. Tuyệt đại đa số dòng là `SENT` và
không có lý do nằm trong index. Nhờ vậy vòng quét khi rảnh là một index scan chạm
~0 dòng, dưới 1ms, dù bảng có hàng triệu dòng đã gửi.

```sql
CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id, created_at DESC);
```

Để tra cứu khi điều tra: *"giao dịch `VCB-abc` đã phát ra những sự kiện nào?"*.
Cần vì ta giữ dòng vĩnh viễn.

## 6. Ba quy tắc bắt buộc

**① Ghi outbox cùng transaction với thay đổi nghiệp vụ.** Tách ra là mất ý nghĩa.

**② `payload` là TEXT, byte đóng băng.** Không JSONB, không parse-rồi-serialize-lại.

**③ Không giữ transaction qua lời gọi HTTP.** Relay chia ba đoạn:

```
tx 1   bốc dòng (FOR UPDATE SKIP LOCKED), đánh dấu đang xử lý   ─ commit ─
       │
       ▼  NGOÀI mọi transaction
       POST webhook, ký HMAC, có timeout
       │
tx 2   ghi kết quả: SENT, hoặc attempt+1 và lùi next_attempt_at
```

`SKIP LOCKED` để hai instance relay không bốc trùng một dòng.

## 7. Vì sao gọi là "tổng quát"

Thêm tính năng mới **không phải đổi schema**. Ví dụ sau này bank cần báo Hub khi
giao dịch hết hạn:

```
aggregate_type  | BankTransaction
aggregate_id    | VCB-xyz789
event_type      | BankTransaction.Expired          ← chỉ khác chỗ này
payload         | {...,"status":"EXPIRED",...}
destination     | http://payment-hub:8080/bank-callbacks/VCB
```

Hoặc một tính năng hoàn toàn khác — báo khi tài khoản bị khoá:

```
aggregate_type  | Account
aggregate_id    | vcb_user_locked
event_type      | Account.Locked
payload         | {...}
destination     | http://risk-service:8080/events
```

Relay không cần biết `BankTransaction` hay `Account` là gì. Nó đọc `destination`,
ký `payload`, gửi đi, ghi kết quả. Đó là điều mà bốn cột
`bank_code / bank_txn_ref / status / callback_url` không làm được — chúng mô tả
**một việc cần làm**, không phải **một sự kiện đã xảy ra**.
