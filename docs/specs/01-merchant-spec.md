# Đặc tả nghiệp vụ — Module MERCHANT

> Tài liệu BA. Mô tả **CÁI GÌ / VÌ SAO** ở góc nghiệp vụ. Phần **LÀM THẾ NÀO** (pages,
> component, data model kỹ thuật, API call cụ thể) là việc thiết kế của engineer.
> Đọc kèm: [00-overview.md](../00-overview.md) (ranh giới Merchant), CLAUDE.md (nguyên tắc).

---

## 1. Bối cảnh & phạm vi module

Merchant Demo là **website bán hàng giả lập tối giản**, đóng vai "khách hàng của Payment
Hub". Mục đích **duy nhất**: tạo ra một đơn hàng thật rồi đẩy sang Payment Hub để demo &
kiểm thử luồng thanh toán. Nó **không** phải sản phẩm e-commerce.

**Trong phạm vi (module này sẽ làm, chia theo phase):**

| Phase | Nội dung | Trạng thái |
|---|---|---|
| **Phase 1** | Duyệt sản phẩm → giỏ hàng → checkout → tạo đơn → bấm "Thanh toán" | 👈 **Tài liệu này** |
| Phase 2 | Redirect sang Payment Hub & nhận kết quả quay về (return URL) | Sau |
| Phase 3 | Nhận **webhook** từ Hub → cập nhật trạng thái đơn | Sau |
| Phase 4 | Trang **danh sách đơn hàng** + trạng thái | Sau |
| Phase 5 | Trang **giả lập lỗi (Chaos)**: webhook trả 500 / timeout / chậm / down | Sau |

**Ngoài phạm vi (KHÔNG làm — theo CLAUDE.md):** login, user management, inventory/tồn kho,
voucher, khuyến mãi, shipping, admin phức tạp, dashboard. Đừng thêm.

## 2. Các bên (actors)

| Actor | Mô tả |
|---|---|
| **Người mua (Buyer/Guest)** | Khách vãng lai, **không đăng nhập**. Duyệt hàng, bỏ giỏ, thanh toán. |
| **Merchant system** | Website + backend merchant. Quản lý sản phẩm giả, giỏ hàng, đơn hàng. |
| **Payment Hub** | Hệ thống ngoài. Phase 1 chỉ *chạm tới* ở bước cuối (khởi tạo thanh toán). |

---

# PHASE 1 — Duyệt sản phẩm → Mua hàng

## 3. User stories

- **US-1:** Là người mua, tôi muốn **xem danh sách sản phẩm** (tên, giá, ảnh) để chọn đồ.
- **US-2:** Là người mua, tôi muốn **thêm sản phẩm vào giỏ** và chỉnh số lượng.
- **US-3:** Là người mua, tôi muốn **xem giỏ hàng** với tổng tiền để kiểm tra trước khi trả.
- **US-4:** Là người mua, tôi muốn bấm **"Thanh toán"** để bắt đầu trả tiền cho đơn của mình.
- **US-5:** Là merchant, tôi muốn **tạo một đơn hàng (order)** ngay khi người mua bấm thanh
  toán, để về sau đối chiếu kết quả từ Payment Hub với đơn này.

## 4. Yêu cầu chức năng (Functional Requirements)

| ID | Yêu cầu |
|---|---|
| **FR-M1** | Hệ thống hiển thị **5–10 sản phẩm giả** (dữ liệu seed sẵn, cố định). |
| **FR-M2** | Mỗi sản phẩm có: tên, giá, tiền tệ, ảnh (placeholder), mô tả ngắn. |
| **FR-M3** | Người mua thêm/bớt sản phẩm vào **giỏ hàng**, đổi số lượng, xóa khỏi giỏ. |
| **FR-M4** | Giỏ hàng tính **tổng tiền** = Σ(giá × số lượng). |
| **FR-M5** | Trang **checkout** hiển thị lại các món + tổng tiền để xác nhận. |
| **FR-M6** | Khi bấm "Thanh toán", hệ thống **tạo một Order** với trạng thái ban đầu (chờ thanh toán) và lưu lại. |
| **FR-M7** | Sau khi tạo Order, hệ thống **khởi tạo yêu cầu thanh toán** với Payment Hub cho đơn đó (chi tiết bước gọi Hub: Phase 2). |
| **FR-M8** | Bấm "Thanh toán" **hai lần** cho cùng một đơn **không được tạo 2 giao dịch** (chống trùng). |

## 5. Dữ liệu nghiệp vụ (business-level — engineer tự thiết kế schema)

Mô tả **thông tin** cần có, không phải kiểu dữ liệu/khóa cụ thể (đó là việc thiết kế của bạn).

**Sản phẩm (Product):**
- Định danh, tên, mô tả ngắn, **giá**, **tiền tệ**, ảnh (URL placeholder).

