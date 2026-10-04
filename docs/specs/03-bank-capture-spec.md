# Đặc tả nghiệp vụ — Increment 4: THU TIỀN (Hub ↔ Bank)

> Tài liệu **BA**: mô tả *cái gì* và *vì sao*, không mô tả *làm thế nào*.
> Phần thiết kế kỹ thuật là việc của engineer — xem §10.
> Tiếp nối: [02-payment-init-spec.md](02-payment-init-spec.md) ·
> [02-payment-init-design.md](02-payment-init-design.md)
> Kiến thức nền bắt buộc đọc: [bank-call-timeout-and-retry.md](../notes/bank-call-timeout-and-retry.md)
> **Khi bắt tay code: dùng [03-bank-capture-checklist.md](03-bank-capture-checklist.md)** (bản rút gọn để tick).

---

## 1. Bối cảnh

Hết Increment 3, Ana đang đứng trên trang checkout của Hub, nhìn thấy số tiền, và **không
làm gì được** — nút "Thanh toán" đang disabled. Payment nằm ở `CREATED` cho tới khi hết hạn.

Increment 4 làm cho nút đó chạy: Ana chọn ngân hàng, xác nhận, tiền được trừ, merchant
biết kết quả.

Đây là increment **khó nhất về mặt phân tán** trong cả dự án, vì lần đầu tiên hệ thống phải
xử lý tình huống **"tôi không biết chuyện gì đã xảy ra"** — Bank không trả lời.

### 1.1 Trong phạm vi

1. **Fake Bank** giả lập **3 ngân hàng** (`VCB` · `TCB` · `ACB`) với hành vi lỗi khác nhau,
   có giao diện cho Ana chọn kết quả: **Thành công · Thất bại · Hủy**
2. **Fake Bank API** nhận lệnh thu tiền từ Hub (server-to-server), có tài khoản + số dư giả
2b. **Màn chọn ngân hàng** trên trang checkout của Hub
3. **Hub → Bank**: gọi thu tiền, có **timeout + retry + circuit breaker**
4. **Bank → Hub**: **callback** báo kết quả, **ký HMAC**, **idempotent**
5. **Hub**: máy trạng thái đầy đủ `CREATED → IN_PROGRESS → CAPTURED | FAILED`
5b. **Adapter + credential + circuit breaker RIÊNG cho từng ngân hàng**
6. **Bảng lưu từng lần gọi Bank** (append-only) — bằng chứng khi đối soát/khiếu nại
7. **Job đối soát**: quét payment treo ở `IN_PROGRESS`, hỏi lại Bank
8. Trang kết quả trên Hub, rồi đưa Ana về `return_url` của merchant
9. **Verify-on-return phía merchant**: Ana về tới Chợ Phiên ⇒ merchant **hỏi lại Hub**
   (`GET /v1/payments/{ref}`) rồi cập nhật đơn — xem §2.7

### 1.2 Ngoài phạm vi

| Hạng mục | Increment |
|---|---|
| Webhook Hub → merchant (merchant tự biết kết quả) | Inc 5 |
| Outbox + Kafka | Inc 5 |
| Refund / hoàn tiền | Inc 6 |
| Ledger (sổ cái kép) | Inc 6 |
| Authorize tách khỏi Capture (`AUTHORIZED`, `VOIDED`) | — **cố ý bỏ**, xem §2.2 |
| 3-D Secure / OTP thật | — |
| Nhiều phương thức thanh toán (ví, QR) | — |
| Manual review (treo giao dịch nghi ngờ) | Inc 9 |

### 1.3 Ranh giới bàn giao

```
Inc 3 kết thúc:  payment CREATED, Ana đang ở /checkout/{token}
Inc 4 kết thúc:  payment CAPTURED hoặc FAILED, Ana đã về lại website merchant,
                 và merchant ĐÃ cập nhật đơn — NẾU Ana quay về VÀ kết quả đã có sẵn.
                 Các ca còn lại (Ana đóng tab · kết quả tới muộn) vẫn kẹt → Inc 5.
```

---

## 2. Quyết định phạm vi quan trọng

### 2.1 Ana nhập thông tin thanh toán **ở đâu**?

**Quyết định: Ana được chuyển hướng sang trang của Fake Bank.** Hub **không bao giờ**
chạm vào số thẻ.

```
Ana ở Hub /checkout/{token}
   │ bấm "Thanh toán"
   ▼
Hub gọi Bank (server-to-server) xin một phiên thanh toán
   │
   ▼
Ana được redirect sang trang Fake Bank
   │ chọn: Thành công / Thất bại / Hủy
   ▼
Bank báo kết quả cho Hub (callback) + đưa Ana quay lại Hub
```

