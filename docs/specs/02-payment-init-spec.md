# Đặc tả nghiệp vụ — Increment 3: KHỞI TẠO THANH TOÁN (Merchant → Hub)

> Tài liệu BA. Mô tả **CÁI GÌ / VÌ SAO** ở góc nghiệp vụ. Phần **LÀM THẾ NÀO** (state machine
> chi tiết, schema, chuỗi ký HMAC, filter chain, API contract cụ thể) là **việc thiết kế của
> engineer** — xem mục 10.
> Đọc kèm: [00-overview.md](../00-overview.md), [identity-and-auth.md](identity-and-auth.md)
> (boundary #1), [01-merchant-spec.md](01-merchant-spec.md) (Phase 2), CLAUDE.md (nguyên tắc).

---

## 1. Bối cảnh & phạm vi

Đây là increment **đầu tiên chạm vào Payment Hub** — hệ trọng tâm của dự án.

Cho tới giờ, merchant mới chỉ tạo được Order trong DB của mình (Inc 2 Phase 1). Increment 3
mở **cây cầu đầu tiên** giữa hai tổ chức: **merchant server → Payment Hub**, và đưa **người
mua** sang trang thanh toán do Hub host.

**Trong phạm vi:**

| # | Nội dung |
|---|---|
| 1 | **Đăng ký merchant**: Hub cấp **API key + secret** (bootstrap bằng seed/admin; merchant tự quản ở Inc 7) |
| 2 | **Xác thực + toàn vẹn request**: merchant **ký request bằng HMAC**, Hub **verify**; chữ ký sai → từ chối |
| 3 | **Tạo Payment** từ yêu cầu của merchant (số tiền, mã đơn, return URL) |
| 4 | **Idempotency thật**: `Idempotency-Key` + **bản ghi idempotency có TTL**; gọi lại → **không** tạo payment thứ 2 |
| 5 | **Payment Session có TTL**: quá hạn → **EXPIRED**, không thanh toán được nữa |
| 6 | **Redirect URL**: Hub trả link để merchant điều hướng người mua sang **hosted checkout của Hub** |
| 7 | **Hosted checkout tối giản**: trang của Hub hiển thị thông tin khoản thanh toán cho người mua |
| 8 | **Merchant Demo Phase 2**: gọi Hub khi bấm "Thanh toán" + redirect người mua |

**NGOÀI phạm vi (tuyệt đối không làm ở increment này):**

| Việc | Thuộc về |
|---|---|
| **Thu tiền thật** — gọi Bank, authorize/capture | Increment 4 |
| Webhook báo kết quả về merchant | Increment 5 |
| Refund, ledger | Increment 6 |
| Merchant Portal, đăng nhập người | Increment 7 |
| Nhập thông tin thẻ, xử lý thẻ | Increment 4 (và **không bao giờ** lưu thẻ thật) |

> ⚠️ Hệ quả quan trọng: ở Inc 3, **người mua tới hosted checkout nhưng CHƯA trả được tiền**.
> Trang checkout chỉ **hiển thị** khoản cần trả + hạn thanh toán. Nút trả tiền thật xuất hiện
> ở Inc 4. Increment này chứng minh **đường đi và tính đúng đắn của việc khởi tạo**, không
> phải việc thu tiền.

---

## 2. Các bên (actors)

| Actor | Mô tả | Cách được xác thực |
|---|---|---|
| **Merchant system** (server) | Máy chủ của merchant, gọi API Hub để khởi tạo thanh toán | **API key + HMAC** (boundary #1, cross-org) |
| **Người mua (Buyer)** | Khách vãng lai, **không đăng nhập**; được redirect sang Hub | Không xác thực; truy cập bằng **link chứa token khó đoán** |
| **Payment Hub** | Hệ thống chính: nhận yêu cầu, tạo Payment, host trang checkout | — |
| **Admin/seed** | Cấp API key + secret cho merchant (bootstrap) | Ngoài phạm vi UI ở Inc 3 |

---

## 3. User stories

- **US-1:** Là **merchant**, tôi muốn **đăng ký và nhận API key + secret**, để có danh tính khi gọi Hub.
- **US-2:** Là **merchant**, tôi muốn **ký request** gửi lên Hub, để Hub tin chắc request đến
  từ tôi và **không bị sửa** trên đường truyền.
- **US-3:** Là **Hub**, tôi muốn **từ chối request có chữ ký sai/thiếu/quá cũ**, để không ai
  giả mạo được merchant.
- **US-4:** Là **merchant**, tôi muốn **tạo một yêu cầu thanh toán** cho đơn hàng của mình và
  **nhận lại link** để đưa khách sang trả tiền.
- **US-5:** Là **merchant**, tôi muốn **gọi lại cùng một yêu cầu** (do lỗi mạng/retry) mà
  **không bị tạo 2 khoản thanh toán**.
- **US-6:** Là **người mua**, tôi muốn **thấy rõ số tiền phải trả và của shop nào** khi được
  đưa sang trang thanh toán.
- **US-7:** Là **Hub**, tôi muốn **khoản thanh toán có hạn**, để link cũ không dùng mãi được.

---

## 4. Yêu cầu chức năng (Functional Requirements)

| ID | Yêu cầu |
|---|---|
| **FR-P1** | Hệ thống lưu được **danh sách merchant**, mỗi merchant có **API key** (định danh công khai) và **secret** (bí mật, dùng để ký). Secret **không lưu dạng đọc được**. |
| **FR-P2** | Merchant gọi API tạo thanh toán kèm: **định danh merchant (API key)**, **chữ ký**, **thời điểm gửi**, và **khóa idempotency**. |
| **FR-P3** | Hub **tính lại chữ ký** từ nội dung request + secret của merchant và **so khớp**. Không khớp → **từ chối**. |
| **FR-P4** | Hub **từ chối request quá cũ/quá lệch giờ** so với thời điểm nhận (chống phát lại — replay). |
| **FR-P5** | Yêu cầu tạo thanh toán gồm tối thiểu: **số tiền**, **tiền tệ**, **mã đơn phía merchant**, **return URL**, (tùy chọn) mô tả. |
| **FR-P6** | Hub tạo **Payment** với **trạng thái khởi tạo**, gắn với merchant, có **thời điểm hết hạn**. |
| **FR-P7** | Hub trả về: **mã payment**, **trạng thái**, **redirect URL**, **thời điểm hết hạn**. |
| **FR-P8** | Gọi lại **cùng `Idempotency-Key`** (cùng merchant) → trả về **đúng payment đã tạo**, **không** tạo thêm. |
| **FR-P9** | Cùng `Idempotency-Key` nhưng **nội dung khác** → **từ chối** (báo lỗi rõ ràng), không âm thầm trả kết quả cũ. |
| **FR-P10** | **Redirect URL** dẫn tới **trang checkout do Hub host**, hiển thị: tên merchant, số tiền + tiền tệ, mã đơn, **thời hạn còn lại**. |
| **FR-P11** | Truy cập trang checkout của **payment đã hết hạn** → hiển thị **đã hết hạn**, không cho tiếp tục. |
| **FR-P12** | Payment quá hạn mà chưa được thanh toán → chuyển sang trạng thái **hết hạn**. |
| **FR-P13** | Merchant chỉ được thao tác/truy vấn **payment của chính mình**. |
| **FR-P14** | (Merchant Demo — Phase 2) Khi bấm "Thanh toán": tạo Order (đã có ở Phase 1) → **gọi Hub** → **redirect** người mua sang redirect URL. |

---

## 5. Dữ liệu nghiệp vụ (business-level — engineer tự thiết kế schema)

Mô tả **thông tin cần có**, không phải kiểu dữ liệu/khóa cụ thể.

**Merchant:**
- Định danh, tên hiển thị, **API key** (công khai), **secret** (bí mật — lưu an toàn),
  trạng thái (đang hoạt động / bị khóa), thời điểm tạo.

**Payment (khoản thanh toán):**
- Mã payment (công khai, dùng đối chiếu), **merchant sở hữu**, **số tiền + tiền tệ**,
  **mã đơn phía merchant**, **trạng thái**, **return URL** (nơi trả khách về sau khi xong),
  **thời điểm tạo**, **thời điểm hết hạn**, và **thứ dùng để mở trang checkout** (khó đoán).

**Bản ghi Idempotency:**
- **Khóa** (do merchant gửi), **merchant** sở hữu khóa đó, **dấu vết nội dung request**
  (để phát hiện "cùng khóa khác nội dung"), **trạng thái xử lý**, **kết quả đã trả**,
  **thời điểm hết hiệu lực**.

> 💡 **Nguyên tắc bắt buộc (CLAUDE.md):**
> - Tiền = **số nguyên minor unit** + **currency** (ISO 4217). Không float.
> - **Không lưu dữ liệu thẻ thật** (Inc 3 chưa chạm tới thẻ — giữ nguyên tắc từ đầu).
> - **Secrets không hardcode**; secret của merchant không được lưu dạng đọc được.

---

## 6. Quy tắc nghiệp vụ (Business Rules)

| ID | Quy tắc | Vì sao |
|---|---|---|
| **BR-P1** | Request **thiếu/sai chữ ký** → từ chối, **không** tạo gì. | Chống giả mạo merchant. |
| **BR-P2** | Request có **thời điểm gửi lệch quá ngưỡng cho phép** → từ chối. | Chống **replay** (bắt được request cũ rồi gửi lại). |
| **BR-P3** | **Nội dung request bị sửa một ký tự** → chữ ký không khớp → từ chối. | Toàn vẹn dữ liệu (số tiền!). |
| **BR-P4** | **Số tiền phải > 0**; tiền tệ phải hợp lệ. | Không tạo khoản thu vô nghĩa. |
| **BR-P5** | **Cùng `Idempotency-Key` + cùng nội dung** → **một** payment duy nhất; lần sau trả **đúng kết quả lần đầu**. | Chống thu tiền 2 lần (nguyên tắc #1). |
| **BR-P6** | **Cùng `Idempotency-Key` + khác nội dung** → **từ chối**. | Khóa bị dùng sai; im lặng trả kết quả cũ sẽ che giấu bug của merchant. |
| **BR-P7** | Khóa idempotency **có thời hạn**; hết hạn thì coi như khóa mới. | Không giữ trạng thái vô hạn. |
| **BR-P8** | Payment **có hạn** (TTL); quá hạn → **hết hạn**, không thanh toán được. | Link thanh toán không được sống mãi; giá/tồn kho/ý định mua có thời hạn. |
| **BR-P9** | Link checkout phải **khó đoán**, **không** dùng số thứ tự. | Chặn người lạ dò và xem khoản thanh toán của người khác. |
| **BR-P10** | **Return URL** do merchant gửi phải được **kiểm tra hợp lệ**. | Chống **open redirect** (đưa khách sang site lừa đảo). |
| **BR-P11** | Merchant **không truy cập được payment của merchant khác**. | Cách ly tenant. |
| **BR-P12** | Mọi lần **từ chối** (chữ ký sai, replay, khóa trùng) đều phải **ghi lại** được. | Audit/điều tra (nguyên tắc #7). |

---

## 7. Màn hình tối thiểu — Hosted Checkout (phía Hub)

| Trang | Nội dung |
|---|---|
| **Trang thanh toán** (hosted checkout) | Tên merchant · **số tiền + tiền tệ** · mã đơn · **thời hạn còn lại**. **Inc 3 chưa có nút trả tiền thật** (Inc 4 mới thêm). |
| **Trang hết hạn** | Thông báo khoản thanh toán đã hết hạn, hướng dẫn quay lại shop. |

> UI **tối giản**, server-rendered là đủ (theo CLAUDE.md). Không cần đẹp.

---

## 8. Ranh giới bàn giao

- **Vào:** merchant server gọi Hub (đã ký HMAC) → Hub tạo Payment → trả redirect URL.
- **Ra:** người mua **đứng ở hosted checkout của Hub**. Việc **thu tiền** (gọi Bank,
  authorize/capture) và **báo kết quả về merchant** (webhook, return URL) **thuộc Increment 4–5**.
- Merchant Demo: Phase 2 chỉ tới mức **gọi Hub + redirect**; xử lý kết quả quay về là Phase sau.

---

## 9. Tiêu chí hoàn thành (Definition of Done)

- ✅ Seed được **ít nhất 1 merchant** có API key + secret.
- ✅ Merchant **ký request**; Hub **chấp nhận chữ ký đúng**.
- ✅ Hub **từ chối**: chữ ký sai · thiếu chữ ký · **request quá cũ** (replay) · body bị sửa.
- ✅ Tạo payment thành công → trả **mã payment + redirect URL + thời điểm hết hạn**.
- ✅ Gọi **2 lần cùng `Idempotency-Key`** (cùng body) → **chỉ 1 payment** trong DB, lần 2 trả
  **cùng kết quả**.
- ✅ Cùng key **khác body** → **bị từ chối**.
- ✅ Mở redirect URL → thấy trang checkout với **đúng số tiền + merchant**.
- ✅ Payment **quá hạn** → trạng thái **hết hạn**, trang checkout báo hết hạn.
- ✅ Merchant Demo: bấm "Thanh toán" → tạo Order → gọi Hub → **redirect** sang Hub.
- ✅ **Có test tự động** cho **chữ ký** và **idempotency** (gồm cả **gọi đồng thời**).

---

## 🎯 Nhiệm vụ thiết kế của bạn (engineer)

Từ spec trên, **bạn thiết kế trước** (chưa code), mình review từng phần:

1. **State machine của Payment** (phía Hub): các trạng thái + chuyển tiếp + trigger; cái nào
   terminal. *(Nhớ: mới ở Inc 3 — nhưng hãy nghĩ tới cả Inc 4/5 để không phải đập đi.)*
2. **Mô hình dữ liệu**: Merchant · Payment · bản ghi Idempotency — field & quan hệ.
3. **Cơ chế ký HMAC**: **ký trên cái gì** (chuỗi chuẩn hóa gồm những thành phần nào, theo thứ
   tự nào), gửi qua **header nào**, **chống replay** ra sao, và **so sánh chữ ký** cần lưu ý gì.
4. **Thiết kế idempotency**: lưu ở đâu (Redis/DB), bản ghi gồm gì, xử lý ca **"request đầu
   đang chạy dở"**, TTL bao lâu, phát hiện "cùng key khác body" bằng cách nào.
5. **Thứ tự filter chain**: auth → signature → idempotency — **vì sao thứ tự đó**, cái nào
   phải chạy trước cái nào và tại sao.
6. **Redirect URL & truy cập checkout**: dùng gì để định danh (đảm bảo khó đoán), sống bao lâu.
7. **API contract**: endpoint, header, body request/response, mã lỗi (`error_code`) cho từng ca từ chối.

Nộp từng phần, mình review như senior. Bí chỗ nào cứ hỏi — mình cho hint theo tầng.
