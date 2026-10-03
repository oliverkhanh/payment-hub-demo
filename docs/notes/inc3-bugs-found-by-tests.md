# Ghi chú — 6 bug thật mà test bắt được khi code Increment 3

> Không có bug nào trong số này lộ ra nếu chỉ "chạy thử thấy 201 là xong".
> Đây là lý do CLAUDE.md viết *"Feature không test được coi như chưa xong"*.
> Thứ tự bên dưới là thứ tự chúng thực sự xuất hiện.

---

## Bug 1 — `jsonb` + `String` ⇒ 500 ngay ở request đầu tiên

```
ERROR: column "metadata" is of type jsonb but expression is of type character varying
```

Hibernate gửi tham số dạng `varchar`, PostgreSQL không tự cast sang `jsonb`.

```java
@JdbcTypeCode(SqlTypes.JSON)                       // ← thiếu dòng này
@Column(columnDefinition = "jsonb", updatable = false)
private String metadata;
```

**Bài học:** `columnDefinition` chỉ nói cho *DDL generator*, không nói cho *JDBC binder*.
Cột kiểu đặc thù của Postgres (`jsonb`, `uuid`, `inet`…) phải khai kiểu JDBC tường minh.

---

## Bug 2 — bắt `DataIntegrityViolationException` **BÊN TRONG** transaction ⇒ 500

Đây là **biến thể mới của bài học Increment 2**, và là bug khó nhất trong đợt này.

```java
@Transactional(propagation = REQUIRES_NEW)
public Optional<IdempotencyRecord> tryClaim(...) {
    try {
        return Optional.of(records.saveAndFlush(...));
    } catch (DataIntegrityViolationException dup) {
        return Optional.empty();          // ❌ return BÌNH THƯỜNG
    }
}
```

Nuốt exception rồi return bình thường ⇒ Spring tưởng mọi thứ ổn ⇒ **đi COMMIT**.
Nhưng PostgreSQL đã **ABORT** transaction đó sau lệnh INSERT lỗi (SQLSTATE `25P02`)
⇒ commit nổ ⇒ **500**.

**Sửa:** để exception **bay ra** (tx rollback gọn), bắt **NGOÀI** ranh giới transaction:

```java
// IdempotencyStore — KHÔNG try/catch
@Transactional(propagation = REQUIRES_NEW)
public IdempotencyRecord claim(...) {
    return records.saveAndFlush(new IdempotencyRecord(...));
}

// PaymentService — KHÔNG @Transactional ⇒ đây mới là chỗ bắt được
try {
    recordId = idempotency.claim(...).getId();
} catch (DataIntegrityViolationException alreadyClaimed) {
    return handleDuplicateKey(...);
}
```

> **Câu chốt:** *bắt lỗi ghi phải ở NGOÀI transaction đã chết.*
> Inc 2 là "đừng SELECT trong tx đã abort"; Inc 3 là "đừng COMMIT tx đã abort".
> Cùng một gốc: **sau lỗi ghi, transaction đó chỉ còn một việc hợp lệ — ROLLBACK.**

⚠️ Lưu ý phân biệt: `PaymentCreator.create` **có** try/catch trong tx, nhưng nó **ném ra**
một exception khác ⇒ Spring rollback ⇒ **không sao**. Nuốt-rồi-return mới chết.

---

## Bug 3 — filter `@Component` bị Spring Boot đăng ký cho **MỌI** đường dẫn

Triệu chứng: `GET /checkout/{token}` trả **401**, dù `SecurityConfig` không hề gắn filter
HMAC vào chuỗi `/checkout/**`.

Nguyên nhân: Spring Boot **tự** đăng ký mọi bean kiểu `Filter` thành servlet filter map `/*`.
Nên filter chạy **hai lần**: một lần ở ngoài (áp cho tất cả), một lần trong chuỗi `/v1/**`.

```java
@Bean
FilterRegistrationBean<MerchantHmacAuthenticationFilter> disableAutoRegistration(
        MerchantHmacAuthenticationFilter filter) {
    var registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);          // ← chỉ sống trong SecurityFilterChain
    return registration;
}
```

