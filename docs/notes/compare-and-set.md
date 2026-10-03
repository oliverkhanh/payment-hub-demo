# Ghi chú — Compare-and-set (CAS) và pessimistic lock

> Cách làm đúng chỗ "kiểm tra rồi mới ghi" khi có nhiều luồng chạy song song.
> Pattern này xuất hiện **4 lần** trong dự án — nhận ra nó một lần là dùng được mãi.
> Đọc kèm: [spring-transactional-catch-boundary.md](spring-transactional-catch-boundary.md) ·
> [bank-call-timeout-and-retry.md](bank-call-timeout-and-retry.md)

---

## 0. Bài toán: "kiểm tra rồi mới làm" (check-then-act)

Mọi đoạn code có hình dạng này đều là một quả bom hẹn giờ:

```java
long balance = select balance ...;        // ① ĐỌC
if (balance >= amount) {                  // ② QUYẾT ĐỊNH dựa trên cái vừa đọc
    update ... set balance = balance - amount;   // ③ GHI
}
```

Giữa ① và ③ có **khe hở thời gian**. Luồng khác lọt vào đó thì điều đã kiểm ở ② không còn
đúng lúc ③ chạy.

### Thí nghiệm thật (Postgres 16, 20 luồng song song)

> Chạy lại: `./scripts/cas-experiment.sh` (cần `docker compose` đang chạy ở `infra/`).

Tài khoản có **100.000đ**, 20 người cùng lúc rút **10.000đ**.
Đúng ra: 10 người rút được, 10 bị từ chối, còn **0đ**.

| Cách làm | Rút thành công | Số dư còn lại |
|---|---|---|
| Đọc rồi ghi (`if` trong Java) | **20/20** ❌ | **90.000đ** ❌ |
| `SELECT … FOR UPDATE` **trong** transaction | 10/20 ✅ | 0đ ✅ |
| `SELECT … FOR UPDATE` **quên** transaction | **20/20** ❌ | **80.000đ** ❌ |
| **Compare-and-set** | 10/20 ✅ | 0đ ✅ |

Dòng 1: ngân hàng giao **200.000đ** hàng mà chỉ trừ **10.000đ**.

> ⚠️ **Về cột thời gian:** script có in thời gian, nhưng ĐỪNG kết luận gì từ nó. Mỗi luồng
> mở một connection qua `docker exec`, chi phí đó (hàng trăm ms) át mất phần chênh lệch thật.
> Chạy nhiều lần sẽ thấy số nhảy lung tung. Lập luận "khoá lâu hơn" ở §3 đúng về **cơ chế**
> (xem sơ đồ), không dựa vào phép đo này. Muốn đo tử tế thì cần pgbench với connection pool.
> **Cột đáng tin là hai cột kết quả** — chúng ổn định qua mọi lần chạy.

### Hai lỗi riêng biệt trong cách ngây thơ

| Lỗi | Biểu hiện |
|---|---|
| **Kiểm và ghi tách rời** | lúc ghi, điều đã kiểm có thể không còn đúng |
| **Ghi giá trị TUYỆT ĐỐI** (`= 90000`) | xoá sạch thay đổi người khác vừa làm → *lost update* |

Lỗi thứ hai giải thích con số 90.000đ: cả 20 người đều đọc 100.000 và đều ghi 90.000.

> ⚠️ Cột đã có `CHECK (balance >= 0)` mà **không cứu được** — vì không ai ghi số âm, ai cũng
> ghi đúng 90.000. `CHECK` là chốt chặn cho **một** lần ghi sai, không chống lost update.

---

## 1. CAS là gì

**Gộp "kiểm tra" và "ghi" vào MỘT câu lệnh mà không ai chen vào giữa được.**

```sql
UPDATE bank_accounts
   SET balance = balance - :amount     -- ⑵ SET      (tương đối, không tuyệt đối)
 WHERE code = :code
   AND balance >= :amount;             -- ⑴ COMPARE  (điều kiện nằm TRONG câu lệnh)
```

### Ba thành phần bắt buộc — thiếu cái nào cũng hỏng

| | Phải làm | Nếu làm sai |
|---|---|---|
| ① | Điều kiện nằm trong **`WHERE`** | để trong `if` của Java → khe hở quay lại |
| ② | **`balance - :amount`** (tương đối) | `= :newValue` (tuyệt đối) → lost update |
| ③ | Đọc **số dòng bị sửa** làm câu trả lời | bỏ qua → tưởng thành công trong khi không |

```java
int rows = accounts.debit(code, amount);
if (rows == 0) {
    // không trừ được — và biết CHẮC là không trừ gì cả
}
```

`0` **không phải lỗi**, nó là kết quả nghiệp vụ: *"không làm được"*.
`1` nghĩa là *"làm rồi, chắc chắn đúng một lần"*.

