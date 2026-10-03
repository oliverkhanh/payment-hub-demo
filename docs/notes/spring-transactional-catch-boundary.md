# Spring `@Transactional`: catch exception bên TRONG hay bên NGOÀI?

> Gặp thật khi code Increment 3 (`IdempotencyStore.claim`). Đã **kiểm chứng bằng thí nghiệm**
> trên 29 test — xem §9.
> Đọc kèm: [spring-transaction-proxy-and-abort.md](spring-transaction-proxy-and-abort.md) ·
> [inc3-bugs-found-by-tests.md](inc3-bugs-found-by-tests.md)

---

## 1. Vấn đề là gì?

Một operation nằm trong transaction:

```java
@Transactional
public void claim() {
    repository.saveAndFlush(...);
}
```

Giả sử `saveAndFlush()` lỗi:

```
UNIQUE constraint violation
```

Vấn đề phát sinh khi catch exception **ngay bên trong** transaction:

```java
@Transactional
public void claim() {
    try {
        repository.saveAndFlush(...);
    } catch (DataIntegrityViolationException e) {
        // xử lý duplicate
    }
}
```

Nhìn bề ngoài:

```
Exception đã được catch
→ method không còn exception
→ tưởng như operation thành công
```

Nhưng transaction đã bị đánh dấu:

```
rollback-only
```

Nghĩa là **transaction này không được phép COMMIT nữa**. Xuất hiện mâu thuẫn:

```
Java method:   SUCCESS
Transaction:   FAILED / rollback-only
```

Khi Spring kết thúc transaction, nó ném:

```
org.springframework.transaction.UnexpectedRollbackException:
  Transaction silently rolled back because it has been marked as rollback-only
```

### Ai đóng dấu `rollback-only`? (chi tiết hay bị bỏ qua)

**Không phải** cái `catch` của bạn, và cũng **không phải** `@Transactional` ngoài cùng.
Người đóng dấu là **một ranh giới transaction LỒNG BÊN TRONG**:

`SimpleJpaRepository.saveAndFlush()` **tự nó** có `@Transactional` (mặc định `REQUIRED`)
⇒ nó **tham gia** transaction của `claim()`. Khi INSERT lỗi, interceptor của **repository**
kết thúc phần của nó bằng cách gọi `setRollbackOnly()` trên transaction dùng chung.

```
claim()  ── mở tx T (REQUIRES_NEW)
   │
   └─ saveAndFlush() ── THAM GIA tx T
         │ INSERT → đụng UNIQUE → ném DataIntegrityViolationException
         └─ interceptor repository: "có exception ⇒ ĐÁNH DẤU T là rollback-only"  ⚠️
   │
   ├─ catch của bạn nuốt exception → return bình thường
   └─ interceptor claim(): "không exception ⇒ COMMIT" → 💥
```

> Dấu `rollback-only` **đã đóng rồi**. `catch` chỉ chặn *exception lan ra*,
> nó **không gỡ được dấu**.

---

## 2. Giải pháp là gì?

Nếu một exception làm transaction không thể commit, **để exception thoát khỏi
transactional boundary**:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public Record claim(...) {
    return repository.saveAndFlush(...);     // KHÔNG catch ở đây
}
```

Rồi catch ở business layer:

```java
// KHÔNG @Transactional — đây là chỗ đứng NGOÀI mọi transaction
try {
    Record record = idempotency.claim(...);
} catch (DataIntegrityViolationException e) {
    return handleDuplicate(...);
}
```

Luồng trở thành:

```
claim()
   ↓  DB operation
   ↓  Exception
   ↓  Exception THOÁT khỏi @Transactional
   ↓  Spring thấy có exception ⇒ ROLLBACK        ← không thử COMMIT
   ↓  Transaction kết thúc ĐÚNG CÁCH (xem §4.1)
   ↓  Exception GỐC tiếp tục bay ra ngoài (không bị thay thế)
   ↓  Business layer catch
   ↓  Xử lý duplicate
