# Ghi chú — Payment State Machine: luồng đầy đủ bằng ví dụ (Increment 3+)

> State machine của **Payment phía Hub** — aggregate trung tâm của dự án. Kể bằng một câu
> chuyện có số liệu cụ thể, để phân biệt rõ AUTHORIZED / CAPTURED / VOIDED / REFUNDED.
> Đọc kèm: [02-payment-init-spec.md](../specs/02-payment-init-spec.md)

> ⚠️ **ĐÍNH CHÍNH (đọc trước):** mọi chỗ trong tài liệu này ghi *"Bank timeout → FAILED"*
> là **SAI**. Timeout = **không biết**, không phải thất bại ⇒ payment **ở lại
> `IN_PROGRESS`** cho tới khi job đối soát hỏi lại Bank. Đoán FAILED khi Bank đã trừ tiền
> ⇒ khách bị trừ **hai lần**. Xem [bank-call-timeout-and-retry.md](bank-call-timeout-and-retry.md).
>
> Tài liệu đó cũng ghi sửa đổi thứ hai: `UNIQUE(merchant_id, order_code)` phải là
> **partial index theo `status`**, nếu không nó chặn luôn việc thử lại hợp lệ sau `EXPIRED`.

---

## 0. Khái niệm nền: Authorize ≠ Capture

Thanh toán thẻ gồm **2 bước tách rời**:

| Bước | Là gì | Tiền |
|---|---|---|
| **① Authorize (giữ/hold)** | Hỏi bank *"thẻ còn tiền không? Giữ giúp tôi."* | Bank **khóa hạn mức**, tiền **CHƯA rời** tài khoản |
| **② Capture (thu)** | *"Thu số tiền đã giữ đó cho tôi."* | Tiền **thật sự chuyển** |

Đời thật:
- **Khách sạn**: check-in giữ 5tr (authorize) → check-out thu 3tr (capture 3tr, nhả 2tr).
- **Đổ xăng**: giữ 2tr → bơm 700k → chỉ thu 700k.
- **Shop online**: authorize lúc đặt → **capture khi giao hàng**.

→ Vì thế tồn tại tình trạng **"đã giữ, chưa thu"** — không phải thành công, cũng không phải
thất bại. Nó xứng đáng **một state riêng** (`AUTHORIZED`).

---

## 0.5 ⭐ Bức tranh toàn cảnh — 3 giai đoạn, ai làm gì

### 🔴 Hiểu nhầm phổ biến nhất

> *"Merchant gọi Hub → Hub gọi Bank luôn."*

**SAI.** Giữa hai việc đó là **NGƯỜI MUA**:

```
❌  Merchant gọi Hub → Hub gọi Bank

✅  Merchant gọi Hub  →  [Hub CHỜ Ana]  →  Ana nhập thẻ  →  Hub gọi Bank
      (CREATED)          có thể 10 phút    (IN_PROGRESS)
```

**Vì sao Hub chưa thể gọi Bank lúc tạo payment?** Vì **chưa có thông tin thẻ**! Merchant chỉ
gửi *"thu giúp tôi 355k cho đơn ord_abc"* — **thẻ nào** thì phải chờ Ana sang trang checkout
nhập vào. Không có thẻ → không gọi Bank được.

→ **`CREATED` = đã có lệnh thu, đang chờ Ana xuất hiện.** Đây chính là "payment session",
và nó **có TTL** vì không thể chờ Ana mãi.

### Dòng thời gian — ai làm gì

```
┌─ GIAI ĐOẠN 1: chờ ANA ──────────────────────────────────┐
│ Ana bấm "Thanh toán" ở SHOP                             │
│ Merchant → Hub: "thu 355k cho ord_abc"                  │
│ Hub tạo payment ................................ CREATED │
│ Hub trả link → Ana redirect sang trang Hub              │
│                                                          │
│  Ana đóng tab, không quay lại ................. EXPIRED  │
│  Ana bấm "quay lại shop" .................... CANCELLED  │
└──────────────────────────────────────────────────────────┘
                    │ Ana NHẬP THẺ, bấm "Trả tiền"
                    ▼
┌─ GIAI ĐOẠN 2: chờ BANK ─────────────────────────────────┐
│ Hub → Bank: "giữ 355k thẻ này" ............ IN_PROGRESS │
│                                                          │
│  Bank: "không đủ tiền" ......................... FAILED  │
│  Bank: im lặng (timeout) .............. FAILED ⚠️ nguy   │
│  Bank: "OK, giữ rồi, mã auth_77" ........... AUTHORIZED │
└──────────────────────────────────────────────────────────┘
                    │ tiền ĐANG BỊ GIỮ (chưa rời tài khoản)
                    ▼
┌─ GIAI ĐOẠN 3: THU hay NHẢ ──────────────────────────────┐
│  Hub → Bank "thu đi" ......................... CAPTURED  │ ← tiền RỜI tài khoản
│  Hub → Bank "nhả ra" (hết hàng/hết hạn) ........ VOIDED  │ ← tiền về nguyên
└──────────────────────────────────────────────────────────┘
                    │ (Inc 6) Ana trả hàng → merchant gọi Hub
                    ▼
                REFUNDED   ← tiền chạy NGƯỢC về Ana
```