---

## 2. Vì sao nó đúng

Khi `UPDATE` tìm thấy dòng khớp, PostgreSQL **khoá dòng đó** cho tới hết câu lệnh. Luồng thứ
hai chạm vào cùng dòng bị **chặn lại**, chờ, rồi — đây mới là điểm mấu chốt — **kiểm lại
mệnh đề `WHERE` trên giá trị MỚI**.

```
A:  UPDATE … WHERE balance >= 10000  → khớp (100.000) → ghi 90.000 → commit
B:  UPDATE … WHERE balance >= 10000  → BỊ CHẶN ──────────────────────┘
                                     → tỉnh dậy, kiểm LẠI trên 90.000
```

Người thứ 11 gặp `balance = 0`, `WHERE` không khớp, `UPDATE 0` → bị từ chối đúng lúc cần.
**DB tuần tự hoá giúp ta**, ta không phải tự làm.

---

## 3. CAS vs pessimistic lock (`SELECT … FOR UPDATE`)

### Pessimistic lock KHÔNG SAI

Thí nghiệm dòng 2 cho kết quả đúng y hệt CAS. Đây là lựa chọn giữa **hai cách đều đúng**.

### Vì sao dự án này chọn CAS — ba lý do, xếp theo sức nặng

#### ⭐ Lý do 1 — pessimistic hỏng ÂM THẦM

Dòng 2 và dòng 3 của bảng thí nghiệm dùng **cùng một câu `FOR UPDATE`**, chỉ khác có bọc
transaction hay không:

```java
@Transactional                          // ĐÚNG: khoá giữ tới COMMIT
public void debit(...) { var acc = repo.findForUpdate(code); … }

public void debit(...) { … }            // SAI: khoá THẢ NGAY khi câu SELECT xong
                                        //      → không một lỗi nào được ném ra
```

Quên `@Transactional` thì `FOR UPDATE` **lặng lẽ vô dụng**: 20/20 rút được, mất 80.000đ,
và **test đơn luồng vẫn xanh**. Chỉ phát hiện ra khi khách đã mất tiền.

> `FOR UPDATE` đúng **nếu bạn nhớ**. CAS đúng **kể cả khi bạn quên** —
> vì đảm bảo nằm trong chính câu lệnh, không phụ thuộc ngữ cảnh transaction.

#### Lý do 2 — khoá giữ bao lâu

```
FOR UPDATE:  [khoá]──SELECT──nghiệp vụ 50ms──UPDATE──COMMIT──[thả]
                    └────── khoá giữ suốt 50ms ──────┘   → 20 người xếp hàng

CAS:         nghiệp vụ 50ms (song song, KHÔNG khoá) ──▶ [khoá]─UPDATE─[thả]  ~0.1ms
```

Đây là lập luận về **cơ chế**, không phải về con số đo được (xem cảnh báo ở §0).

Với `FOR UPDATE`, **mọi thứ nằm giữa SELECT và COMMIT đều ở trong khoá** — thêm một query,
một vòng lặp, hoặc tệ nhất là một cú gọi mạng. Cùng bài học với
[bank-call-timeout-and-retry.md](bank-call-timeout-and-retry.md): giữ transaction qua cú gọi
mạng thì cạn connection pool. `FOR UPDATE` *mời* ta viết logic ở giữa; CAS không có "ở giữa".

#### Lý do 3 — deadlock

Một dòng, một câu `UPDATE` ⇒ **không thể deadlock**. Với `FOR UPDATE` nhiều dòng thì có:

```
A: khoá ACC-001 → chờ ACC-002
B: khoá ACC-002 → chờ ACC-001        ← deadlock
```

Chuyển khoản A→B và B→A cùng lúc là ra ngay. Phải tự áp luật "luôn khoá theo thứ tự mã tăng
dần" — một luật dễ quên.

### Khi nào pessimistic lock THẮNG

| Tình huống | Vì sao CAS không làm được |
|---|---|
| Quyết định phức tạp (phí theo bậc, hạn mức ngày tính từ bảng khác) | không nhét vừa vào `WHERE` |
| Sửa nhiều dòng nhất quán (chuyển khoản: trừ A, cộng B) | cần transaction bao cả hai, khoá luôn cũng tự nhiên |
| Cần **giá trị trước/sau** để ghi sổ cái | CAS chỉ trả về số dòng |
| Đọc xong mới biết phải làm gì | CAS phải biết trước điều kiện |

> Dòng thứ ba là lý do hệ thống **ledger** thật hay dùng `FOR UPDATE`: bút toán cần số dư
> trước và sau. Khi dự án tới **Inc 6 (Refund + Ledger)**, rất có thể đổi sang `FOR UPDATE`
> ở chỗ đó — và đó sẽ là quyết định đúng lúc ấy.