```

---

## 3. Tại sao phải làm như vậy?

Vì cần tách **hai trách nhiệm**.

**Transaction layer** trả lời:
```
"Operation này có thành công về mặt transaction không?"
DB operation thất bại → rollback
```

**Business layer** trả lời:
```
"Nếu operation thất bại thì nghiệp vụ phải làm gì?"

claim thất bại
    ↓ transaction rollback
    ↓ business layer biết là duplicate
    ↓ lấy record đã tồn tại
    ↓ tiếp tục xử lý
```

Không nên để một layer vừa **catch exception**, vừa **còn nằm trong transaction đã
rollback-only**, vừa **cố tiếp tục transaction đó**.

> **Exception bay ra khỏi method `@Transactional` chính là TÍN HIỆU để Spring rollback.**
> Nuốt nó = cắt mất tín hiệu ⇒ Spring tưởng thành công ⇒ đi commit.

---

## 4. Hai trường hợp

### ❌ Catch bên TRONG transaction

```java
@Transactional
public void claim() {
    try {
        repository.saveAndFlush(...);
    } catch (DataIntegrityViolationException e) {
        // nuốt exception
    }
}
```

```
Exception
   ↓ rollback-only
   ↓ catch + nuốt
   ↓ method return BÌNH THƯỜNG
   ↓ Spring: "không exception ⇒ COMMIT"
   ↓ COMMIT một tx mang dấu rollback-only
   ↓ 💥 UnexpectedRollbackException
```

```
Method nói:       SUCCESS
Transaction nói:  FAILED
```

### ✅ Catch bên NGOÀI transaction

```java
@Transactional
public Record claim() {
    return repository.saveAndFlush(...);
}
```

```java
try {
    claim();
} catch (DataIntegrityViolationException e) {
    handleDuplicate();
}
```

```
Exception
   ↓ thoát @Transactional
   ↓ Spring: "có exception ⇒ ROLLBACK"
   ↓ transaction kết thúc ĐÚNG CÁCH
   ↓ exception GỐC bay ra ngoài
   ↓ business layer catch