### Bốn cột mốc quyết định

| Mốc | Ai bấm / làm | Trước → Sau |
|---|---|---|
| 1 | **Merchant** gọi API | *(chưa có)* → CREATED |
| 2 | **Ana** nhập thẻ, bấm "Trả tiền" | CREATED → IN_PROGRESS |
| 3 | **Bank** trả lời | IN_PROGRESS → AUTHORIZED / FAILED |
| 4 | **Hub/Merchant** quyết định | AUTHORIZED → CAPTURED / VOIDED |

> **Câu chốt để hết mơ hồ:**
> **`CREATED` = chờ ANA. `IN_PROGRESS` = chờ BANK.** Hai cái chờ khác nhau, hai giai đoạn khác nhau.
> EXPIRED/CANCELLED chết ở **giai đoạn chờ Ana** → Bank chưa biết gì.
> VOIDED/CAPTURED xảy ra **sau khi Bank đã giữ tiền**.

### FAQ — những chỗ hay rối

**❓ "EXPIRED là sao? Khách bấm thanh toán rồi mà?"**
Ana **chưa hề bấm gì ở Hub cả**. Phân biệt **hai cú bấm**:
- bấm *"Thanh toán"* ở **SHOP** → merchant tạo payment (CREATED)
- bấm *"Trả tiền"* ở **trang Hub** (sau khi nhập thẻ) → mới gọi Bank (IN_PROGRESS)

EXPIRED = Ana được redirect sang Hub rồi **đóng tab**, không bao giờ nhập thẻ → hết TTL.
**Chưa có lần gọi Bank nào.**

**❓ "CANCELLED thì Bank có xử lý tiếp không?"**
**Không — Bank chưa từng tham gia.** CANCELLED rẽ từ `CREATED`, lúc Hub còn đang chờ Ana.
*(Nếu đã AUTHORIZED rồi mới muốn hủy → đó là **VOID**, khác state cha.)*

**❓ "AUTHORIZED → CAPTURED qua bước nào, mất bao lâu?"**
Chỉ là **một lệnh nữa**: `Hub → Bank: "capture hold auth_77"` (vài trăm ms).
Nhưng **KHI NÀO capture là quyết định nghiệp vụ**:

| Kiểu | Capture lúc nào | Ví dụ |
|---|---|---|
| **Ngay** | authorize xong thu luôn | dịch vụ số, nạp tiền |
| **Trì hoãn** | authorize lúc đặt, **capture khi giao hàng** | shop bán đồ vật lý |

Hold **có hạn** (~7 ngày). Quá hạn không capture → Bank **tự nhả** → VOIDED.
*(Dự án ta: Inc 4 làm authorize + capture liền nhau cho đơn giản.)*

**❓ "VOIDED xảy ra khi nào?"** Chỉ từ `AUTHORIZED`, 3 trường hợp:
merchant hủy đơn (hết hàng) · nghi ngờ gian lận · hold hết hạn (Bank tự nhả).

**❓ "REFUNDED — khách tự gọi Hub à?"** Không:
```
Ana trả hàng → liên hệ MERCHANT (không phải Hub/Bank)
   → Merchant đồng ý hoàn → Merchant gọi API Hub
   → Hub gọi Bank hoàn tiền → vài ngày sau tiền về Ana
```
**Ana không bao giờ gọi Hub trực tiếp** — shop mới là bên quyết định hoàn tiền.

---

## 1. Nhân vật của ví dụ

- **Khách:** Ana — mua cà phê + bánh, **355.000₫**
- **Merchant:** shop "Chợ Phiên"
- **Hub:** Payment Hub
- **Bank:** ngân hàng phát hành thẻ của Ana
- **Số dư Ana ban đầu:** 2.000.000₫

---

## 2. 🟢 Luồng chính — thành công trọn vẹn

### ① Merchant tạo payment → `CREATED`
```
10:00  Ana bấm "Thanh toán" ở shop
       Merchant → POST /v1/payments {amount: 355000, VND, orderCode: ord_abc}
       Hub tạo payment pay_x9k2, TTL 15 phút (hết hạn 10:15)
       Hub → trả redirectUrl
```
→ **`CREATED`** — link đã có, **chưa ai đụng vào**.
*Ana: số dư 2.000.000₫ · khả dụng 2.000.000₫*

