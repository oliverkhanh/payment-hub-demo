# Ghi chú — Transaction, Proxy & Idempotency race (Increment 2)

> Ba vấn đề lồng nhau gặp thật khi làm `POST /v1/orders` (merchant-demo), kèm bằng chứng
> thực nghiệm. Đọc kèm: [increment-2 code] `domain/OrderService.java`, `domain/OrderCreator.java`.

---

## 0. Bối cảnh

Cần chống trùng khi khách bấm "Thanh toán" nhiều lần / client retry. Cơ chế:
**Idempotency-Key** do client sinh → server lưu vào `orders.idempotency_key` có **UNIQUE**.
Khi 2 request cùng key tới **đồng thời**, một thằng thắng, thằng thua phải **trả về đơn của
thằng thắng** (HTTP 200), chứ không được lỗi và không được tạo đơn thứ 2.

Chính chỗ "thằng thua đọc lại đơn" làm lộ ra 3 vấn đề dưới.

---

## VẤN ĐỀ 1 — SQL chạm DB lúc nào? (`save` vs `saveAndFlush`)

### Vấn đề
Hibernate **ghi trễ (write-behind)**: `save()` chỉ đưa entity vào persistence context,
SQL **INSERT có thể bị hoãn tới lúc COMMIT**. Mà vi phạm `UNIQUE` **chỉ nổ khi INSERT
thực sự chạm DB**.

→ Nếu INSERT bị hoãn tới commit, exception nổ **SAU KHI method đã return** — **ngoài khối
`try/catch`** → không bắt được → 500.

```java
try {
    orders.save(order);          // ❌ có thể hoãn INSERT tới commit
} catch (DataIntegrityViolationException e) {
    // ... KHÔNG BAO GIỜ chạy, vì lỗi nổ ở commit (ngoài try)
}
```

### Giải pháp
`saveAndFlush()` = `save()` + **`flush()` ngay** → ép INSERT chạm DB **trong khối try** →
vi phạm UNIQUE nổ **tại chỗ** → bắt được.

```java
try {
    orders.saveAndFlush(order);  // ✅ INSERT chạy NGAY trong try
} catch (DataIntegrityViolationException race) {
    // ✅ bắt được, xử lý race ở đây
}
```

### Ghi chú
- **FLUSH xảy ra khi:** commit · gọi `flush()`/`saveAndFlush()` · auto-flush trước một query.
- **Ngoại lệ IDENTITY:** với `@GeneratedValue(IDENTITY)`, Hibernate **buộc INSERT ngay**
  lúc save (để lấy id auto-increment). Nên `save()` *tình cờ* cũng chạy — nhưng **mong manh**:
  đổi sang `SEQUENCE` (có batching) là catch **âm thầm hỏng**. → Luôn dùng `saveAndFlush`
  khi cần bắt lỗi constraint tại chỗ.

---

## VẤN ĐỀ 2 — Sau lỗi DB, transaction bị ABORT (không query tiếp được)

### Phân biệt 2 khái niệm hay bị trộn

| | Cái gì | SELECT sau đó |
|---|---|---|
| **`rollback-only`** (cờ của Spring/JPA, vd do `setRollbackOnly()`) | chỉ đánh dấu "không được commit" | ✅ **vẫn chạy được**; chỉ **COMMIT** ném `UnexpectedRollbackException` |
| **Transaction bị DB ABORT** (sau một lệnh SQL lỗi) | PostgreSQL hủy cả transaction | ❌ **mọi lệnh bị từ chối**, kể cả SELECT |

→ **Không phải "rollback-only chặn SELECT"** — mà là **DB abort** mới chặn.

### Vấn đề
PostgreSQL: **một lệnh trong transaction lỗi → cả transaction vào trạng thái "aborted"**.
Mọi lệnh tiếp theo (SELECT cũng vậy) bị từ chối:

```
ERROR: current transaction is aborted, commands ignored until end of transaction block
SQLSTATE 25P02
```

