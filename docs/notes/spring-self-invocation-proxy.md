# Ghi chú — Self-invocation: vì sao `@Transactional` (và bạn bè) bị bỏ qua

> Cái bẫy Spring kinh điển. Gặp thật khi fix race idempotency ở Increment 2.
> Liên quan: [spring-transaction-proxy-and-abort.md](spring-transaction-proxy-and-abort.md)

---

## 1. VẤN ĐỀ

### Hiểu nhầm phổ biến
> "Đặt `@Transactional` lên method là method đó được bọc rồi. Gọi từ đâu chả như nhau."

**SAI.** Hệ quả: annotation **im lặng không có tác dụng** — không lỗi biên dịch, không
warning, không exception. Code chạy "bình thường" nhưng **không có transaction** (hoặc
không có cache/retry/async…). Bug rất khó thấy vì **không có dấu hiệu gì cả**.

### Triệu chứng thường gặp
- `@Transactional` không rollback khi lỗi.
- Tưởng mỗi vòng lặp một transaction riêng, thực ra **không có transaction nào**.
- `@Async` chạy **đồng bộ**.
- `@Cacheable` **không cache**, lần nào cũng gọi thật.
- `@Retryable` **không retry**.

---

## 2. CƠ CHẾ — proxy bọc OBJECT, không bọc METHOD

Spring **không sửa code của bạn**. Nó tạo **một object mới** (proxy) đứng trước object thật
(target), và **chỉ inject proxy** cho các bean khác.

`@Transactional` chỉ là **tấm biển đánh dấu** "method này cần tx". **Người thực thi** là
**interceptor nằm trong proxy**. Không đi qua proxy ⇒ không ai đọc tấm biển.

### Thứ Spring thực sự tạo ra (đại ý)

```java
// (1) Object THẬT của bạn — Spring KHÔNG đụng vào
class MyService {
    void outer() {
        this.inner();                 // gọi nội bộ
    }
    @Transactional
    void inner() { ... }
}

// (2) Object Spring TẠO THÊM lúc runtime
class MyService$$Proxy extends MyService {
    private final MyService target;

    @Override void inner() {
        tx.begin();                               // ← INTERCEPTOR CHỈ NẰM Ở ĐÂY
        try { target.inner(); tx.commit(); }
        catch (e) { tx.rollback(); throw e; }
    }

    @Override void outer() {
        target.outer();                           // ← nhảy thẳng vào object THẬT
    }                                              //   bên trong: this = target (KHÔNG phải proxy)
}
```

**Điểm chốt:** khi `outer()` chạy, nó đang chạy **bên trong `target`**. Ở đó `this` = `target`,
nên `this.inner()` gọi **`target.inner()`** — **bản trần, không có `tx.begin()`**.
Bản `inner()` có transaction (override ở proxy) **không bao giờ được chạm tới**.

### Sơ đồ

```
Gọi TỪ BEAN KHÁC                         Gọi NỘI BỘ (self-invocation)
──────────────────                        ────────────────────────────
other → [MyService$$Proxy]                 outer() đang chạy trong target
              │ interceptor: tx.begin()        │
              ▼                                └─► this.inner()
         target.inner()                              ↑ this = target
              ✅ CÓ transaction                      ❌ KHÔNG qua interceptor
                                                     ❌ KHÔNG có transaction
```

### Ẩn dụ
Proxy = **lễ tân ở CỬA**. `@Transactional` = **nội quy: "mở sổ giao dịch trước khi cho vào"**.
- Người **ngoài** vào gặp anh B → **qua lễ tân** → mở sổ ✅
- Anh A đang **ở trong phòng**, quay sang bàn anh B (`this.`) → **không qua lễ tân** → không sổ ❌

> **Câu thần chú:** *`@Transactional` là nội quy ở CỬA; `this.` là đi CỬA SAU.*

---

## 3. VÍ DỤ MINH HOẠ

### Ví dụ A — tưởng "mỗi vòng lặp một transaction" (rất hay gặp)

```java
@Service
public class ReportService {

    public void generateAll(List<Report> list) {
        for (Report r : list) {
            this.generateOne(r);        // ❌ @Transactional BỊ BỎ QUA
        }
    }

    @Transactional
    public void generateOne(Report r) {
        repo.save(...);                  // chạy KHÔNG có transaction
    }
}
```
**Kỳ vọng:** mỗi report một tx riêng, cái nào lỗi thì chỉ cái đó rollback.
**Thực tế:** **không có transaction nào cả** → lỗi giữa chừng để lại dữ liệu dang dở.