### ② Ana bắt đầu trả → `IN_PROGRESS`
```
10:03  Ana redirect sang hosted checkout của Hub
       Thấy: "Chợ Phiên — 355.000₫ — còn 12 phút"
       Ana nhập thẻ, bấm Trả tiền
       Hub → gọi Bank: "giữ giúp 355.000₫"
```
→ **`IN_PROGRESS`** — **đang chờ Bank trả lời** (bất đồng bộ: vài giây → vài chục giây, có OTP).

### ③ Bank giữ tiền → `AUTHORIZED`
```
10:04  Bank: "OK, đã giữ 355.000₫, mã hold: auth_77"
```
→ **`AUTHORIZED`** — **tiền BỊ GIỮ, CHƯA rời tài khoản**.
*Ana: số dư **2.000.000₫** (y nguyên!) · **khả dụng 1.645.000₫** (bị khóa 355k)*
*App bank của Ana: "Giao dịch đang chờ xử lý — 355.000₫"*

### ④ Hub thu tiền → `CAPTURED`
```
10:04  Hub → Bank: "thu 355.000₫ của hold auth_77"
       Bank: "OK, đã thu"
```
→ **`CAPTURED`** — **tiền THẬT SỰ chuyển** sang merchant.
*Ana: số dư **1.645.000₫** · Sao kê: `-355.000₫ Chợ Phiên`*

```
CREATED → IN_PROGRESS → AUTHORIZED → CAPTURED   ✅
```

---

## 3. Các nhánh rẽ

### 🔵 Rẽ ở `CREATED`

**Ana không vào link → `EXPIRED`**
```
10:00 tạo payment (hết hạn 10:15)
10:15 Ana chưa từng mở link → job quét phát hiện hết TTL
```
→ `EXPIRED`. Link chết. Tiền **chưa bao giờ đụng tới**.

**Ana đổi ý ngay → `CANCELLED`**
```
10:02 Ana bấm "Quay lại shop" / merchant gọi hủy
```
→ `CANCELLED`. Khác EXPIRED: **có người chủ động hủy**, không phải hết giờ.

### 🔴 Rẽ ở `IN_PROGRESS` — **HAI đường tới `FAILED`**

**Đường 1 — Bank TRẢ LỜI "không":**
```
10:04  Bank: "từ chối — không đủ số dư"
```
→ `FAILED`. Rõ ràng, biết chắc **không có tiền nào bị giữ**.

**Đường 2 — Bank KHÔNG TRẢ LỜI (timeout):**
```
10:04  Hub gọi Bank... chờ 30s... im lặng
```
→ `FAILED`, nhưng **nguy hiểm hơn nhiều**: **KHÔNG BIẾT** Bank đã giữ tiền hay chưa.
⚠️ Có thể Bank **đã giữ 355k** mà Hub tưởng thất bại → **tiền Ana bị treo oan**.
→ Đây là lý do **Inc 10 cần đối soát (reconciliation)**: đối chiếu file sao kê của Bank
để phát hiện những ca lệch này.

### 🟡 Rẽ ở `AUTHORIZED` → `VOIDED` (đã giữ, KHÔNG thu)

```
10:04  AUTHORIZED — đã giữ 355.000₫
10:06  Shop: "hết bánh đậu xanh rồi!" → Hub gọi Bank: "nhả hold auth_77"
```
→ `VOIDED`
*Ana: số dư 2.000.000₫ · **khả dụng về lại 2.000.000₫***
*Sao kê Ana: **KHÔNG CÓ DÒNG NÀO** — như chưa từng xảy ra*
*Merchant: **không mất phí***

Hoặc: Hub quên capture → sau vài ngày **Bank tự nhả hold** → cũng `VOIDED`.

### 🟣 Rẽ ở `CAPTURED` → `REFUNDED` (đã thu, TRẢ LẠI) — *Inc 6*

```
Ngày 1  CAPTURED — đã thu 355.000₫
Ngày 4  Ana trả hàng → merchant yêu cầu hoàn tiền
        Hub → Bank: "hoàn 355.000₫"
Ngày 6  tiền về tới tài khoản Ana
```
→ `REFUNDED`
*Sao kê Ana: **2 dòng** → `-355.000₫ (ngày 1)` và `+355.000₫ (ngày 6)`*
*Merchant: **mất phí xử lý**, phải **ghi bút toán ngược** vào ledger*

---

## 4. So sánh 3 ca "Ana không mất tiền" — khác nhau hoàn toàn