**Bài học:** `addFilterBefore(...)` **thêm** filter vào chuỗi security, nó **không** giới hạn
filter vào chuỗi đó. Muốn giới hạn thì phải **tắt auto-registration**.

---

## Bug 4 — `/error` yêu cầu xác thực ⇒ mọi lỗi 500 bị **che thành 401**

Đây là bug **làm mù việc debug**, tệ hơn bản thân nó.

Khi controller ném exception, Tomcat **dispatch nội bộ** sang `/error`. Đường dẫn đó rơi vào
chuỗi `anyRequest().authenticated()` ⇒ trả **401 thân rỗng**. Bug thật (Bug 5) bị giấu hoàn toàn.

```java
.requestMatchers("/error").permitAll()
```

**Bài học:** thấy **401 mà không có `WWW-Authenticate` hợp lý / thân rỗng** ở một endpoint
đáng lẽ public ⇒ nghi ngay `/error` bị chặn, đừng đi tìm lỗi xác thực.

---

## Bug 5 — `LazyInitializationException` (Increment 2 lặp lại y nguyên)

`loadForCheckout()` là `@Transactional`, trả về `Payment`. Template Thymeleaf đọc
`payment.merchant.name` **sau khi transaction đã đóng** (`open-in-view=false`) ⇒ nổ.

```java
@EntityGraph(attributePaths = "merchant")
Optional<Payment> findByCheckoutToken(String checkoutToken);
```

**Bài học:** hệt bug `order.items` ở Inc 2. Quy tắc: **entity đi ra khỏi transaction thì mọi
thứ view cần phải đã được nạp** — bằng `@EntityGraph`, hoặc map sang DTO **trong** tx.

---

## Bug 6 (môi trường test) — `@Container` + Spring context cache ⇒ DB rỗng

Triệu chứng: test class **đầu tiên** xanh, các class sau **mọi request 401**.

`@Container` (static field, `@Testcontainers` trên abstract class) ⇒ extension dựng
**container MỚI cho từng test class**. Nhưng Spring context được **CACHE** dùng lại
⇒ Flyway **không chạy lại** ⇒ DB mới rỗng, không có merchant seed ⇒ `findByApiKey` rỗng ⇒ 401.

```java
// SINGLETON CONTAINER — không @Container, không @Testcontainers
static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")...;
static { POSTGRES.start(); }
```

**Bài học:** vòng đời container phải **≥** vòng đời Spring context. Ngược lại là lệch.

### Phụ: hai vấn đề môi trường khác

| Vấn đề | Triệu chứng | Sửa |
|---|---|---|
| Docker Engine 29 từ chối API < 1.44 | `Could not find a valid Docker environment` + HTTP 400 ở `/info` | surefire `<systemPropertyVariables><api.version>1.44</api.version>` |
| Thay `RestTemplate` request factory | status đúng nhưng `getBody()` **null** dù `Content-Length: 203` | **đừng** thay factory của `TestRestTemplate` (nó tự lo redirect + đọc được thân lỗi) |

---

## Tổng kết — test nào bắt bug nào

| Bug | Bắt bởi | Test tuần tự đơn giản có bắt được? |
|---|---|---|
| 1 jsonb | bất kỳ test tạo payment | ✅ (request đầu là lộ) |
| 2 commit tx đã abort | **replay** + **10 request đồng thời** | ❌ — chỉ nổ khi đụng UNIQUE |
| 3 filter auto-register | test trang checkout | ❌ — nếu chỉ test `/v1/**` thì vô hình |
| 4 `/error` bị chặn | (lộ ra khi tìm Bug 5) | ❌ |
| 5 lazy loading | test **render** trang checkout | ❌ — nếu chỉ test API JSON thì vô hình |
| 6 container/context | chạy **cả suite** | ❌ — chạy một class thì xanh |

> ⭐ Bug 3, 5, 6 chỉ lộ ra khi test **nhiều loại client** (API + trình duyệt) và chạy
> **cả suite**, không phải một test lẻ. Bug 2 chỉ lộ khi test **đồng thời**.
> Đúng bài học Inc 2: **test tuần tự, chạy lẻ, xanh — không chứng minh được gì.**