**Giỏ hàng (Cart):**
- Danh sách dòng hàng, mỗi dòng: sản phẩm + số lượng. Giỏ gắn với **phiên duyệt** của
  khách (không cần user account). Tổng tiền suy ra từ các dòng.

**Đơn hàng (Order)** — tạo khi bấm Thanh toán:
- Định danh đơn (mã đơn), danh sách món đã mua + số lượng + giá **tại thời điểm đặt**
  (chốt giá, không đổi về sau), **tổng tiền + tiền tệ**, **trạng thái đơn**, thời điểm tạo,
  và **chỗ để tham chiếu tới giao dịch bên Payment Hub** (điền ở Phase 2).

> 💡 **Lưu ý nguyên tắc (CLAUDE.md):** tiền lưu bằng **số nguyên minor unit** (vd 10.000đ →
> `10000`) kèm **currency** (ISO 4217, vd `VND`). Không dùng float. Áp dụng ngay từ Merchant.

## 6. Quy tắc nghiệp vụ (Business Rules)

| ID | Quy tắc | Vì sao |
|---|---|---|
| **BR-M1** | Giỏ hàng **rỗng thì không cho checkout**. | Không có gì để trả. |
| **BR-M2** | **Chốt giá tại thời điểm tạo Order.** Giá sản phẩm đổi về sau không ảnh hưởng đơn đã tạo. | Đơn là "hợp đồng" tại thời điểm mua. |
| **BR-M3** | Tổng tiền Order = tổng các dòng, tính lại phía **server** (không tin số client gửi lên). | Chống client sửa giá. |
| **BR-M4** | Một Order khi vừa tạo ở trạng thái **"chờ thanh toán"**; chưa được coi là đã trả. | Tiền chưa về. |
| **BR-M5** | Bấm Thanh toán nhiều lần cho cùng đơn → chỉ **một** yêu cầu thanh toán hiệu lực. | Chống double-charge (bài học idempotency — chi tiết cơ chế ở module Hub). |

## 7. Các trang (screens) tối thiểu — Phase 1

| Trang | Nội dung |
|---|---|
| **Danh sách sản phẩm** | Lưới 5–10 sản phẩm, nút "Thêm vào giỏ". |
| **Giỏ hàng** | Các dòng hàng, chỉnh số lượng/xóa, tổng tiền, nút "Checkout". |
| **Checkout** | Xác nhận lại đơn + tổng tiền, nút **"Thanh toán"**. |

> UI **tối giản, không cần đẹp** (theo CLAUDE.md). Có thể gộp giỏ hàng + checkout nếu muốn.

## 8. Điểm bàn giao sang Payment Hub (ranh giới module)

Phase 1 **kết thúc** ngay tại khoảnh khắc bấm "Thanh toán": merchant đã (1) tạo Order
"chờ thanh toán", (2) sẵn sàng gọi Payment Hub. **Chi tiết** merchant gửi gì cho Hub (số
tiền, mã đơn, return URL, chữ ký…) và nhận lại gì (redirect URL) → thuộc **spec module
Payment Hub** và **Phase 2** của merchant. Ở đây chỉ cần biết: *"Order đã tạo, giờ đẩy
sang Hub để thu tiền."*

## 9. Tiêu chí hoàn thành Phase 1 (Acceptance Criteria)

- ✅ Vào web thấy 5–10 sản phẩm (seed sẵn).
- ✅ Thêm/bớt/sửa số lượng trong giỏ; tổng tiền đúng.
- ✅ Checkout hiển thị đúng đơn; bấm "Thanh toán" **tạo được một Order** trạng thái "chờ
  thanh toán" trong DB merchant.
- ✅ Tổng tiền được tính lại ở server; tiền lưu dạng minor unit + currency.
- ✅ Giỏ rỗng không checkout được.
- ✅ (Chuẩn bị cho Phase 2) Có điểm móc để khởi tạo thanh toán với Hub.

---

## 🎯 Nhiệm vụ thiết kế của bạn (engineer)

Từ spec trên, **bạn thiết kế** (chưa cần code, thiết kế trước):

1. **Vòng đời trạng thái của Order phía merchant** (state machine *đơn giản*): Order đi qua
   những trạng thái nào trong toàn bộ vòng đời (gợi ý: nghĩ tới cả Phase sau — chờ thanh
   toán → đã trả / thất bại / hủy…). Liệt kê states + transitions như bài state machine
   trước, nhưng đây là **order phía merchant**, đơn giản hơn nhiều so với payment phía Hub.
2. **Mô hình dữ liệu** (bảng/entity) cho Product, Cart, Order — chọn field & quan hệ.
3. **Chống trùng (BR-M5):** nêu *ý tưởng* để bấm Thanh toán 2 lần không tạo 2 giao dịch
   (chưa cần chi tiết kỹ thuật, chỉ hướng tiếp cận).

Nộp bản thiết kế của bạn, mình review. Bí khái niệm nào cứ hỏi.