**Sửa:**
```java
@Service
public class ReportWriter {                     // ← BEAN RIÊNG
    @Transactional
    public void generateOne(Report r) { repo.save(...); }
}

@Service
public class ReportService {
    private final ReportWriter writer;           // inject bean khác (thực chất là proxy)

    public void generateAll(List<Report> list) {
        for (Report r : list) writer.generateOne(r);   // ✅ mỗi lần một tx riêng
    }
}
```

### Ví dụ B — case thật của dự án (Increment 2)

**Sai:**
```java
@Service
public class OrderService {
    public PlaceOrderResult placeOrder(String key, Request req) {
        try {
            return new PlaceOrderResult(this.create(key, req), true);  // ❌ this. → không tx riêng
        } catch (DataIntegrityViolationException race) {
            return orders.findByIdempotencyKey(key)...;  // ❌ chạy trong tx ĐÃ ABORT → 500
        }
    }

    @Transactional                     // ⚠️ vô hiệu
    public Order create(...) { return orders.saveAndFlush(order); }
}
```
→ Thực nghiệm: 10 request đồng thời ⇒ **9 × HTTP 500**.

**Đúng:**
```java
@Service
public class OrderCreator {                    // ← BEAN RIÊNG
    @Transactional                              // ✅ có hiệu lực
    public Order create(...) { return orders.saveAndFlush(order); }
}

@Service
public class OrderService {
    private final OrderCreator creator;

    public PlaceOrderResult placeOrder(String key, Request req) {   // KHÔNG @Transactional
        try {
            return new PlaceOrderResult(creator.create(key, req), true);  // ✅ tx RIÊNG
        } catch (DataIntegrityViolationException race) {
            return orders.findByIdempotencyKey(key)...;   // ✅ tx SẠCH
        }
    }
}
```
→ Thực nghiệm sau fix: **1 × 201 + 9 × 200, 0 × 500**.

---

## 4. KHÔNG CHỈ `@Transactional` — mọi annotation dựa trên proxy

Cùng một bẫy, vì tất cả đều chạy bằng **Spring AOP proxy**:

| Annotation | Self-invocation thì… |
|---|---|
| `@Transactional` | không có transaction |
| `@Async` | chạy **đồng bộ** (không sang thread khác) |
| `@Cacheable` / `@CacheEvict` | **không** cache / không evict |
| `@Retryable` | **không** retry |
| `@PreAuthorize` / `@Secured` | **không** kiểm quyền |
| `@Validated` | **không** validate |

→ Quy tắc chung: **annotation AOP chỉ có tác dụng khi lời gọi vượt qua ranh giới bean.**

---

## 5. CÁC TRƯỜNG HỢP KHÁC CŨNG LÀM ANNOTATION VÔ HIỆU

Ngoài self-invocation, proxy còn "bó tay" khi:

| Trường hợp | Vì sao |
|---|---|
| **Class KHÔNG phải bean** (không `@Service`/`@Component`, không khai `@Bean`) | Spring không biết nó tồn tại → **không tạo proxy** → annotation chỉ là chữ trang trí |
| **Instance tự `new`** (dù class CÓ `@Service`) | bean trong container là một instance **khác** đã bọc proxy; bản bạn `new` nằm ngoài tầm Spring |
| Method **`private`** | proxy (CGLIB) **không override được** → annotation vô nghĩa |
| Method **`final`** / class **`final`** | CGLIB tạo proxy bằng **kế thừa** → không override được |
| Method **`static`** | không thuộc instance → không proxy được |
| Gọi từ **constructor** / `@PostConstruct` | proxy chưa sẵn sàng |

```java
@Service
public class OrderHelper { @Transactional public void doIt() {...} }   // ✅ là bean

@Service
class Caller {
    void run() {
        var h = new OrderHelper();   // ❌ instance TỰ NEW → không phải bean → không proxy
        h.doIt();                     // ❌ KHÔNG có transaction
    }                                 // đúng: inject OrderHelper rồi gọi
}
```

→ **Annotation trên class không quyết định — cái quyết định là instance này có phải bean do Spring tạo không.**