**Vì sao:** đây là mô hình của VNPay/cổng ngân hàng VN và của 3-D Secure. Quan trọng hơn,
nó **giữ Hub ngoài phạm vi PCI DSS** — Hub không nhìn thấy, không truyền, không lưu số thẻ
(CLAUDE.md #5).

> *Ghi chú so sánh:* Stripe làm khác — thẻ được tokenize **ngay trên trình duyệt** bằng
> Stripe.js, server merchant cũng không thấy số thẻ. Hai cách đều né được PCI; ta chọn cách
> redirect vì có sẵn Fake Bank để dựng UI.

### 2.2 Gộp Authorize và Capture

**Quyết định: một bước duy nhất — thu tiền luôn.** Không có `AUTHORIZED`, không có `VOIDED`.

| | Production thật | Dự án này |
|---|---|---|
| Bước 1 | `AUTHORIZE` — bank **giữ** hạn mức, tiền chưa rời tài khoản | — |
| Bước 2 | `CAPTURE` — merchant xác nhận đã giao hàng, tiền mới chuyển | **gộp làm một** |
| Hủy trước khi giao | `VOID` — nhả hạn mức | — |

**Vì sao tách ở production:** luật của các tổ chức thẻ **cấm capture trước khi giao hàng**;
hàng vật lý giao sau vài ngày, số tiền cuối có thể đổi (thiếu hàng, giao một phần).

**Vì sao gộp ở đây:** Chợ Phiên không có kho, không có vận chuyển — giống mô hình **"sale"**
của hàng số giao ngay. Gộp lại vẫn đúng nghiệp vụ, và giảm được 3 trạng thái.

⚠️ Enum vẫn **giữ** `AUTHORIZED`/`VOIDED`/`REFUNDED` để Inc 6 dùng.

### 2.3 Nhiều ngân hàng — thật ở phía Hub, giả lập ở phía Bank

Ngoài đời mỗi ngân hàng là **một hệ thống khác nhau**: API khác, credential khác, mã lỗi khác.
Gateway thật vì vậy có **bank connector cho từng ngân hàng** (Adapter pattern).

**Quyết định — tách bạch hai phía:**

| Phía | Làm gì |
|---|---|
| **Hub** (thứ ta đang học) | đối xử như **3 hệ thống độc lập**: 3 bộ credential · 3 circuit breaker · 3 adapter · 3 bảng mã lỗi. **KHÔNG đơn giản hoá.** |
| **Fake Bank** (đồ chơi) | **MỘT service**, routing theo `bank_code`, một template UI tham số hoá. |

```yaml
# Hub nhìn thấy 3 ngân hàng riêng biệt
hub.banks:
  VCB: { base-url: ..., api-key: ..., secret: ..., timeout-ms: 5000 }
  TCB: { base-url: ..., api-key: ..., secret: ..., timeout-ms: 8000 }
  ACB: { base-url: ..., api-key: ..., secret: ..., timeout-ms: 5000 }
```

Hành vi giả lập của từng bank (để test được các kịch bản hỏng):

| bank_code | Hành vi |
|---|---|
| `VCB` | bình thường |
| `TCB` | **chậm** — một tỉ lệ request treo lâu ⇒ thử **timeout** & đối soát |
| `ACB` | **hay lỗi** — một tỉ lệ trả 5xx ⇒ thử **circuit breaker** |

> **Bài học đắt nhất ở đây:** circuit breaker **phải tách theo từng bank**.
> Một breaker dùng chung cho "Bank" nói chung là **thiết kế sai** — ACB sập sẽ chặn luôn
> giao dịch qua VCB đang khoẻ. Với một bank duy nhất thì **không bao giờ phát hiện ra lỗi này**.

**Phép thử thiết kế:** *"Mai thay VCB giả bằng VCB thật thì phải sửa gì?"*
Đáp án đúng: **chỉ config** (+ có thể một adapter). Nếu phải sửa `PaymentService`, state machine
hay callback handler ⇒ chi tiết của Fake Bank đã rò rỉ vào lõi nghiệp vụ.

### 2.3b Adapter pattern — nhốt cái riêng của từng bank vào một chỗ

**Port** (trong `domain`, không biết gì về HTTP):

```java
public interface BankConnector {
    String bankCode();
    CreateResult createTransaction(BankCreateRequest req);   // mở phiên, trả redirect_url
    QueryResult  queryTransaction(String idempotencyKey);    // job đối soát hỏi lại
}
```

**Chọn adapter theo `bank_code` bằng registry**, không phải `if/else`:

```java
@Component
public class BankConnectorRegistry {
    private final Map<String, BankConnector> byCode;   // Spring inject TẤT CẢ bean BankConnector

    public BankConnectorRegistry(List<BankConnector> connectors) {
        this.byCode = connectors.stream().collect(toMap(BankConnector::bankCode, identity()));
    }

    public BankConnector forBank(String bankCode) {
        var connector = byCode.get(bankCode);
        if (connector == null) throw new BusinessException(UNSUPPORTED_BANK);
        return connector;
    }
}
```

Thêm ngân hàng mới = **thêm một bean**, không sửa dòng nào ở chỗ khác.
`PaymentService` không bao giờ thấy HTTP, JSON hay mã lỗi của bank nào:

```java
var connector = registry.forBank(payment.getBankCode());
var result = connector.createTransaction(request);
```

#### Quy ước đặt tên (chốt)

| Vai trò | Tên | Ở đâu |
|---|---|---|
| **Port** | `BankConnector` | `domain.bank` — tên mô tả **năng lực**: nối ra ngân hàng |
| **Adapter** | `VcbAdapter` · `TcbAdapter` · `AcbAdapter` | `infra.bank` — tên mô tả **vai trò kiến trúc** |
| Bộ chọn | `BankConnectorRegistry` | `domain.bank` |

Hậu tố `Adapter` theo đúng từ vựng **ports & adapters** mà CLAUDE.md §5 đã chọn: đọc tên
class là biết nó nằm ở **biên** và việc của nó là **dịch**, không phải chứa nghiệp vụ.

#### Contract thật của 3 dialect (đã implement)

| | VCB | TCB | ACB |
|---|---|---|---|
| Endpoint | `POST /banks/{code}/v1/transactions` | `POST /tcb-gateway/payment/init` | `POST /acb/api/v2/pay-session` |
| Content-Type | `application/json` | `application/json` | `application/x-www-form-urlencoded` |
| Kiểu tên | snake_case | camelCase | UPPER_SNAKE |
| Số tiền | `amount` — **minor unit** | `txnAmount` — **đồng** | `AMT` — minor unit |
| Khoá idempotency | header `Idempotency-Key` | body `requestId` | form `REF_NO` |
| **Dòng ④ chuỗi ký** | **= khoá** | **RỖNG** | **RỖNG** |
| Thành công | `result_code: "00"` | `status: "SUCCESS"` | `error_no: 0` |
| Từ chối | `result_code: "51"` | `code: "INSUFFICIENT_BALANCE"` | `error_no: 9527` |
| Field kết quả | `bank_txn_id` / `redirect_url` | `txnRef` / `paymentUrl` | `txn_id` / `pay_url` |

⭐ Dòng in đậm là bài học đắt nhất bảng này: **chuỗi ký là contract RIÊNG của từng đối tác**,
không phải một công thức toàn cầu. TCB/ACB không có header `Idempotency-Key` nên dòng ④ phải
để rỗng ở **cả hai phía** — lệch một bên là 401 mà không tài nào đoán ra lý do.

⭐ Chữ ký luôn tính trên **đúng byte sẽ gửi đi**, nên form-urlencoded hay JSON không ảnh
hưởng gì: công thức làm việc trên byte, không trên cấu trúc dữ liệu. Phía nhận cũng phải
đọc `byte[]` thô — serialize lại từ DTO rồi băm là nguồn gốc của lỗi *"chữ ký đúng mà vẫn 401"*.

#### ⚠️ Adapter chỉ có giá trị khi các bank THỰC SỰ khác nhau

Viết 3 class y chang nhau chỉ khác `bankCode()` là **cargo cult** — lúc đó một
`HttpBankConnector` + config theo bank là đủ và đúng hơn.

Ngoài đời các bank khác nhau nhiều nhất ở **bảng mã lỗi**. Nên **quyết định**: 3 fake bank
dùng **cùng hình dạng HTTP** (đỡ việc) nhưng **khác bảng mã lỗi** (để adapter có việc thật):

```
VCB  →  "resultCode": "00" = thành công,  "51" = không đủ tiền
TCB  →  "status": "SUCCESS",              "code": "INSUFFICIENT_BALANCE"
ACB  →  "error_no": 0,                    "error_no": 9527
```

Mỗi adapter làm đúng một việc có thật: **dịch mã lỗi riêng của bank sang ngôn ngữ chung
của Hub**:

```java
// Hub chỉ biết 4 thứ này, không biết "51" hay "9527" nghĩa là gì
enum BankOutcome { SUCCESS, DECLINED, INDETERMINATE, BAD_REQUEST }
```

> Đây mới là chỗ Adapter kiếm cơm: nhốt cái riêng của từng bank vào một class, để lõi
> nghiệp vụ không bao giờ phải biết bank nào đang chạy.
>
> **Phép thử:** mai thay VCB giả bằng VCB thật ⇒ chỉ sửa **config + một adapter**.
> Phải sửa `PaymentService`/state machine/callback handler là thiết kế đã rò rỉ.

`BankOutcome.INDETERMINATE` là chỗ Adapter gánh **bài học trung tâm của Inc 4**: mọi thứ
không phải câu trả lời dứt khoát (timeout, 5xx, mã lỗi lạ chưa từng thấy) đều phải quy về
đây — **không được** lẳng lặng coi là `DECLINED`.

### 2.3c Circuit breaker — MỘT breaker cho MỖI bank

Dùng **Resilience4j**.

```yaml
resilience4j.circuitbreaker.instances:
  bank-VCB: { failureRateThreshold: 50, slidingWindowSize: 10, waitDurationInOpenState: 30s }
  bank-TCB: { ... }
  bank-ACB: { ... }
```

```java
CircuitBreaker breaker = registry.circuitBreaker("bank-" + bankCode);   // theo BANK
```

> ⭐ Một breaker dùng chung cho "Bank" nói chung là **thiết kế sai**: ACB sập sẽ chặn luôn
> giao dịch qua VCB đang khoẻ ⇒ một ngân hàng hỏng làm chết **toàn bộ** doanh thu.
> Với một bank duy nhất thì **không bao giờ phát hiện ra lỗi này** — đó là lý do spec
> quyết định giả lập 3 bank (§2.3).

#### Breaker mở ≠ timeout (phân biệt bắt buộc)

| | Ta biết gì | Kết luận |
|---|---|---|
| **Timeout** | **không biết** Bank có nhận request không | `IN_PROGRESS` — chờ đối soát |
| **Breaker mở** | **biết chắc chưa hề gửi đi** | `FAILED` — an toàn, tiền không thể trừ |

Breaker không chỉ bảo vệ Bank — nó còn **cho Hub một câu trả lời dứt khoát** thay vì một
dấu hỏi.

#### Câu để chốt ở D4: retry và breaker lồng nhau thế nào

```
Retry ( Breaker ( gọi ) )   → 3 lần retry = 3 lần lỗi ghi vào breaker → mở NHANH
Breaker ( Retry ( gọi ) )   → 3 lần retry = 1 lần lỗi                 → mở CHẬM
```

*(Resilience4j mặc định xếp `Retry` ngoài cùng.)* Hai cách cho hành vi khác hẳn nhau.

### 2.4 Chọn ngân hàng ở ĐÂU?

**Quyết định: ở trang checkout của Hub**, không phải ở website merchant.

| | ① Chọn ở Hub *(chọn cái này)* | ② Chọn ở merchant |
|---|---|---|
| Merchant phải biết danh sách bank | không | **có** |
| Hub thêm/sửa/tắt một bank | merchant **không phải làm gì** | mọi merchant phải cập nhật |
| Contract `POST /v1/payments` của Inc 3 | **giữ nguyên** | phải thêm field |

*(VNPay thật hỗ trợ cả hai: có `vnp_BankCode` thì đi thẳng, không có thì hiện trang chọn.
Đường nâng cấp về sau: cho merchant gửi kèm `bank_code` **tùy chọn** — thêm field tùy chọn
nên không phá contract cũ.)*

**Hệ quả kỹ thuật:** `bank_code` **không** nằm trong request tạo payment (Inc 3). Nó xuất hiện
**muộn hơn**, lúc Ana bấm thanh toán:

```
Inc 3:  POST /v1/payments           → payment CREATED, bank_code = NULL
Inc 4:  POST /checkout/{token}/pay  → body { bank_code: "VCB" } → IN_PROGRESS, ghi bank_code
```

⚠️ `bank_code` do **trình duyệt Ana** gửi ⇒ **dữ liệu không tin được** ⇒ phải validate theo
danh sách Hub hỗ trợ, sai thì từ chối.

### 2.5 Xác thực Hub ↔ Bank *(chốt D1)*

**Nguyên tắc nền:** *ai sở hữu cái cửa thì người đó phát chìa khoá.*

| Chiều | Cơ chế | Ai cấp credential |
|---|---|---|
| **Hub → Bank** (thu tiền) | API key + HMAC | **Bank** cấp cho Hub — mỗi bank **một bộ riêng** |
| **Bank → Hub** (callback) | HMAC | **Hub** cấp cho Bank — **KHÁC khoá** chiều đi |

#### Vì sao hai chiều dùng hai khoá khác nhau

HMAC **đối xứng** ⇒ bên verify cũng chính là bên ký được. Một khoá dùng chung nghĩa là
**cả hai bên đều tạo được chữ ký mà bên kia chấp nhận**. Ba hậu quả:

1. **Callback mất giá trị làm bằng chứng.** Khi tranh chấp, Hub đưa callback đã ký ra để
   chứng minh *"Bank báo thành công"* — nhưng nếu Hub cũng giữ khoá đó thì Hub tự ký được,
   Bank phản bác ngay: *"cái đó các anh tự tạo"*.
2. **Bán kính thiệt hại gấp đôi** — lộ một khoá là hỏng cả hai chiều.
3. **Không xoay khoá độc lập được** — đổi chiều này buộc phải đổi luôn chiều kia.

#### Vì sao chọn HMAC mà không phải mTLS

| | API key + HMAC *(chọn)* | mTLS |
|---|---|---|
| Ai dùng ngoài đời | VNPay, nhiều cổng nội địa | **ngân hàng lõi, SWIFT, card network** |
| Xác thực ở tầng | ứng dụng | **kết nối TCP** — chặn trước cả byte HTTP đầu tiên |
| Hạ tầng cần | không | CA, cấp cert, keystore/truststore, theo dõi hạn cert |
| Dạy thêm gì cho dự án | ❌ đã làm ở Inc 3 | ✅ quản lý chứng chỉ |

> ⚠️ **Đánh đổi có ý thức:** chọn HMAC nghĩa là **trục xác thực không dạy thêm gì mới** so
> với Inc 3. Chấp nhận, vì Inc 4 đã có 5 bài học nặng khác (in-doubt · retry idempotent ·
> callback idempotent · breaker theo bank · đối soát) — nhồi thêm mTLS sẽ loãng.
>
> **Production:** tích hợp ngân hàng thật thường **bắt buộc mTLS**.
> **Hạng mục tùy chọn** cho Inc 9/12 — thay được mà không đụng lõi nghiệp vụ, vì đã nhốt
> trong bank adapter.

#### Lưu credential ở đâu

**Config / biến môi trường**, KHÔNG phải bảng DB:

```yaml
hub.banks:
  VCB:
    base-url:        ${VCB_BASE_URL}
    api-key:         ${VCB_API_KEY}           # Bank cấp cho Hub
    api-secret:      ${VCB_API_SECRET}        # Bank cấp cho Hub
    callback-secret: ${VCB_CALLBACK_SECRET}   # Hub cấp cho Bank
```

Phân biệt với merchant secret ở Inc 3 — **hai loại secret khác nhau**:

| | Merchant secret (Inc 3) | Bank credential (Inc 4) |
|---|---|---|
| Số lượng | hàng nghìn, tạo/xoá liên tục | **3**, gần như cố định |
| Ai tạo ra | Hub sinh lúc onboarding | **con người** cấu hình một lần |
| Nơi phù hợp | **bảng DB** (+ mã hoá cột, khoá ở KMS) | **config / Secrets Manager** |

> 🔖 **DEV-ONLY:** `.env` (gitignored). Production: **Secrets Manager**.
> **Xoay khoá: chưa làm ở Inc 4** — ghi nhận là nợ kỹ thuật. Cơ chế 2 khoá song song
> (`secret` + `secret_previous` + cửa sổ chồng lấn) **đã có sẵn từ Inc 3**, sau này áp lại
> chỉ là thêm một field config.

### 2.6 Dữ liệu thẻ

**Tuyệt đối không có số thẻ thật.** Fake Bank dùng **tài khoản giả** có số dư.
Ana **nhập mã tài khoản + PIN**, và **ngân hàng tự quyết định** kết quả dựa trên trạng thái
tài khoản đó.

#### Ngân hàng phải là bên QUYẾT ĐỊNH, không phải người test

Thiết kế ban đầu cho Ana bấm 3 nút *Thành công / Thất bại / Hủy*. **Bỏ cách đó** — nó sai ở
tầng thiết kế, không phải chỉ đơn giản hoá ở tầng vận hành: ngân hàng khi đó chỉ là cái máy
phát kết quả theo lệnh người bấm, không kiểm số dư, không trừ tiền, không quyết định gì.

Thay bằng **tài khoản đặc biệt** — đúng cách mọi sandbox thật làm (Stripe có thẻ
`4000 0000 0000 0002` = bị từ chối; VNPay sandbox có thẻ riêng cho từng kịch bản):

| Tài khoản | Số dư | Ngân hàng làm gì | Dùng để test |
|---|---|---|---|
| `ACC-001` | 5.000.000 ₫ | **trừ tiền** → `SUCCESS` | luồng thành công |
| `ACC-002` | 50.000 ₫ | không đủ → `DECLINED / insufficient_funds` | **từ chối dứt khoát** (BR-B9, không retry) |
| `ACC-003` | 9.999.999 ₫ | tài khoản **bị khoá** → `DECLINED / account_locked` | mã lỗi khác của Bank |
| `ACC-004` | 9.999.999 ₫ | **treo 30 giây** | **timeout** → `IN_PROGRESS` + đối soát |
| `ACC-005` | 9.999.999 ₫ | trả **500** | retry → **circuit breaker** |

Thêm ô **PIN**: `123456` là đúng; sai ⇒ `DECLINED / invalid_pin`.
Trang Bank hiện sẵn bảng tài khoản test ở cuối trang (Stripe cũng làm vậy).

**Vì sao cách này hơn 3 nút:**
- ngân hàng **thật sự** kiểm số dư, **thật sự** trừ tiền, **thật sự** quyết định;
- vẫn test được **mọi kịch bản**, kể cả timeout và 5xx — thứ mà số dư không tạo ra được;
- cho phép **đo được idempotency**: retry 3 lần cùng khoá ⇒ số dư chỉ giảm **một lần**.
  Với 3 nút thì không có gì để đo.

#### Ranh giới dữ liệu — quan trọng

> **Hub được biết bạn trả QUA ĐÂU. Không được biết bạn trả BẰNG GÌ.**

| Dữ liệu | Hub thấy? | Vì sao |
|---|---|---|
| `bank_code` | ✅ | chỉ là mã định tuyến |
| mã tài khoản / số thẻ | ❌ | vào **phạm vi PCI DSS** ngay lập tức |
| PIN / OTP / CVV | ❌ | tuyệt đối không |
| số dư | ❌ | không phải việc của gateway |
| kết quả + mã giao dịch Bank | ✅ | đây mới là thứ Hub cần |

Hệ quả giao diện: **chọn ngân hàng ở Hub**, **nhập tài khoản + PIN ở Bank**.
Ngoài PCI, còn một lý do thực tế: người dùng kiểm tra mình không bị lừa bằng cách nhìn
**thương hiệu ngân hàng trên thanh địa chỉ**. Hub mà hỏi PIN ngân hàng thì chính Hub
trông như trang phishing.

### 2.7 Đồng hồ đếm ngược trên trang checkout của Hub

Trang checkout hiện **thời gian còn lại** trước khi link hết hạn (dữ liệu đã có sẵn từ
Inc 3: `payments.checkout_expires_at`). Hết giờ ⇒ khoá nút thanh toán, hiện
*"Link đã hết hạn"*.

⚠️ Đồng hồ chạy bằng JS **chỉ để hiển thị**. Quyết định thật vẫn ở server
(`checkout_expires_at` + job quét) — trình duyệt sửa đồng hồ cũng vô ích.

**Ranh giới dữ liệu — quan trọng:**

> **Hub được biết bạn trả QUA ĐÂU. Không được biết bạn trả BẰNG GÌ.**

| Dữ liệu | Hub thấy? | Vì sao |
|---|---|---|
| `bank_code` | ✅ | chỉ là mã định tuyến |
| số tài khoản / số thẻ | ❌ | vào **phạm vi PCI DSS** ngay lập tức |
| PIN / OTP / CVV | ❌ | tuyệt đối không |
| số dư | ❌ | không phải việc của gateway |
| kết quả + mã giao dịch Bank | ✅ | đây mới là thứ Hub cần |

Hệ quả giao diện: **chọn ngân hàng ở Hub**, **chọn tài khoản + xác nhận ở Bank**.
Ngoài PCI, còn một lý do thực tế: người dùng kiểm tra mình không bị lừa bằng cách nhìn
**thương hiệu ngân hàng trên thanh địa chỉ**. Hub mà hỏi PIN ngân hàng thì chính Hub
trông như trang phishing.

---

### 2.8 Verify-on-return: merchant hỏi lại Hub khi Ana quay về

**Vấn đề:** hết Inc 4, payment đã `CAPTURED` nhưng đơn hàng bên merchant vẫn
`AWAITING_PAYMENT` — vì merchant chưa có kênh nào để biết. Webhook thì tới Inc 5 mới làm.
Demo như vậy là dở dang.

**Cách vá:** dùng cú quay về của Ana làm **tín hiệu**, rồi đi hỏi nguồn sự thật.

```
Ana về  http://localhost:5173/orders/ord_8222?status=CAPTURED
   │  merchant KHÔNG tin ?status=
   └─▶ POST /v1/orders/{code}/sync-payment   (frontend → backend merchant)
         └─▶ GET /v1/payments/{ref}          (merchant → Hub, ký HMAC)
               ← { "status": "CAPTURED" }
       đơn: AWAITING_PAYMENT → PAID
```

> **Redirect là tiếng chuông cửa. `GET /v1/payments` là mở cửa ra xem ai.**
> Cái trên thanh địa chỉ ai cũng gõ được; cái hỏi thẳng sang Hub thì có chữ ký.

API `GET /v1/payments/{ref}` **đã có sẵn từ Inc 3** — không phải viết mới.

**Dùng `POST`, không nhét vào `GET /v1/orders/{code}`:** theo chuẩn HTTP, `GET` phải không
có tác dụng phụ — trình duyệt, crawler và prefetch đều có thể gọi nó bất cứ lúc nào.

#### ⚠️ Vì sao VẪN PHẢI làm webhook ở Inc 5

Verify-on-return **chỉ chộp được kết quả ở đúng một khoảnh khắc**. Bốn ca nó bó tay —
và ca đầu tiên xảy ra **ngay cả khi Ana có quay về**:

```
14:00:08  Bank im lặng → payment = IN_PROGRESS (chưa có kết quả)
14:00:12  Ana QUAY VỀ  → merchant hỏi Hub → "IN_PROGRESS" → đơn không đổi
14:00:20  Ana đóng trang
14:10:00  job đối soát chốt được → payment = CAPTURED
          ⇒ KHÔNG CÒN AI HỎI. Đơn kẹt AWAITING_PAYMENT vĩnh viễn.
```

| Ca | Verify-on-return | Webhook |
|---|---|---|
| Ana quay về, kết quả đã có | ✅ | ✅ |
| **Ana quay về, kết quả CHƯA có** | ❌ | ✅ |
| Ana đóng tab | ❌ | ✅ |
| Hoàn tiền từ Portal (Inc 6, không có khách) | ❌ | ✅ |
| Kịp cập nhật màn hình Ana đang nhìn (~300ms) | ✅ | 🟡 có thể chậm hơn một nhịp |

Ba lớp đầy đủ (Inc 5 hoàn thiện):

```
webhook (push)          → nguồn chính: không mất tin, có retry
verify-on-return        → cho màn hình Ana kịp cập nhật
merchant tự quét (pull) → lưới an toàn cuối, chạy thưa
```

#### Hệ quả: hai đường cùng ghi MỘT transition

Webhook và verify-on-return **cùng** chuyển `AWAITING_PAYMENT → PAID`, và có thể chạy
**đồng thời**. Đặt trạng thái thì idempotent tự nhiên; **tác dụng phụ thì không**
(email xác nhận, xuất kho, cộng doanh thu).

**Chặn bằng compare-and-set ở DB** — chỉ bên ĐỔI ĐƯỢC trạng thái mới chạy tác dụng phụ:

```sql
UPDATE orders SET status = 'PAID', paid_at = now()
 WHERE order_code = ? AND status = 'AWAITING_PAYMENT';   -- 0 hoặc 1 dòng
```

```java
if (orders.markPaid(orderCode) == 1) {
    sendConfirmationEmail(order);      // chỉ bên thắng mới làm
}
```

## 3. Các bên tham gia

| Bên | Vai trò ở Inc 4 |
|---|---|
| **Ana** (người mua) | bấm thanh toán, chọn kết quả trên UI Bank |
| **Payment Hub** | điều phối: gọi Bank, nhận callback, chuyển trạng thái, đối soát |
| **Fake Bank** | nhận lệnh thu tiền, trừ số dư tài khoản giả, gọi callback về Hub |
| **Merchant** (Chợ Phiên) | *thụ động ở Inc 4* — chỉ nhận Ana quay về; biết kết quả thật từ Inc 5 |

---

## 4. User stories

| # | Là | Tôi muốn | Để |
|---|---|---|---|
| U1 | Ana | bấm thanh toán và được đưa tới trang ngân hàng | trả tiền cho đơn hàng |
| U2 | Ana | chọn được kết quả (thành công/thất bại/hủy) trên UI Bank | thử được mọi kịch bản khi demo |
| U3 | Ana | quay về website merchant sau khi xong | biết đơn hàng của mình ra sao |
| U4 | Ana | không bị trừ tiền hai lần dù mạng lỗi hay bấm lại | không mất tiền |
| U5 | Hub | biết chắc kết quả kể cả khi Bank không trả lời | không kết luận sai |
| U6 | Hub | có bằng chứng từng lần gọi Bank | đối chất khi khách khiếu nại |
| U7 | Vận hành | payment không bao giờ treo vĩnh viễn ở `IN_PROGRESS` | không phải dò tay |

---

## 5. Yêu cầu chức năng

### Phía Ana
- **FR-B1** Trang checkout có nút thanh toán hoạt động (thay nút disabled của Inc 3).
- **FR-B2** Bấm nút ⇒ payment chuyển `CREATED → IN_PROGRESS`, Ana được chuyển sang UI Bank.
- **FR-B3** UI Bank hiển thị tên ngân hàng, tên merchant, số tiền; Ana **nhập mã tài khoản
  + PIN**. **Ngân hàng tự quyết định** kết quả theo trạng thái tài khoản (§2.6) — không có
  nút chọn kết quả.
- **FR-B3d** Trang checkout của Hub hiển thị **đồng hồ đếm ngược** tới `checkout_expires_at`;
  hết giờ thì khoá nút thanh toán (§2.7).
- **FR-B3b** Trang checkout của Hub cho Ana **chọn ngân hàng** trong danh sách Hub hỗ trợ.
- **FR-B3c** `bank_code` không hợp lệ ⇒ **từ chối**, không gọi Bank.
- **FR-B4** Sau khi chọn, Ana được đưa về trang kết quả của Hub, rồi về `return_url` của merchant.
- **FR-B5** Link thanh toán đã `EXPIRED`/`CANCELLED`/`CAPTURED` ⇒ không bấm thanh toán được.

### Hub → Bank
- **FR-B6** Hub gọi Bank **server-to-server** để mở phiên thanh toán, nhận về mã giao dịch + URL để redirect Ana.
- **FR-B7** Mọi lời gọi Bank có **timeout**, **retry có giới hạn**, và **circuit breaker**.
- **FR-B7b** Timeout, retry, breaker và credential được cấu hình **RIÊNG cho từng ngân hàng**.
  Một ngân hàng hỏng **không được** ảnh hưởng tới giao dịch qua ngân hàng khác.
- **FR-B8** Retry phải **an toàn** — gọi lại không được tạo giao dịch thứ hai.
- **FR-B9** **Mọi lần gọi Bank đều được ghi lại** (append-only): gọi lúc nào, gửi gì, nhận gì, mất bao lâu.
- **FR-B10** Hub xác thực với Bank bằng **API key + HMAC**, credential **do Bank cấp**,
  **mỗi bank một bộ riêng** (cross-org, **không** dùng IdP nội bộ).

### Bank → Hub
- **FR-B11** Bank gọi callback báo kết quả; callback ký HMAC bằng **khoá do Hub cấp**
  (**khác** khoá chiều đi), Hub **phải verify trước khi chạm nghiệp vụ**.
- **FR-B12** Callback trùng (Bank gửi lại) **không được xử lý hai lần**.
- **FR-B13** Callback tới **muộn** hoặc **không theo thứ tự** vẫn phải xử lý đúng.
- **FR-B14** Callback cho payment đã ở trạng thái kết thúc ⇒ không đổi trạng thái, nhưng **phải ghi log**.

### Chịu lỗi
- **FR-B15** Bank **timeout** ⇒ payment **KHÔNG** chuyển sang `FAILED`, giữ `IN_PROGRESS`.
- **FR-B16** Job đối soát quét payment `IN_PROGRESS` quá hạn, **hỏi lại Bank** để chốt kết quả.
- **FR-B17** Bank hỏng liên tục ⇒ circuit breaker mở, Hub **fail nhanh** thay vì bắt Ana chờ.
- **FR-B18** Ana đóng trình duyệt giữa chừng ⇒ kết quả vẫn được chốt (nhờ callback + đối soát).

### Phía merchant (verify-on-return)
- **FR-B19** Ana quay về trang kết quả của merchant ⇒ merchant gọi `GET /v1/payments/{ref}`
  sang Hub và cập nhật đơn theo kết quả đó.
- **FR-B20** Merchant **không** cập nhật đơn dựa trên query param của redirect.
- **FR-B21** Payment còn `IN_PROGRESS` ⇒ đơn **giữ nguyên** `AWAITING_PAYMENT`,
  màn hình hiện "đang xử lý" — **không đoán**.
- **FR-B22** Cập nhật đơn phải **idempotent**: gọi nhiều lần chỉ gửi email/giao hàng
  **một lần** (compare-and-set, xem §2.7).

---

## 6. Dữ liệu nghiệp vụ *(mức khái niệm — engineer tự thiết kế schema)*

### Phía Hub
| Thực thể | Thông tin cần có |
|---|---|
| **Lần gọi Bank** | thuộc payment nào · **ngân hàng nào** · lần thứ mấy · **khoá idempotency Hub gửi sang Bank** · gửi gì · kết quả (thành công/từ chối/timeout/lỗi) · mã giao dịch Bank · mã lỗi Bank · bắt đầu & kết thúc lúc nào |
| **Payment** *(bổ sung)* | **ngân hàng đã chọn** · mã giao dịch Bank của lần thành công · số lần đã thử · lần thử gần nhất |
| **Callback đã nhận** | đủ thông tin để nhận ra callback trùng |

### Phía Fake Bank
| Thực thể | Thông tin cần có |
|---|---|
| **Tài khoản** | **thuộc ngân hàng nào** · mã tài khoản · chủ tài khoản · số dư · trạng thái |
| **Giao dịch** | **ngân hàng nào** · mã giao dịch · tài khoản · số tiền · trạng thái · **khoá idempotency Hub gửi** · thời điểm |

---

## 7. Quy tắc nghiệp vụ

| # | Quy tắc |
|---|---|
| **BR-B1** | **Timeout ≠ thất bại.** Không có câu trả lời dứt khoát ⇒ **không** kết luận. |
| **BR-B2** | Chỉ chuyển sang trạng thái kết thúc khi có **trả lời dứt khoát** từ Bank (qua response hoặc callback hoặc đối soát). |
| **BR-B3** | Retry **giữ nguyên** khoá idempotency đã gửi lần đầu. |
| **BR-B4** | Chỉ retry nhóm **không có kết luận** (timeout, lỗi mạng, 5xx, 429). **Không** retry khi Bank đã từ chối dứt khoát. |
| **BR-B5** | **Ghi ý định TRƯỚC khi gọi** Bank — Hub sập giữa chừng vẫn biết có lệnh đang bay. |
| **BR-B6** | Bảng lần gọi Bank là **append-only** (CLAUDE.md #7). |
| **BR-B7** | Callback **chưa verify chữ ký** ⇒ **không** chạm vào nghiệp vụ. |
| **BR-B8** | Transition sai ⇒ **chặn**, không âm thầm ghi đè trạng thái. |
| **BR-B9** | Số dư tài khoản không đủ ⇒ Bank **từ chối dứt khoát** (`FAILED`, không retry). |
| **BR-B10** | Một payment chỉ được **thu tiền một lần** — dù có bao nhiêu lần gọi Bank. |
| **BR-B11** | Hub **không lưu, không log** dữ liệu thẻ (ở đây là mã tài khoản giả — vẫn che khi log). |
| **BR-B12** | Ana đóng trình duyệt **không** làm mất giao dịch — kết quả chốt bằng callback/đối soát, không phụ thuộc trình duyệt. |
| **BR-B13** | `bank_code` do trình duyệt gửi ⇒ **không tin**, phải validate theo danh sách Hub hỗ trợ. |
| **BR-B14** | **Cách ly theo ngân hàng**: ngân hàng A hỏng không được làm hỏng giao dịch qua ngân hàng B. |
| **BR-B15** | Redirect là **tín hiệu**, không phải **sự thật**. Cập nhật đơn phải dựa trên câu trả lời server-to-server đã ký. |
| **BR-B16** | Tác dụng phụ (email, xuất kho, cộng doanh thu) chỉ chạy cho **bên ĐỔI ĐƯỢC trạng thái**, không chạy theo mỗi lần nhận tin. |

---

## 8. Màn hình tối thiểu

**Hub**:
1. Trang checkout *(sửa từ Inc 3)*: thêm **danh sách ngân hàng** + nút thanh toán đã hoạt động
2. Trang kết quả: thành công / thất bại / **đang xử lý** (khi còn `IN_PROGRESS`) + nút về merchant

**Fake Bank** (server-rendered, một template dùng chung cho 3 bank):
3. Trang xác nhận: **tên ngân hàng** · tên merchant · số tiền · ô **mã tài khoản** ·
   ô **PIN** · nút **Xác nhận thanh toán** · nút **Hủy** ·
   bảng **tài khoản test** ở cuối trang
4. Trang "đang xử lý" (khi tài khoản test giả lập Bank chậm)

---

## 9. Tiêu chí hoàn thành (DoD)

- [ ] Chạy được các kịch bản trên UI Bank bằng **tài khoản test**: thành công (`ACC-001`) ·
      không đủ tiền (`ACC-002`) · tài khoản khoá (`ACC-003`) · timeout (`ACC-004`) ·
      lỗi 5xx (`ACC-005`) · sai PIN · Ana bấm hủy
- [ ] ⭐ **Số dư `ACC-001` chỉ giảm MỘT lần** dù Hub retry 3 lần cùng `Idempotency-Key`
- [ ] Trang checkout Hub có **đồng hồ đếm ngược**; hết giờ thì khoá nút thanh toán
- [ ] Bank **từ chối** request của Hub nếu credential sai
- [ ] Hub **từ chối** callback nếu chữ ký sai
- [ ] **Callback trùng** gửi 5 lần ⇒ chỉ xử lý 1 lần, trạng thái không đổi
- [ ] **Bank timeout** ⇒ payment ở `IN_PROGRESS`, **không** thành `FAILED`; job đối soát chốt được kết quả
- [ ] **Bank sập hoàn toàn** ⇒ circuit breaker mở, Hub trả lời nhanh, không treo
- [ ] ⭐ **ACB sập KHÔNG làm hỏng giao dịch qua VCB** (breaker tách theo bank)
- [ ] Thêm một bank thứ 4 = **thêm một bean `BankConnector`**, không sửa `PaymentService`
- [ ] Mã lỗi riêng của từng bank được dịch sang `BankOutcome` chung; mã lạ chưa từng thấy
      ⇒ `INDETERMINATE`, **không** bị coi là `DECLINED`
- [ ] `bank_code` không hợp lệ ⇒ bị từ chối, **không** gọi Bank
- [ ] **Transition sai bị chặn** (ví dụ callback `CAPTURED` cho payment đã `EXPIRED`)
- [ ] Số dư tài khoản giả **trừ đúng một lần** dù Hub retry
- [ ] Bảng lần gọi Bank có **đủ dòng** cho mọi lần gọi, kể cả lần timeout
- [ ] Ana quay về Chợ Phiên ⇒ đơn hiện **PAID** (không còn kẹt `AWAITING_PAYMENT`)
- [ ] Gọi `sync-payment` **5 lần** ⇒ đơn vẫn `PAID`, tác dụng phụ chạy **đúng 1 lần**
- [ ] Payment còn `IN_PROGRESS` ⇒ đơn giữ `AWAITING_PAYMENT`, màn hình "đang xử lý"
- [ ] **Automated test** (Testcontainers): state machine · callback idempotent · timeout → không kết luận sai · chữ ký callback · retry không trừ tiền hai lần

---

## 10. 🎯 Nhiệm vụ thiết kế của engineer

Làm **lần lượt**, mỗi bài nộp xong review rồi mới sang bài kế.

| # | Bài | Câu hỏi cốt lõi |
|---|---|---|
| **D1** | **Chốt cơ chế xác thực Hub ↔ Bank** | mTLS hay API key + HMAC? Ai cấp cho ai? Callback ngược lại ký bằng gì? **Mỗi bank một bộ credential — Hub lưu ở đâu, xoay khoá thế nào?** |
| **D2** | **Sơ đồ tuần tự đầy đủ** | 3 luồng: thành công · hủy · **timeout**. Ai gọi ai, theo thứ tự nào, ai đổi trạng thái ở bước nào |
| **D3** | **Schema bảng lần gọi Bank** | cột nào, ghi ở thời điểm nào, vì sao append-only |
| **D4** | **Chính sách retry** | retry cái gì, mấy lần, giãn cách ra sao, ngưỡng circuit breaker, **vì sao breaker phải tách theo bank** |
| **D5** | **Thiết kế callback** | endpoint nào, chống trùng bằng gì, xử lý callback tới muộn / sai thứ tự |
| **D6** | **Job đối soát** | quét gì, hỏi Bank thế nào, xử lý từng loại câu trả lời, bao lâu thì bỏ cuộc |
| **D7** | **API contract của Fake Bank** | request/response, mã lỗi, phân biệt "từ chối" với "chưa biết", **`bank_code` nằm ở đâu: path, header hay body?** |

---

## 11. Bẫy đã biết trước (để không dẫm lại)

1. **Gọi HTTP bên trong `@Transactional`** ⇒ timeout 5s = giữ connection DB 5s ⇒ cạn pool.
2. **Retry mà sinh khoá mới** ⇒ trừ tiền hai lần.
3. **Timeout → `FAILED`** ⇒ khách bị trừ tiền nhưng thấy báo lỗi, trả lại lần nữa.
4. **Tin trình duyệt** (redirect về) làm nguồn kết quả ⇒ Ana sửa URL là "thanh toán thành công".
5. **Callback xử lý trước khi verify chữ ký** ⇒ ai cũng chuyển payment thành `CAPTURED` được.
6. **Quên ghi ý định trước khi gọi** ⇒ Hub sập là mất dấu tiền.
