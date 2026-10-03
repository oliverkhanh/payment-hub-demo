# Ghi chú — Khóa ký JWT: các thành phần & cách liên kết (Increment 1)

> Tổng hợp mọi khái niệm quanh "khóa ký" của IdP: keypair, keystore, keytool, kid, JWKS,
> JWKSource, Secrets Manager, KMS, rotation, và scaling nhiều instance. Ẩn dụ xuyên suốt:
> **con dấu** (private) + **ảnh chụp con dấu** (public) + **hộp có khóa** (keystore).

---

## 0. Vấn đề khóa giải quyết

payment-hub làm sao **tin** một token do IdP phát, mà **không dùng chung bí mật** với IdP?
→ Dùng **ký bất đối xứng (RS256)**: IdP ký bằng **private**, payment-hub verify bằng **public**.
Private chỉ IdP giữ → không ai giả token; public ai cũng lấy được → ai cũng verify được.

---

## 1. Các thành phần (định nghĩa + ẩn dụ)

| Thành phần | Là gì | Ẩn dụ |
|---|---|---|
| **Cặp khóa (keypair)** | private + public, sinh cùng lúc, gắn nhau bằng toán | con dấu + ảnh của nó |
| **private key** | bí mật, dùng để **KÝ**; chỉ IdP giữ | **con dấu** |
| **public key** | công khai, dùng để **VERIFY**; suy ra từ private (không ngược lại) | **ảnh chụp con dấu** |
| **RS256** | thuật toán ký bất đối xứng (RSA + SHA-256) | kiểu khắc dấu |
| **keystore** | **file** đựng khóa (của ta: `idp-signing.p12`) | **hộp có khóa** |
| **PKCS12 (.p12)** | định dạng chuẩn của hộp | kiểu hộp |
| **alias** | tên một khóa trong hộp (`idp-signing`) | tên khắc trên dấu |
| **storepass** | mật khẩu mở hộp (`changeit`) | chìa khóa hộp |
| **keytool** | công cụ (đi kèm JDK) sinh khóa + bỏ vào hộp | ông thợ khắc dấu |
| **kid** | nhãn định danh khóa, có ở **header JWT** và **mỗi khóa trong JWKS** | tên ghi dưới mỗi dấu |
| **JWKSource** (bean) | code Spring **load** khóa từ hộp lúc app start | tay lấy dấu ra khỏi hộp |
| **JWKS** (`/oauth2/jwks`) | endpoint IdP **publish public key** | bảng tin dán ảnh dấu |
| **JwtDecoder** (payment-hub) | fetch JWKS + verify chữ ký + exp | người soi dấu |
| **Secrets Manager** | kho cất **giá trị secret** (file `.p12`, storepass) | két gửi đồ |
| **KMS** | kho **ký hộ**, khóa không rời kho | phòng công chứng đóng dấu hộ |

---

## 2. Hai vòng đời tách biệt (điểm hay nhầm nhất)

| | KEY GENERATION (sinh) | KEY LOADING (load) |
|---|---|---|
| Ai | người / CI / KMS (NGOÀI app) | app (`JWKSource`) |
| Bằng gì | `keytool` / KMS API | code Spring lúc start |
| Khi nào | **1 LẦN**, lúc setup, trước khi app chạy | **MỖI lần** app start |
| Ra gì | file `.p12` (hoặc key trong KMS) | đọc key vào RAM để ký/verify |

→ **App KHÔNG sinh key, chỉ LOAD.** Sinh là bước setup thủ công/CI, một lần.

Lệnh sinh (dev, chạy 1 lần):
```bash
keytool -genkeypair -alias idp-signing -keyalg RSA -keysize 2048 \
        -storetype PKCS12 -keystore idp-signing.p12 -storepass changeit ...
```

---

## 3. Luồng liên kết TẤT CẢ thành phần

