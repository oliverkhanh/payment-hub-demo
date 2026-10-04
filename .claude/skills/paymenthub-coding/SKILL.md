---
name: paymenthub-coding
description: >
  Quy ước viết code cho Payment Hub: Clear > Short, Explicit > Implicit,
  Easy to debug > Compact. Cấm one-liner gom nhiều logic, nested ternary,
  stream/Optional chain dài, magic number, abstraction tạo sớm, swallow
  exception. Business rule phải nhìn thấy ở tầng ngoài. KHÔNG comment trong
  code. Không tự ý đổi architecture/schema/API contract. Gọi skill này TRƯỚC
  KHI viết hoặc sửa bất kỳ code Java nào trong payment-hub, fake-bank,
  merchant-demo, identity-provider — kể cả sửa nhỏ một hàm.
---

# Quy ước viết code — Payment Hub

## 1. Mục tiêu

Thứ tự ưu tiên khi có xung đột:

- Clear > Short
- Readable > Clever
- Explicit > Implicit
- Easy to debug > Compact

Code phải thể hiện rõ **ý định** và **business logic**. Không tối ưu số dòng
nếu việc rút gọn làm giảm khả năng đọc hiểu.

## 2. Không viết code quá vắn tắt

Không gom nhiều logic vào một dòng hoặc một expression.

Không nên:

```java
return users.stream()
    .filter(u -> u.isActive() && u.getAge() >= 18)
    .map(User::getName)
    .toList();
```

Khi logic mang business rule, tách ra cho rõ:

```java
List<User> activeAdults = users.stream()
    .filter(User::isActive)
    .filter(user -> user.getAge() >= 18)
    .toList();

return activeAdults.stream()
    .map(User::getName)
    .toList();
```

Không phải lúc nào cũng cần tách. Nhưng khi logic phức tạp thì đừng hy sinh
readability để giảm số dòng.

## 3. Không dùng trick / clever code

Tránh:

- Nested ternary
- Lambda quá phức tạp
- Optional chain quá dài
- Stream chain quá dài
- Generic abstraction không cần thiết
- Reflection khi không cần
- Magic number / magic string

Nếu một đoạn code cần giải thích mới hiểu được thì viết lại cho dễ đọc.

## 4. Business logic phải dễ nhìn thấy

Không giấu business rule bên trong abstraction quá sâu. Người review nhìn vào
phải hiểu ngay trình tự:

```java
if (payment.isExpired()) {
    throw new PaymentExpiredException();
}

if (!payment.canBeCaptured()) {
    throw new InvalidPaymentStateException();
}

payment.capture();
```

Đọc một lượt là ra: kiểm tra expired → kiểm tra state → capture.

## 5. Tách logic khi cần

Một method nên có một mục đích rõ ràng. Nếu method làm quá nhiều bước
(validate → load → calculate → update → publish event) thì tách thành các
method có tên thể hiện **intent**:

```java
validatePayment();

Payment payment = loadPayment();

PaymentResult result = calculatePayment(payment);

updatePayment(payment, result);

publishPaymentCapturedEvent(payment);
```

Tên method phải giúp người đọc hiểu **tại sao** đang làm việc đó, không chỉ
hiểu đang gọi hàm gì.

⚠️ Mặt trái: đừng extract một danh sách guard clause đã phẳng và dễ đọc thành
nhiều hàm con. Guard clause xếp dọc vốn đã là dạng dễ đọc nhất — gom chúng vào
một hàm phụ biến danh sách "mọi lý do dừng" thành hộp đen. Tối ưu **số lần
người đọc phải nhảy hàm**, không phải số dòng mỗi hàm.

## 6. Tên biến / method phải rõ nghĩa

Ưu tiên: `paymentId`, `merchantId`, `bankTransactionId`, `remainingAmount`,
`expiredAt`.

Tránh: `id`, `id2`, `tmp`, `data`, `obj`, `result`, `x` — trừ khi phạm vi rất
nhỏ và ý nghĩa đã rõ.

Quy ước hậu tố của dự án: repository → `...Repository`,
configuration properties → `...Properties`. Áp dụng tương tự cho các loại khác.

## 7. Không tạo abstraction quá sớm

Không tạo `Factory` / `Strategy` / `Helper` / `Util` / `Manager` / `Processor`
/ `Handler` chỉ vì "có thể sau này sẽ cần".

Chỉ tạo khi có lý do thực tế:

- Có nhiều implementation
- Có variation rõ ràng
- Có business rule cần tách
- Có khả năng thay đổi độc lập
- Giúp test hoặc maintain dễ hơn

## 8. Ưu tiên code dễ debug

Khi lỗi xảy ra, phải dễ đặt breakpoint → xem biến → xem flow → xác định nguyên
nhân. Không gom quá nhiều logic vào một expression.

## 9. Không comment trong code

**Không viết comment** (`//`, `/* */`, javadoc) trong code Java. Code phải tự
diễn đạt qua tên biến / hàm / class.

Phần "tại sao" — business rule, workaround, constraint, side effect, quyết định
thiết kế — không biến mất, mà chuyển chỗ:

- `docs/notes/` cho kiến thức cần giữ lại
- `docs/specs/` cho thiết kế
- Câu trả lời review trong chat

Ví dụ: thay vì comment "bank có thể gửi callback nhiều lần nên phải idempotent",
đặt tên hàm và ràng buộc DB nói lên điều đó, rồi ghi lý do vào
`docs/notes/`.

Comment trong SQL migration / YAML / `pom.xml` / `Dockerfile` hiện vẫn giữ —
hỏi trước nếu định đụng vào.

## 10. Error handling phải explicit

Không swallow exception:

```java
try {
    processPayment();
} catch (Exception ignored) {
}
```

Không `catch (Exception)` nếu không có lý do rõ ràng. Xử lý exception phải trả
lời được: cái gì hỏng, vì sao, có retry được không, có cần rollback không, có
nên để lỗi lan lên không.

## 11. Không tự ý thay đổi architecture

Khi implement một task, KHÔNG tự ý:

- Introduce framework mới
- Đổi architecture
- Refactor những phần không liên quan
- Đổi API contract
- Đổi database schema

Nếu thấy cần → **nêu vấn đề và chờ developer quyết**, không làm rồi báo cáo sau.

## 12. Trước khi coding

Với feature không trivial, xác định trước:

1. Goal
2. Relevant files
3. Existing flow
4. Proposed approach
5. Edge cases
6. Potential side effects

Nếu yêu cầu chưa rõ hoặc có nhiều hướng thiết kế: không code ngay — trình bày
approach và trade-off trước.

## 13. AI không quyết định thay developer

Developer sở hữu idea / architecture / business logic. AI làm implementation /
syntax / boilerplate.

AI được phép: suggest, explain trade-off, implement, review, tìm bug, đề xuất
cải tiến. AI **không** tự quyết vấn đề architectural hoặc business-critical.

Khi có nhiều cách làm hợp lý: trình bày Option A / B / C kèm trade-off, để
developer chọn.

## 14. Code phải phục vụ việc học và ownership

Code phải đủ rõ để developer: đọc → hiểu → debug → sửa → dựng lại được.

Mục tiêu không phải "AI generate code nhanh nhất", mà là "developer vẫn hiểu và
làm chủ hệ thống, trong khi AI tăng tốc phần implementation".

## Sau khi implement

Luôn báo cáo: **file nào thay đổi**, **implement thế nào**, **vì sao chọn cách
đó**, rồi chờ developer review trước khi làm phần tiếp theo.