```

Không còn mâu thuẫn:

```
Method:          FAILED
Transaction:     ROLLBACK
Business layer:  đã biết lỗi → xử lý
```

### 4.1 "Kết thúc đúng cách" nghĩa là gì? (chính xác hoá)

⚠️ **Về mặt DỮ LIỆU, hai đường giống hệt nhau** — cả hai đều rollback, connection đều trả
về pool, không rò rỉ gì. Đừng tưởng bản hỏng làm bẩn dữ liệu.

Khác biệt thật nằm ở **exception nào sống sót**:

| | catch **trong** | catch **ngoài** |
|---|---|---|
| Spring làm gì cuối tx | thử **COMMIT** → thất bại → rồi rollback | **ROLLBACK** thẳng |
| Exception bay lên trên | `UnexpectedRollbackException` | `DataIntegrityViolationException` |
| Còn biết **nguyên nhân** không? | ❌ mất — lỗi gốc chỉ còn trong log | ✅ giữ nguyên |
| Code gọi xử lý được không? | ❌ không phân biệt được duplicate hay lỗi khác | ✅ rẽ được nhánh replay/409/422 |
| Dữ liệu trong DB | rollback | rollback |

Ba ý của "đúng cách":

1. **Spring không bị yêu cầu làm việc bất khả thi** — không có bước COMMIT thất bại.
2. **Exception gốc không bị thay thế.** Đây là thiệt hại lớn nhất:
   `UnexpectedRollbackException` **che mất** `duplicate key value violates unique constraint`.
   Người debug nhìn stack trace chỉ thấy "transaction bị rollback", phải lục log phía trước
   mới ra nguyên nhân.
3. **Không có trạng thái mâu thuẫn** giữa "method nói SUCCESS" và "transaction nói FAILED".

Hệ quả nghiệp vụ: chỉ khi giữ được `DataIntegrityViolationException` thì tầng trên mới
**phân biệt được** "đụng khoá idempotency" với "lỗi hệ thống" — để trả 200/409/422 thay vì 500.

### ⚠️ "Ngoài" nghĩa là ngoài **MỌI** `@Transactional`

Đây là chỗ sai tinh vi: nếu business layer **cũng** có `@Transactional` thì nó **tham gia**
cùng transaction ⇒ vẫn nằm trong cái đã hỏng ⇒ vẫn nổ (và nếu định đọc lại dữ liệu thì
PostgreSQL còn chặn luôn — xem §7).

Đó là lý do `PaymentService` **cố ý KHÔNG** có `@Transactional`.

---

## 5. NGOẠI LỆ: khi nào catch bên trong vẫn ổn?

Quy tắc không phải "cấm catch trong transaction". Nó là **"đừng nuốt rồi return bình thường"**.

### ✅ Catch để ĐỔI TÊN lỗi, rồi NÉM TIẾP

```java
@Transactional
public Payment create(...) {
    try {
        return payments.saveAndFlush(payment);
    } catch (DataIntegrityViolationException conflict) {
        throw new ActivePaymentExistsException(conflict);   // ✅ THROW, không return
    }
}
```

Vẫn có exception bay ra ⇒ Spring vẫn rollback ⇒ **không có cú commit nào để nổ**.
Khác bản hỏng đúng **một chữ**: `throw` thay vì `return`.

### ✅ Catch để dọn dẹp rồi ném tiếp (`try/finally`, log, metric)

Miễn là cuối cùng exception vẫn thoát ra.

### ✅ Operation lỗi nằm ở transaction RIÊNG

Nếu chỗ lỗi chạy trong `REQUIRES_NEW` của **bean khác**, nó rollback **riêng** — transaction
gọi nó không bị đánh dấu ⇒ catch bình thường.

### ✅ `Propagation.NESTED` (SAVEPOINT)

Với JDBC transaction manager, `NESTED` tạo **savepoint** ⇒ rollback về savepoint thay vì hủy
cả transaction ⇒ catch trong tx rồi đi tiếp được. Spring **không** tự tạo savepoint cho
từng statement, phải khai báo.

---

## 6. `rollback-only` và `ROLLBACK` khác nhau

```
rollback-only  =  "Không được COMMIT nữa"        → một TRẠNG THÁI
ROLLBACK       =  "Hủy các thay đổi, kết thúc"   → một HÀNH ĐỘNG
```

```
DB exception
    ↓ transaction = rollback-only
    ↓ Spring kết thúc transaction
    ↓ ROLLBACK
```

### `rollback-only` chặn cái gì?

| | rollback-only chặn? |
|---|---|
| `SELECT` sau đó | ❌ **không** — vẫn chạy được |
| `COMMIT` | ✅ **có** → `UnexpectedRollbackException` |

⚠️ Đừng lẫn với **PostgreSQL abort transaction**: sau một lệnh SQL lỗi, PostgreSQL hủy cả
transaction ở phía DB, lúc đó **mọi lệnh đều bị từ chối, kể cả SELECT**:

```
ERROR: current transaction is aborted, commands ignored until end of transaction block
SQLSTATE 25P02
```

Hai cơ chế **độc lập**, cùng dẫn tới một kết luận: **sau lỗi ghi, transaction đó chỉ còn một
việc hợp lệ — ROLLBACK.**

---

## 7. `UnexpectedRollbackException` thực chất nói gì?

Nó **không phải nguyên nhân ban đầu**, nó là **hậu quả**:

```
Exception ban đầu
    ↓ transaction bị rollback-only
    ↓ exception bị catch/nuốt bên trong
    ↓ method return bình thường
    ↓ Spring phát hiện transaction không thể commit
    ↓ UnexpectedRollbackException