...cho tới khi `ROLLBACK`. Thêm nữa, phía Hibernate: sau flush lỗi, **session hỏng**, và
derived-query còn **auto-flush** → có thể retry lại chính cái INSERT hỏng.

→ Nên đoạn recovery này **chết** nếu nằm trong **cùng transaction** với cái INSERT lỗi:

```java
catch (DataIntegrityViolationException race) {
    orders.findByIdempotencyKey(key);   // ❌ tx đã abort → 25P02 → 500
}
```

### Giải pháp
**Recovery phải chạy trong một transaction MỚI** (DB transaction sạch, chưa abort;
persistence context mới, không còn INSERT hỏng đang chờ).

### Ghi chú
- Đây là hành vi **đặc thù PostgreSQL** (hủy cả tx). Muốn recover **trong cùng tx** thì phải
  bọc lệnh ghi bằng **SAVEPOINT** (rollback tới savepoint thay vì hủy cả tx) — Spring mặc
  định **không** tự tạo savepoint cho từng statement.

---

## VẤN ĐỀ 3 — Self-invocation: `@Transactional` bị bỏ qua

### Hiểu nhầm phổ biến
> "Đặt `@Transactional` lên method là method đó được bọc proxy rồi, gọi ở đâu cũng như nhau."

**SAI.** **Proxy bọc cả OBJECT (bean), không bọc từng method.** `@Transactional` chỉ là
**tấm biển** nói "method này cần tx"; **người thực thi** là proxy đứng **ở cửa**. Không đi
qua cửa thì không ai đọc tấm biển.

### Nhìn vào thứ Spring thực sự tạo

```java
// (1) Object THẬT của bạn — Spring KHÔNG sửa
class OrderService {
    void placeOrder() {
        this.create();            // gọi nội bộ
    }
    @Transactional
    void create() { ... }
}

// (2) Object Spring TẠO THÊM lúc runtime (proxy), đại ý:
class OrderService$$Proxy extends OrderService {
    private final OrderService target;

    @Override void create() {
        tx.begin();                          // ← interceptor CHỈ ở đây
        try { target.create(); tx.commit(); }
        catch (e) { tx.rollback(); throw e; }
    }

    @Override void placeOrder() {
        target.placeOrder();                 // ← nhảy vào object THẬT
    }                                         //   trong đó this = target, KHÔNG phải proxy
}
```

→ Khi `placeOrder` chạy, nó ở **bên trong `target`**, nên `this.create()` gọi
**`target.create()`** — bản **trần, không có `tx.begin()`**. Bản `create()` có transaction
(override ở proxy) **không bao giờ được chạm tới**.

### Bảng chốt

| Gọi từ đâu | Tham chiếu | Qua interceptor? | Có tx mới? |
|---|---|---|---|
| Bean khác: `creator.create()` | **proxy** (Spring luôn inject proxy) | ✅ | ✅ |
| Cùng class: `this.create()` | **target** | ❌ | ❌ |

### Ẩn dụ
Proxy = **lễ tân ở cửa**; `@Transactional` = **nội quy "mở sổ giao dịch trước khi cho vào"**.
Người ngoài vào → qua lễ tân → mở sổ ✅. Anh A đang **ở trong phòng** quay sang bàn anh B
(`this.`) → **không qua lễ tân** → không sổ ❌.

**Câu thần chú:** *`@Transactional` là nội quy ở CỬA; `this.` là đi cửa sau.*

---

## GIẢI PHÁP TỔNG — tách bean

Ba vấn đề trên gộp lại thành một cách sửa:

