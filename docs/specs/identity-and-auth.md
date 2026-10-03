# Tham chiếu — Identity & Auth (use-case sử dụng)

> Ghi chú các quyết định & use-case auth đã chốt, để lúc code (Inc 1, 3–7) tra nhanh.
> Nguyên tắc auth tổng: xem CLAUDE.md Mục 3; ánh xạ increment: docs/06-roadmap.md Mục 2.

---

## 1. IdP = Platform IAM, 2 realm (tách khoang)

| Realm | Đối tượng | Grant | Token |
|---|---|---|---|
| **machine** | Service nội bộ Hub | `client_credentials` | `sub`=service, `scope`, `aud`=hub-services, TTL ngắn |
| **merchant** | Con người của merchant | `authorization_code` + PKCE (OIDC) + **MFA (TOTP)** | mang `merchantId` (tenant) + `role` |

## 2. Các biên tin cậy (dùng cơ chế gì)

| Biên | Cơ chế | IdP? |
|---|---|---|
| Merchant *server* → Hub API | **API key + HMAC** (Hub cấp) | ❌ |
| Hub → Bank | **mTLS** hoặc API key/HMAC (Bank cấp) | ❌ |
| Bank → Hub (callback) | callback **ký HMAC** | ❌ |
| Service nội bộ Hub ↔ nhau | **OAuth2 client_credentials → JWT** | ✅ realm machine |
| Con người merchant → Portal | **OIDC authorization_code + PKCE + MFA** | ✅ realm merchant |

---

## 3. ⭐ clientId / clientSecret — DÙNG KHI NÀO

**Mô hình 1 câu:** clientId/secret là *tài khoản/mật khẩu của một ỨNG DỤNG* (không phải người).
Dùng khi một app (giữ được bí mật) cần **tự chứng minh danh tính với authorization server**.

**2 ngữ cảnh:**
1. **App tự lấy token cho chính nó** — grant `client_credentials` (M2M).
2. **App confidential hoàn tất login user** — bước đổi *authorization code* lấy token.

**Use-case thực tế:**
- M2M / service-to-service / job chạy nền (không có user).
- "Login with Google/GitHub" (web app confidential đổi code).
- Tích hợp API bên thứ ba/đối tác (đăng ký app → được id/secret).
- Service principal trên cloud (Azure AD app), CI/CD, thiết bị giữ được secret.

**KHÔNG dùng clientId/secret khi:**
| Tình huống | Thay bằng |
|---|---|
| SPA / mobile / CLI (public client) | **PKCE** (không secret) |
| Truy cập API đơn giản | **API key** (1 chuỗi) — vd Merchant→Hub của ta |
| M2M bảo mật cao (tài chính) | **mTLS** hoặc **private_key_jwt** |

**Cơ chế 2 bước (đừng nhầm):**
```
B1 LẤY token:  Service ──(Basic auth clientId:secret)──► IdP /oauth2/token  → JWT
B2 GỌI nhau:   Service ──(Authorization: BEARER <JWT>)──► Service khác
```
→ clientId/secret chỉ dùng ở B1. Lời gọi (B2) mang JWT Bearer, secret KHÔNG đi tới service đích.

---

## 4. Token (client_credentials) để LÀM GÌ

Ví von: clientId/secret = CMND chìa ở lễ tân để **lấy thẻ**; token = **thẻ ra vào** quẹt ở
từng cửa (service đích), có ghi *được vào phòng nào* (scope) và *hết hạn khi nào* (TTL).

Service đích dùng token để:
1. **Xác thực** — verify chữ ký qua **JWKS** (không hỏi lại IdP) + kiểm `exp`.
2. **Phân quyền** — đọc **`scope`** (vd `ledger:write`) → cho phép / 403.
3. **Biết ai gọi** — đọc **`sub`** (= clientId) → audit.

Đặc điểm: đại diện **SERVICE** (không phải user), **stateless**, **ngắn hạn**. Token ≠ secret
(lộ token thiệt hại giới hạn vài phút; lộ secret mới nghiêm trọng).

---

## 5. ⭐ Token propagation: dùng token SERVICE hay token USER?

**Tùy bản chất lời gọi:**

| Loại lời gọi | Token dùng |
|---|---|
| **User khởi phát** (user bấm ở Portal → Hub services) | **Token của USER** (mang danh tính + tenant + role) |
| **Hệ thống khởi phát** (job, worker, consumer Kafka) | **Token của SERVICE** (client_credentials) |

**Nguyên tắc vàng:** *Quyết định phân quyền (authz) PHẢI dựa trên token verify được — KHÔNG
tin field user nhét trong payload* (bịa được → thủng).

**Khi cần danh tính user ở service đích — 3 cách đúng:**
| Cách | Làm gì | Dùng khi |
|---|---|---|
| **A. Forward user token** | Chuyển tiếp JWT của user xuống; service đích verify + trích user/tenant/role | Đơn giản; chuỗi do user khởi phát |
| **B. Token Exchange (OBO, RFC 8693)** | Đổi user-token lấy token mang cả actor(service)+subject(user) | Chuẩn enterprise, đi sâu nhiều tầng |
| **C. Context trong event (chỉ AUDIT)** | Đưa `initiatedBy: userX` vào payload event Kafka — chỉ để ghi log, KHÔNG gate quyền | Luồng async |

**Phân biệt cốt lõi:** **authz** (phải từ token verify được) ≠ **audit/context** (được truyền
kèm nếu producer tin cậy). User trong payload OK cho audit, KHÔNG OK cho phân quyền.

**Áp vào project:**
- Portal → `hub-payment`/`hub-refund` (user bấm): **forward user JWT** (Cách A) → enforce RBAC + tenant.
- Worker/job/consumer: **service token** (client_credentials); nếu cần "ai bấm" để ghi sổ → lấy từ **event payload** (Cách C).
- Nâng cấp chuẩn: **Token Exchange** (Spring Authorization Server hỗ trợ grant `token-exchange`).