```
[SETUP — 1 lần]  keytool ─sinh→ (keypair: private+public) ─cất vào→ idp-signing.p12 (alias=idp-signing, pass=changeit)
                                                                      │
[PHÂN PHỐI]  file .p12  ──bake image / mount / Secrets Manager──►  cho MỌI instance IdP
                                                                      │
[APP START]  JWKSource mở hộp (storepass) → lấy khóa alias "idp-signing"
                 ├── private → để KÝ token
                 └── public  → publish ra /oauth2/jwks (kèm kid="idp-signing")
                                                                      │
[KÝ]  IdP ký JWT bằng private → header JWT ghi kid="idp-signing"
                                                                      │
[PUBLISH]  GET /oauth2/jwks  →  { keys:[ { kid:"idp-signing", n, e ... } ] }  (chỉ PUBLIC)
                                                                      │
[VERIFY]  payment-hub JwtDecoder ─fetch JWKS (1 lần, cache)→ đọc kid trong token
              → chọn đúng public key theo kid → verify chữ ký RS256 + exp  (CỤC BỘ)
```

Mắt xích khóa với nhau qua **`kid`**: token nói "tôi ký bằng dấu tên X" → payment-hub tìm
đúng "ảnh dấu X" trong JWKS để đối chiếu.

---

## 4. Quy tắc nhiều instance (scaling)

**Sinh 1 lần → chia sẻ → mọi instance load CHUNG một khóa.**

- Nơi cất khóa chung: **cùng 1 keystore file** (dev) hoặc **KMS/Secrets Manager** (prod). KHÔNG để DB.
- Vì sao chung:
  - Mỗi instance tự sinh khóa → token instance A ký, instance B (hoặc JWKS của B) không verify được → **cross-instance fail**.
  - Sinh mới mỗi restart → token cũ chết sau restart (**bug D4**).
- payment-hub verify **stateless** → mỗi instance tự cache JWTS riêng, **scale ngang vô tư** (public key read-only).

---

## 5. Nơi cất khóa: 3 mức

| Mức | Cách | Khóa có rời kho? | Dùng khi |
|---|---|---|---|
| **File local** | `.p12` trong image/mount | có (trong file) | dev |
| **Secrets Manager** | cất `.p12` (base64/SecretBinary) + storepass riêng; app tải về rồi tự ký | có (app cầm key) | prod phổ biến |
| **KMS** | khóa nằm trong KMS; app gửi data cho KMS **ký hộ** | **KHÔNG** | prod bảo mật cao |

Secrets Manager: file `.p12` chỉ vài KB, giới hạn 64KB → thoải mái. Nhớ cất **storepass** ở secret riêng.
KMS: an toàn nhất (key không lộ) nhưng mỗi lần ký là round-trip tới KMS.

---

## 6. Rotation (thay khóa không gãy token cũ) — nhờ `kid`

```
1. Sinh khóa mới (kid="idp-signing-v2"), GIỮ khóa cũ.
2. JWKS publish CẢ HAI: [ kid=idp-signing, kid=idp-signing-v2 ].
3. IdP bắt đầu ký bằng v2.
4. Token cũ (kid=v1) vẫn verify được (v1 còn trong JWKS).
5. Sau khi mọi token v1 hết hạn → gỡ v1 khỏi JWKS.
```
→ `kid` cho phép chạy song song 2 khóa trong lúc chuyển giao. payment-hub gặp kid lạ → tự
refetch JWKS để lấy khóa mới.

---

## 7. Hai bug mà chủ đề này phòng tránh

| Bug | Nguyên nhân | Fix |
|---|---|---|
| **D4** — restart là token cũ chết | app tự sinh key mới mỗi lần start | sinh 1 lần, persist keystore/KMS |
| **Scaling Q2** — cross-instance verify fail | mỗi instance khóa riêng | chia sẻ CHUNG một khóa; KHÔNG dùng sticky session |

---

## 8. Chốt một câu

> **Khắc một con dấu (keytool/KMS, 1 lần) → cất một chỗ chung (keystore/Secrets Manager/KMS) →
> mọi instance load chung con dấu đó → IdP ký bằng private, dán ảnh (public) lên JWKS, gắn kid →
> payment-hub soi ảnh theo kid để verify cục bộ.**
