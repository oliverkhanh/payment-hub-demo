# Ghi chú — Ký request bằng HMAC (merchant → Hub, Increment 3)

> Cơ chế xác thực ở **biên giữa hai tổ chức**: merchant server gọi Payment Hub.
> Vì sao dùng HMAC (không dùng OAuth2 như nội bộ Hub), ký cái gì, chống tấn công gì.
> Đọc kèm: [inc3-data-model.md](inc3-data-model.md) ·
> [02-payment-init-spec.md](../specs/02-payment-init-spec.md)

---

## 0. Ba biên xác thực của dự án (đừng lẫn)

| Biên | Cơ chế | Vì sao |
|---|---|---|
| merchant server → **Hub** | **API key + HMAC** | hai **tổ chức khác nhau**; merchant không thuộc IdP của ta |
| Hub ↔ Bank | mTLS hoặc API key/HMAC do **Bank** cấp | Bank ra luật, ta tuân theo |
| service nội bộ Hub ↔ nhau | **OAuth2 client_credentials → JWT** (Inc 1) | cùng một tổ chức, có IdP chung |

> Không cấp tài khoản IdP cho merchant vì họ là **hệ thống bên ngoài** — không nên phụ thuộc
> vào IdP của ta để chạy được (thêm một điểm chết), và không muốn quản lý vòng đời user của họ.

---

## 1. HMAC là gì, khác chữ ký số ra sao

**HMAC** = *Hash-based Message Authentication Code* — mã xác thực thông điệp, **không phải
chữ ký số**.

| | HMAC | Chữ ký số (RS256, Inc 1) |
|---|---|---|
| Khóa | **1 khóa chung** (đối xứng) | private ký / public verify |
| Bên verify làm giả được? | **CÓ** (có cùng khóa) | Không |
| Chống chối bỏ (non-repudiation) | ❌ | ✅ |
| Tốc độ | rất nhanh | chậm hơn nhiều |

Ở đây HMAC đủ dùng vì Hub là bên đáng tin (nó chính là bên thu tiền).
Nếu cần merchant **không thể chối** đã gửi lệnh ⇒ phải dùng chữ ký số.

**Hệ quả quan trọng:** secret **đối xứng** ⇒ Hub phải giữ **bản gốc** ⇒
**KHÔNG hash được** (hash một chiều, không tính lại HMAC được).
→ Xem [inc3-data-model.md §1](inc3-data-model.md).

---

## 2. Scheme chốt

### Chuỗi ký (canonical string)

Nối bằng `\n` (LF), **đúng thứ tự này**, không có `\n` ở cuối:

```
POST                                              ← ① method, IN HOA
/v1/payments                                      ← ② path, KHÔNG gồm query
1758892800                                        ← ③ timestamp Unix, GIÂY
6f1c2e9a-1111-2222-3333-444455556666              ← ④ Idempotency-Key
9b2c4e...f1                                       ← ⑤ SHA-256(raw body), hex CHỮ THƯỜNG
```

```
X-Signature = hex( HMAC-SHA256( api_secret, canonical_string ) )
```

### Header

| Header | Vai trò |
|---|---|
| `X-Merchant-Key` | **tra secret nào để verify** (vai như `kid` của JWT ở Inc 1) — tự nó *không* xác thực gì |
| `X-Signature` | bằng chứng "tôi có secret" + "nội dung không bị sửa" |
| `X-Timestamp` | chống replay |
| `Idempotency-Key` | chống trùng — **và phải nằm trong chuỗi ký** |
| `X-Signature-Version` | để sau này đổi scheme mà không phá merchant cũ |

### Hub verify — 5 bước

```
1. đọc X-Merchant-Key → tra merchants → lấy api_secret
2. lấy RAW body (trước khi Jackson parse) → sha256 hex lower
3. dựng lại canonical string theo đúng spec
4. HMAC-SHA256 → so bằng MessageDigest.isEqual   (constant-time!)
5. kiểm |now − X-Timestamp| ≤ 5 phút
```

Sai bất kỳ bước nào ⇒ **`401 invalid_signature`**, không giải thích thêm.

---

## 3. Vì sao ký từng thành phần — bốn tình huống tấn công

**Kẻ tấn công có gì:** một bản sao request hợp lệ (log bị lộ, proxy bị chiếm, APM trace,
máy CI bị hack). **Không có secret** ⇒ không tự tính được signature mới.
⇒ Nó chỉ làm được **một** việc: **sửa phần KHÔNG được ký, rồi gửi lại.**

> Signature = **niêm phong**. Trong niêm phong thì an toàn; **ngoài** niêm phong nó viết lại tùy ý.

### Attack 1 — không ký PATH ⇒ đổi thu tiền thành hoàn tiền

```
POST /v1/payments   {"order_code":"ord_8222","amount":355000,...}   ← thu tiền
POST /v1/refunds    {"order_code":"ord_8222","amount":355000,...}   ← HOÀN tiền (body cùng hình dạng)
```
Nó lấy request thu tiền, **chỉ đổi path**, giữ nguyên body + signature
⇒ `HMAC(secret, body)` vẫn **khớp** ⇒ Hub **hoàn 355.000₫**.