```java
@Service
public class OrderCreator {                  // ← BEAN RIÊNG
    @Transactional                            // ✅ có hiệu lực (gọi từ ngoài, qua proxy)
    public Order create(String key, Request req) {
        ...
        return orders.saveAndFlush(order);    // ép INSERT ngay; UNIQUE nổ TRONG tx này
    }
}

@Service
public class OrderService {
    private final OrderCreator creator;       // inject BEAN KHÁC (thực chất là proxy)

    public PlaceOrderResult placeOrder(String key, Request req) {   // KHÔNG @Transactional
        // lớp 1 — check-first (ca thường)
        var replay = orders.findByIdempotencyKey(key);
        if (replay.isPresent()) return new PlaceOrderResult(replay.get(), false);

        try {
            // lớp 2 — UNIQUE constraint là chốt chặn atomic
            return new PlaceOrderResult(creator.create(key, req), true);   // tx RIÊNG
        } catch (DataIntegrityViolationException race) {
            // lớp 3 — recovery ở tx SẠCH (placeOrder không nằm trong tx đã abort)
            return orders.findByIdempotencyKey(key)
                    .map(o -> new PlaceOrderResult(o, false))
                    .orElseThrow(() -> race);
        }
    }
}
```

**Vì sao chạy:**
1. `creator.create()` qua **proxy khác bean** → **tx riêng** (Vấn đề 3 giải quyết).
2. `saveAndFlush` → UNIQUE nổ **trong** tx riêng đó (Vấn đề 1 giải quyết).
3. Tx riêng **rollback gọn**; `placeOrder` **không nằm trong** nó → recovery SELECT chạy ở
   **tx sạch** (Vấn đề 2 giải quyết).

### Idempotency đầy đủ = 3 lớp

| Lớp | Cơ chế | Lo ca nào |
|---|---|---|
| 1 | `check-first` (đọc key đầu method) | ca **thường**: retry tuần tự sau khi đã xong |
| 2 | `UNIQUE(idempotency_key)` | ca **đồng thời**: chốt atomic ở DB, chỉ 1 đơn ra đời |
| 3 | recovery đọc lại (tx sạch) | ca **race**: thằng thua trả về đơn của thằng thắng |

Hành vi đúng: request lặp nhận **HTTP 200 + CHÍNH đơn cũ** (không lỗi, không tạo mới).

---

## BẰNG CHỨNG THỰC NGHIỆM

Bắn **10 request đồng thời** cùng một `Idempotency-Key`:

| | Trước fix | Sau fix |
|---|---|---|
| HTTP 201 (tạo) | 1 | **1** |
| HTTP 200 (replay) | 0 | **9** |
| **HTTP 500 (lỗi)** | **9** ❌ | **0** ✅ |
| Số đơn trong DB | 1 | **1** ✅ |

→ Bài học lớn: **test tuần tự XANH không chứng minh được gì về race.** Phải test **đồng thời**.
(Test tuần tự chỉ chạm lớp 1 — check-first — không bao giờ vào nhánh catch.)

---

## CÁCH TỰ KIỂM CHỨNG

Bật log transaction:
```yaml
logging:
  level:
    org.springframework.transaction.interceptor: TRACE
```
- Bản **tách bean**: thấy `Getting transaction for [...OrderCreator.create]` → tx mới được mở.
- Bản **self-invocation**: **không** thấy dòng đó → annotation bị bỏ qua.

Hoặc in trong method: `TransactionSynchronizationManager.getCurrentTransactionName()`.

---

## CHECKLIST nhớ nhanh

- [ ] Cần bắt lỗi constraint tại chỗ → dùng **`saveAndFlush`**, không `save`.
- [ ] Sau **lỗi ghi**, **đừng query tiếp trong cùng tx** (PG abort, 25P02) → recovery ở **tx mới**.
- [ ] Muốn tx mới → **gọi qua bean khác** (`this.` không có tác dụng).
- [ ] `rollback-only` ≠ chặn đọc; nó chặn **commit**. Cái chặn đọc là **DB abort**.
- [ ] Idempotency = **check-first + UNIQUE + recovery**, trả **200 + kết quả cũ**.
- [ ] **Luôn test đồng thời**, không chỉ tuần tự.

## Các cách thay thế (biết để nhận ra)

| Nhu cầu | Cách |
|---|---|
| Tx mới | **tách bean** ✅ · self-inject `@Lazy` · `AopContext.currentProxy()` |
| Recover trong **cùng** tx | **SAVEPOINT** (`Propagation.NESTED` với JDBC tx manager) |
| Chống race kiểu khác | distributed lock (Redis) theo key — bi quan, chậm hơn |