### ✅ Checklist: `@Transactional` có tác dụng khi nào

| # | Điều kiện | Hỏng nếu |
|---|---|---|
| 1 | Class là **bean** do Spring tạo | tự `new`, hoặc class không được scan |
| 2 | **Instance** đang dùng là bean **được inject** | `new OrderHelper()` |
| 3 | Method **`public`**, không **`final`**, không **`static`** | CGLIB không override được |
| 4 | Lời gọi **từ bên ngoài, qua proxy** | `this.method()` (self-invocation) |
| 5 | Có **transaction manager** + bật quản lý tx | Spring Boot tự lo với starter-data-jpa |

Sai bất kỳ dòng nào → annotation **im lặng vô hiệu** (không lỗi, không warning).

### Quy tắc gốc (gộp mọi trường hợp)

> **Spring AOP chỉ tác động lên object do Spring TẠO + QUẢN LÝ, và chỉ khi lời gọi ĐI QUA proxy đó.**
> - Không phải bean → **không có proxy** để đi qua.
> - Self-invocation → có proxy nhưng **đi vòng qua nó**.

---

## 6. GIẢI PHÁP (xếp theo mức khuyến nghị)

### ✅ (1) Tách sang bean khác — nên dùng
```java
@Service class Writer { @Transactional public void doIt() {...} }
@Service class Caller { private final Writer writer;  void run() { writer.doIt(); } }
```
Sạch, rõ trách nhiệm, không "ảo thuật". **Mặc định chọn cách này.**

### (2) Tự inject chính mình (self-injection)
```java
@Service
public class OrderService {
    @Autowired @Lazy
    private OrderService self;           // @Lazy để tránh vòng phụ thuộc lúc khởi động

    public void outer() { self.create(); }   // ✅ đi qua proxy
    @Transactional public void create() { ... }
}
```
Chạy được nhưng **khó đọc**, dễ khiến người sau bối rối.

### (3) `AopContext.currentProxy()`
```java
@EnableAspectJAutoProxy(exposeProxy = true)   // phải bật
...
((OrderService) AopContext.currentProxy()).create();
```
Rối, phụ thuộc cấu hình, ít dùng.

### (4) AspectJ load-time weaving
Sửa **bytecode** thật thay vì proxy → self-invocation **cũng hoạt động**. Nhưng nặng, cần
agent/cấu hình build → hiếm dùng trong ứng dụng thường.

---

## 7. CÁCH PHÁT HIỆN / KIỂM CHỨNG

**Bật log transaction:**
```yaml
logging:
  level:
    org.springframework.transaction.interceptor: TRACE
```
- Có tx: thấy `Getting transaction for [com...OrderCreator.create]`
- Bị bỏ qua: **không thấy** dòng đó cho method đáng lẽ phải có tx.

**In trực tiếp trong method:**
```java
TransactionSynchronizationManager.getCurrentTransactionName();      // null nếu không có tx
TransactionSynchronizationManager.isActualTransactionActive();      // false nếu không có tx
```

**Đọc code nhanh:** thấy `this.someAnnotatedMethod()` trong cùng class → **nghi ngay**.

---

## 8. KNOWLEDGE (nén)

> - **Proxy bọc cả OBJECT (bean), KHÔNG bọc từng method.** `@Transactional` chỉ là *tấm biển*;
>   *người thực thi* là interceptor trong **proxy đứng ở cửa**.
> - **Spring luôn inject PROXY** cho bean khác; nhưng **bên trong target, `this` = target**
>   ⇒ `this.method()` **bỏ qua proxy** ⇒ annotation **im lặng vô hiệu** (không lỗi, không warning).
> - **Áp dụng cho MỌI annotation AOP**: `@Transactional`, `@Async`, `@Cacheable`, `@Retryable`,
>   `@PreAuthorize`, `@Validated`.
> - Cũng vô hiệu với method **private / final / static**, hoặc gọi từ constructor.
> - **Giải pháp mặc định: tách sang bean khác.** (self-inject `@Lazy` / `AopContext` là phương án dự phòng.)
> - **Phát hiện:** log `transaction.interceptor=TRACE`, hoặc `TransactionSynchronizationManager`.
>
> **Câu thần chú:** *`@Transactional` là nội quy ở CỬA; `this.` là đi CỬA SAU.*