→ **Ký PATH** ⇒ chuỗi ký đổi ⇒ signature sai ⇒ 401.

### Attack 2 — không ký `Idempotency-Key` ⇒ nhân bản 100 lần

Lấy một lệnh **hoàn tiền** hợp lệ, bắn 100 lần, mỗi lần **đổi mã**:
```
Idempotency-Key: aaa-001 / aaa-002 / ...      ← sửa tự do, signature không đổi
```
Hub tra sổ: mã nào cũng "chưa thấy" ⇒ **xử lý thật 100 lần** ⇒ mất **35,5 triệu**.

*(Với `/v1/payments` thì lớp 2 — partial `UNIQUE(merchant_id, order_code)` — chặn được.
Nhưng refund **không có** khóa đó, hoàn nhiều lần là hợp lệ về nghiệp vụ ⇒ không gì cản.)*

→ **Ký `Idempotency-Key`** ⇒ buộc phải giữ nguyên mã ⇒ 100 lần gửi = 100 lần **replay vô hại**.

> ⭐ **Then chốt:** `Idempotency-Key` chỉ chống được **kẻ xấu** khi nó **được ký**.
> Không ký thì nó chỉ chống **lỗi mạng thật thà**.

### Attack 3 — không ký TIMESTAMP ⇒ request sống vĩnh viễn

Ký hết rồi, gửi lại y nguyên ⇒ replay vô hại. **An toàn… trong 24 giờ.**
Nhớ `idempotency_records.expires_at` TTL **24h** rồi job dọn:

```
Hôm nay   kẻ tấn công lưu request lại, chưa làm gì
+25 giờ   sổ idempotency đã bị dọn
+25 giờ   gửi lại y nguyên → "mã này chưa thấy bao giờ" → XỬ LÝ THẬT
```

→ **Ký TIMESTAMP** + kiểm `|now − ts| ≤ 5 phút` ⇒ request bắt được chỉ **sống 5 phút**.
Sửa ts ⇒ signature sai; không sửa ⇒ hết hạn.

**5 phút (không phải 5 giây)** — phải chừa cho **lệch đồng hồ** giữa hai hệ thống.

### METHOD — yếu hơn, nhưng miễn phí

Đã ký path thì khó dựng tình huống chết người. Lý do đưa vào: *cùng path, method khác =
hành động khác*:
```
POST   /v1/payments/pay_9f3a/capture   → thu tiền
DELETE /v1/payments/pay_9f3a           → hủy
```
→ Nguyên tắc: **đừng để hở cái gì mà bịt lại không tốn gì.**

### Lớp bổ sung — nonce store (nếu cần chặt hơn)

Lưu signature đã thấy vào Redis, TTL = cửa sổ 5 phút. Thấy lại ⇒ từ chối luôn.
→ Redis **được phép** dùng ở đây: nó chỉ làm chặt thêm, mất dữ liệu thì lùi về các lớp trên.
*Cache được phép sai, không được phép quyết định.*

### Tổng kết niêm phong

```
     ┌──── NIÊM PHONG (được ký) ────┐
     │ METHOD  PATH  TIMESTAMP      │      ngoài niêm phong:
     │ IDEMPOTENCY_KEY  SHA256(BODY)│  ←── kẻ tấn công sửa TỰ DO
     └──────────────────────────────┘
```

| Thành phần | Trả lời câu hỏi |
|---|---|
| `SHA256(BODY)` | *nội dung có bị sửa không?* |
| `PATH` + `METHOD` | *có đúng là gửi tới **hành động** đó không?* |
| `IDEMPOTENCY_KEY` | *có phải **một lần** duy nhất không?* |
| `TIMESTAMP` | *có phải **đồ mới** không?* |

> **signature chứng minh "không bị sửa"; timestamp chứng minh "không phải đồ cũ".**
> Thiếu cái nào cũng hở.

---

## 4. So sánh constant-time (chống timing attack)

```java
computed.equals(received)          // ❌
MessageDigest.isEqual(a, b)        // ✅ constant-time
```

`String.equals` **thoát ngay** khi gặp byte khác đầu tiên ⇒ thời gian chạy **tiết lộ đã khớp
được mấy byte**. Kẻ tấn công đo thời gian, dò **từng byte một**:
32 byte × 256 lần thử, thay vì 2²⁵⁶.

→ Tên: **timing attack**. Qua mạng thì nhiễu lớn, khai thác khó — nhưng đây là **thói quen
bắt buộc** trong code bảo mật, sửa chỉ mất 1 dòng.

**Quy tắc: mọi phép so sánh giá trị bí mật đều phải constant-time.**

---

## 5. Thứ tự các thành phần — do **SPEC** quy định, không phải đoán

Câu hỏi hay gặp: *"Hub làm sao biết merchant nối chuỗi theo thứ tự nào?"*
→ **Không có đàm phán, chỉ có hợp đồng.** Hub công bố trong docs, chính xác tới từng ký tự
(xem §2). Cả hai bên viết code theo đúng bản đó.