| | **EXPIRED / CANCELLED** | **VOIDED** | **REFUNDED** |
|---|---|---|---|
| Dừng ở đâu | trước khi Bank tham gia | sau khi **giữ** | sau khi **thu** |
| Tiền có bị giữ? | ❌ | ✅ rồi nhả | ✅ rồi thu rồi trả |
| Sao kê Ana | không gì | **không gì** | **2 dòng** |
| Phí merchant | 0 | **0** | **có phí** |
| Ghi ledger | không | **không** | **bút toán ngược** |
| Thời gian | ngay | ngay | **vài ngày** |

→ Đây là bằng chứng vì sao **phải tách `VOIDED` và `REFUNDED`**: cùng là "khách không mất
tiền" nhưng **chi phí, sổ sách, trải nghiệm, thời gian** khác hẳn.

**Một câu:** *VOID = chưa từng lấy tiền nên "quên nó đi". REFUND = đã lấy rồi nên phải "trả lại".*

---

## 5. Bản đồ đầy đủ

```
                          merchant gọi API
                                 ↓
                            ╔═CREATED═╗
         khách bấm trả ↙         │hết TTL↘ hủy chủ động
                                 │
        IN_PROGRESS           EXPIRED   CANCELLED
        ↙         ↘
   Bank OK      Bank từ chối / im lặng
      ↓                ↓
 ╔AUTHORIZED╗       FAILED ⚠️(timeout: không rõ đã giữ chưa)
   ↙       ↘
capture   hủy / hold hết hạn
   ↓          ↓
CAPTURED   VOIDED
   ↓ (Inc 6)
REFUNDED
```

### Bảng transitions

| Từ | Trigger (ai gây ra) | Tới |
|---|---|---|
| *(chưa có)* | **Merchant** gọi `POST /v1/payments` | CREATED |
| CREATED | **Khách** bấm trả tiền ở hosted checkout | IN_PROGRESS |
| CREATED | **Hệ thống** — hết TTL | EXPIRED |
| CREATED | **Khách/Merchant** hủy | CANCELLED |
| IN_PROGRESS | **Bank** báo giữ tiền OK | AUTHORIZED |
| IN_PROGRESS | **Bank** từ chối | FAILED |
| IN_PROGRESS | **Hệ thống** — Bank timeout | FAILED ⚠️ |
| AUTHORIZED | **Hub** capture thành công | CAPTURED |
| AUTHORIZED | **Merchant/Hub** hủy trước khi thu | VOIDED |
| AUTHORIZED | **Hệ thống** — hold hết hạn, Bank tự nhả | VOIDED |
| CAPTURED | **Merchant** yêu cầu hoàn tiền *(Inc 6)* | REFUNDED |

**Terminal:** CAPTURED* · FAILED · EXPIRED · CANCELLED · VOIDED · REFUNDED
<sub>*CAPTURED terminal ở Inc 3–5; Inc 6 mở tiếp sang REFUNDED.</sub>

---

## 6. Phạm vi từng Increment

| | Inc 3 chạm | Để sau |
|---|---|---|
| **States** | CREATED · EXPIRED · CANCELLED | IN_PROGRESS, AUTHORIZED, CAPTURED, FAILED, VOIDED *(Inc 4–5)* · REFUNDED *(Inc 6)* |
| **Transitions** | tạo → CREATED · hết TTL → EXPIRED | còn lại |

→ Inc 3 **code đủ 9 state trong enum** (để khỏi đập lại), nhưng **chỉ 2 transition chạy thật**.
Ở Inc 3, khách tới hosted checkout **chỉ XEM** (chưa có nút trả tiền — chưa có Bank).

---

## 7. Năm điểm thiết kế đáng học

**① Tách `CREATED` và `IN_PROGRESS`.** "Chưa ai đụng" ≠ "đang xử lý dở". Gộp lại thì không
biết nên **cho hết hạn** hay **phải đi hỏi Bank xem sao**.

**② `AUTHORIZED` phải là state riêng.** Gộp với CAPTURED thì **mất khả năng**: capture số
tiền khác lúc giữ (khách sạn), void mà không refund (rẻ hơn), biết "đã chắc có tiền nhưng chưa thu".

**③ Phân biệt `VOIDED` / `REFUNDED`.** Xem bảng mục 4 — kế toán, phí, thời điểm cho phép,
đối soát đều khác. Chúng rẽ từ **hai state cha khác nhau** ⇒ không thể gộp.

**④ Hai đường tới `FAILED`**: Bank *trả lời* từ chối, và Bank *không trả lời* (timeout).
Timeout nguy hiểm hơn — trạng thái thật **không xác định** → cần đối soát.

**⑤ Mỗi trigger ghi rõ CHỦ THỂ** (merchant / khách / Bank / hệ thống). Nhìn bảng là biết ngay
transition nào cần **một API**, cái nào cần **job quét**, cái nào do **callback bên ngoài** —
chuyển thẳng từ state machine sang danh sách việc phải code.