### Các cách khác, và vì sao không chọn

| Cách | Vấn đề |
|---|---|
| `synchronized` trong Java | chỉ khoá **trong một JVM** — chạy 2 instance là vô dụng ngay |
| `SERIALIZABLE` | đúng, nhưng phải bắt serialization failure rồi tự retry — đắt, dễ quên |
| Distributed lock (Redis/Redisson) | thêm một hệ thống có thể sập; vẫn phải xử lý khi lock hết hạn giữa chừng |

---

## 4. Phản bác thường gặp: "CAS không cho biết giá trị"

PostgreSQL gỡ bằng `RETURNING`:

```sql
UPDATE bank_accounts
   SET balance = balance - :amount
 WHERE code = :code AND balance >= :amount
RETURNING balance;        -- có dòng trả về ⇒ biết luôn số dư MỚI
```

Có dòng ⇒ trừ thành công **và** biết còn lại bao nhiêu. Không có dòng ⇒ không trừ.
Một câu lệnh, một round-trip, vừa nguyên tử vừa có giá trị.

Riêng ở fake-bank, `0 dòng` còn **không mơ hồ**: bước xác thực trước đó
(`AccountAuthenticator`) đã bảo đảm tài khoản tồn tại và đang `ACTIVE`, nên `0` chỉ có thể là
**không đủ số dư**.

---

## 5. ⚠️ CAS KHÔNG phải idempotency — đừng lẫn

Hai vấn đề khác nhau, hai cơ chế khác nhau:

| Vấn đề | Cơ chế |
|---|---|
| Nhiều người cùng rút → thấu chi | **CAS** `WHERE balance >= amount` |
| Hub **gửi lại** cùng một lệnh → trừ 2 lần | **Idempotency key** `UNIQUE(bank_code, idempotency_key)` + CAS trên `status='PENDING'` |

CAS trên số dư **cho phép** trừ hai lần nếu đó là hai giao dịch khác nhau — đúng như nó phải
thế. Thứ phân biệt *"hai giao dịch"* với *"một giao dịch gửi hai lần"* là **khoá
idempotency**, không phải CAS.

> Vì vậy bước trừ tiền ở fake-bank có **hai CAS trong một transaction**:
> một trên `bank_transactions.status`, một trên `bank_accounts.balance`.

---

## 6. Pattern này xuất hiện 4 lần trong dự án

| Ở đâu | Câu lệnh | Chống cái gì |
|---|---|---|
| `merchant-demo` | `UPDATE orders … WHERE status='AWAITING_PAYMENT'` | cập nhật đơn hai lần |
| `payment-hub`, Inc 4 | `UPDATE payments … WHERE status='CREATED' AND checkout_expires_at > now()` | hai tab cùng mở giao dịch ở bank |
| `fake-bank`, Inc 4 | `UPDATE bank_accounts … WHERE balance >= :amount` | trừ tiền hai lần / rút quá số dư |
| `payment-hub`, Inc 3 | `UNIQUE(merchant_id, idempotency_key)` | anh em họ: *chèn*-and-set thay vì *so*-and-set |

Chỗ CAS ở Hub (`PaymentRepository.markInProgress`) còn mang một ngoại lệ cần biết: nó là chỗ
**duy nhất** đổi `status` mà không qua `Payment.transitionTo()` (CLAUDE.md #3). Chấp nhận
được vì luật transition vẫn còn nguyên — nó nằm trong chính mệnh đề `WHERE`, chỉ là do DB thi
hành thay vì Java.

---

## 7. Cách nhận ra mình cần CAS

Đặt ba câu hỏi với mỗi đoạn code đụng DB:

1. **Có đọc một giá trị rồi quyết định dựa trên nó không?**
2. **Quyết định đó có dẫn tới một lần ghi không?**
3. **Có thể có hai luồng cùng chạy đoạn này không?** (hai tab, double-click, retry, nhiều instance)

Cả ba đều "có" ⇒ **phải CAS hoặc pessimistic lock**. Không có ngoại lệ "chắc hiếm khi xảy ra" —
thí nghiệm ở §0 cho thấy nó xảy ra ngay lần đầu thử.

### Checklist khi viết CAS

- [ ] Điều kiện nằm trong `WHERE`, không phải trong `if` của Java
- [ ] Phép gán **tương đối** (`balance - :amt`), không phải tuyệt đối
- [ ] Có đọc số dòng bị sửa và xử lý nhánh `0`
- [ ] Nhánh `0` được coi là **kết quả nghiệp vụ**, không phải exception
- [ ] Có test đồng thời (N luồng) chứ không chỉ test đơn luồng