Đây là lý do nó tên **canonical string** — *chuẩn tắc*: chỉ có **một** cách dựng nó.

**Merchant làm sai thứ tự ⇒ `401 invalid_signature`.** Và Hub **không biết** vì sao sai —
sai thứ tự, sai secret, hay bị sửa giữa đường, ba cái cho ra **cùng một kết quả**.
Đó là **đặc tính**, không phải lỗi: Hub chỉ được trả lời *"khớp / không khớp"*, gợi ý thêm
là giúp kẻ tấn công dò.

### Vậy merchant tích hợp thế nào? Gateway thật làm 3 việc

1. **Tài liệu có ví dụ số cụ thể** — secret mẫu, request mẫu, chuỗi ký mẫu, signature mẫu.
   Merchant chạy code của mình, ra đúng chuỗi đó là xong.
2. **SDK / snippet sẵn** cho từng ngôn ngữ — để merchant **không phải tự nối chuỗi**.
   90% lỗi tích hợp nằm ở bước nối chuỗi.
3. **Endpoint debug ở sandbox** — trả về chuỗi ký mà Hub **kỳ vọng**, để merchant so mắt.
   **Chỉ sandbox**, tuyệt đối không có ở production.

### Những chỗ hay lệch nhất

| Bẫy | Hậu quả |
|---|---|
| Merchant parse JSON rồi **serialize lại** trước khi băm | thứ tự field / khoảng trắng đổi ⇒ hash khác ⇒ 401 |
| Hub đọc body **sau khi** Jackson parse | mất raw bytes ⇒ phải dùng `ContentCachingRequestWrapper` |
| hex **chữ HOA** vs **chữ thường** | khác chuỗi ⇒ khác hash |
| timestamp **milli** vs **giây** | khác chuỗi |
| `\r\n` vs `\n` (merchant chạy Windows) | khác chuỗi |
| path có/không dấu `/` cuối | khác chuỗi |
| query string không **sort** | khác chuỗi |

→ Spec phải nói rõ **từng cái**; và Hub nên **normalize** những gì normalize được
(luôn `toLowerCase()` hex trước khi so).

### Ngoài đời

- **AWS SigV4** — canonical request gồm method, URI, query **đã sort**, headers **đã sort +
  lowercase**, hash payload. Rất chi tiết vì rất nhiều thứ phải chuẩn hóa.
- **Stripe** — gọn hơn: chuỗi ký = `timestamp + "." + raw_body`,
  header `Stripe-Signature: t=...,v1=...`.

> Tự do thiết kế, miễn **viết ra rõ ràng và cả hai bên làm giống nhau**.
> Đổi thứ tự = **breaking change** ⇒ đó là lý do cần `X-Signature-Version`.

---

## 6. Xoay khóa (key rotation) — hai secret sống song song

Giống cách Inc 1 xoay khóa ký JWT (`kid` + JWKS chứa 2 khóa):

```sql
-- merchants
api_secret            -- khóa hiện hành
api_secret_previous   -- khóa cũ, còn hiệu lực tới ...
previous_expires_at
```

Verify: thử `api_secret`; **sai thì thử tiếp** `api_secret_previous` (nếu chưa hết hạn).

```
T0  Hub sinh secret mới, GIỮ CẢ secret cũ  → chấp nhận CẢ HAI
T1  merchant cập nhật config sang secret mới (họ tự làm, khi nào cũng được)
T2  hết cửa sổ (vd 24h) → xóa secret cũ    → chỉ còn secret mới
```

**Không downtime**, vì luôn có **khoảng chồng lấn (overlap)**.

> **Nguyên tắc chung của mọi lần xoay khóa:**
> thêm cái mới → chờ bên kia chuyển → **mới** bỏ cái cũ.
> Không bao giờ thay thế đột ngột.

---

## 7. Checklist khi implement

- [ ] Đọc **raw body** trước khi Jackson parse (`ContentCachingRequestWrapper` hoặc filter).
- [ ] Dựng canonical string **đúng spec §2**, viết spec đó vào `docs/` trước khi code.
- [ ] `MessageDigest.isEqual`, **không** `equals`.
- [ ] Kiểm cửa sổ thời gian **5 phút** (cấu hình được).
- [ ] `401` cho mọi thất bại xác thực, **không** phân biệt nguyên nhân trong response.
- [ ] **Không log** `api_secret`, không log `X-Signature` đầy đủ (log 8 ký tự đầu là đủ debug).
- [ ] Verify **trước** khi chạm vào nghiệp vụ (filter/interceptor, không phải trong service).
- [ ] Test: signature đúng · sai · thiếu header · timestamp quá hạn · body bị sửa 1 byte ·
      path bị đổi · `Idempotency-Key` bị đổi.

---

## 8. Ba câu chốt

1. **Ký mọi thứ mà nếu bị sửa sẽ gây hại** — không chỉ body.
2. **`Idempotency-Key` chỉ chống được kẻ xấu khi nó được ký.**
3. **Canonical string là một phần của API contract**, ngang hàng tên field và mã lỗi —
   đổi nó là breaking change.