```

Nó có nghĩa:

> *"Method của bạn có vẻ đã thành công, nhưng transaction thực tế đã bị đánh dấu
> rollback-only và không thể commit."*

**Nó luôn che mất lỗi thật.** Lỗi gốc (`duplicate key ...`) nằm ở log trước đó, không nằm
trong stack trace của `UnexpectedRollbackException`.

---

## 8. Cách tư duy khi gặp vấn đề này

Thấy `UnexpectedRollbackException` thì **đừng** hỏi *"tại sao Spring ném cái này?"*.
Truy ngược:

```
Exception gốc là gì?                      (tìm trong log TRƯỚC đó)
   ↓
Exception xảy ra bên trong transaction nào?
   ↓
Transaction có bị rollback-only không?
   ↓
Exception có bị catch/nuốt bên trong transaction không?
   ↓
Tại sao method vẫn return bình thường?
```

Gần như luôn ra đúng pattern:

```
Exception A → catch bên trong → nuốt A → rollback-only
            → method return bình thường → UnexpectedRollbackException
```

### Bật log để nhìn thấy ranh giới transaction

```yaml
logging:
  level:
    org.springframework.transaction.interceptor: TRACE
```
Sẽ thấy `Getting transaction for [...]` / `Completing transaction for [...]` — biết chính xác
tx nào mở ở đâu, đóng ở đâu.

---

## 9. Bằng chứng thực nghiệm (Increment 3)

Lấy code đang chạy **29/29 test xanh**, chỉ đổi vị trí `catch` từ NGOÀI vào TRONG:

| | Trước (catch ngoài) | Sau (catch trong) |
|---|---|---|
| `replayReturnsIdenticalBody` | **200** ✅ | **500** ❌ |
| `sameKeyDifferentBodyIsRejected` | **422** ✅ | **500** ❌ |
| `concurrentSameKeyCreatesExactlyOnePayment` | 1×201 + 9×(200/409) ✅ | 1×201 + **0/9** ❌ |

Exception thật lấy từ log:
```
org.springframework.transaction.UnexpectedRollbackException:
  Transaction silently rolled back because it has been marked as rollback-only
```

→ **Toàn bộ tính năng idempotency ngừng hoạt động**. Không phải bug ở rìa —
hỏng chức năng chính.

---

## 10. Một câu chốt

**Vấn đề:** exception bị catch bên trong làm transaction `rollback-only`, nhưng method vẫn
tiếp tục như thành công.

**Giải pháp:** để exception thoát khỏi `@Transactional`, để Spring rollback, rồi catch ở
**ngoài mọi transaction** để xử lý nghiệp vụ.

**Tại sao:** transaction layer quyết định *commit hay rollback*; business layer quyết định
*sau khi rollback thì làm gì*.

```
        TRANSACTION LAYER
              │
        DB operation
              │
          exception
              │
           ROLLBACK
              │
              ▼
        BUSINESS LAYER
              │
        xử lý nghiệp vụ
```

> **Trong transaction thì NÉM. Ngoài transaction thì BẮT.**
> Và bạn catch không phải để "xử lý lỗi" — mà để quyết định **chỗ nào bắt đầu lại**;
> chỗ đó bắt buộc phải nằm **ngoài cái đã hỏng**.

Đây là pattern bắt buộc khi xử lý **idempotency / duplicate / constraint violation** và mọi
lỗi DB trong Spring.

---

## 11. Checklist nhanh

- [ ] Method `@Transactional` có `catch` nào **nuốt rồi `return`** không? → sửa thành `throw`.
- [ ] Chỗ catch cuối cùng có nằm **ngoài mọi** `@Transactional` không?
- [ ] Cần transaction riêng → gọi **qua bean khác** (`this.` không có tác dụng).
- [ ] Cần bắt lỗi constraint **tại chỗ** → `saveAndFlush`, không `save`.
- [ ] Thấy `UnexpectedRollbackException` → đi tìm **exception gốc trong log trước đó**.
- [ ] Test **đồng thời**, không chỉ tuần tự — pattern này chỉ nổ khi có đụng độ thật.
