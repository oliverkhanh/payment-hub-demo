# Ghi chú — Cơ chế hoạt động của Spring Authorization Server (Increment 1)

> Tổng kết chi tiết cách `spring-security-oauth2-authorization-server` (phía IdP) cấp JWT
> qua grant `client_credentials`, và phía `spring-security-oauth2-resource-server` (payment-hub)
> validate JWT. Dùng để tra cứu lại. Bối cảnh: realm "machine", service gọi service.

## 0. Ẩn dụ nền: IdP = "nhà máy cấp vé" 2 cửa

```
        ┌────────────── AUTHORIZATION SERVER (identity-provider :9000) ──────────────┐
BẠN ───► │  CỬA 1: "Anh LÀ ai?"           ─────►   CỬA 2: "Anh XIN vé gì?"           │ ───► VÉ = JWT
(curl/   │  OAuth2ClientAuthenticationFilter       OAuth2TokenEndpointFilter          │
 Postman)│  - đọc Basic, tách id:secret            - đọc grant_type + scope           │
         │  - validate secret (BCrypt)             - kiểm quyền, đúc + ký JWT (RS256) │
         └────────────────────────────────────────────────────────────────────────────┘
            sai cách/secret → 401 invalid_client     xin quyền/luồng sai → invalid_scope / unauthorized_client
                                                                                  │
                                        JWT (Bearer) ─────────────────────────────┘
                                                                                  ▼
        ┌────────────── RESOURCE SERVER (payment-hub :8080) ─────────────────────────┐
        │  BearerTokenAuthenticationFilter → JwtDecoder (fetch JWKS, cache, verify   │
        │  chữ ký theo kid) → map scope → SCOPE_xxx → kiểm quyền endpoint            │
        │  thiếu/sai token → 401 · token ok nhưng thiếu scope → 403                  │
        └────────────────────────────────────────────────────────────────────────────┘
```

- **Cửa 1 = "vào bằng khóa gì"** → xét `client_authentication_methods`.
- **Cửa 2 = "xin loại vé gì + vé cho làm gì"** → xét `authorization_grant_types` + `scopes`.
- **Đóng dấu = ký RS256** bằng private key (keystore); con dấu công khai treo ở `/oauth2/jwks`.

---

## 1. Hai thư viện = hai nửa của một giao thức

| Thư viện | Đặt ở | Vai trò | Endpoint tự sinh |
|---|---|---|---|
| `oauth2-authorization-server` | identity-provider | **PHÁT** token (mint + ký) | `/oauth2/token`, `/oauth2/jwks`, `/oauth2/authorize`, `/oauth2/introspect`, `/oauth2/revoke`, `/.well-known/...` |
| `oauth2-resource-server` | payment-hub | **VALIDATE** token (verify chữ ký + scope) | không có; chỉ chặn request bằng filter |

Điểm cốt lõi: **ta viết rất ít** — chủ yếu "cắm" cấu hình; toàn bộ filter/converter/provider/encoder do thư viện tự đăng ký khi gọi `applyDefaultSecurity(http)`.

---

## 2. Năm mảnh ta tự cấu hình (identity-provider) và thư viện dùng ở đâu

| Bean ta khai (AuthorizationServerConfig) | Thư viện dùng ở |
|---|---|
| `RegisteredClientRepository` = `JdbcRegisteredClientRepository` | Cửa 1 — tra client trong DB |
| `PasswordEncoder` = BCrypt | Cửa 1 — so khớp secret (hash, không plaintext) |
| `JWKSource` (load RSA từ keystore PKCS12) | Cửa 2 — lấy private key ký JWT; và `/oauth2/jwks` publish public key |
| `AuthorizationServerSettings` | định nghĩa path các endpoint (`/oauth2/token`…) |
| `ApplicationRunner seedClients` | tạo dữ liệu client để Cửa 1 tìm thấy |

Client đã seed:
- `hub-webhook` / `hub-webhook-secret` / scope `payments:read`
- `hub-ledger`  / `hub-ledger-secret`  / scope `ledger:write`
- Cả hai: grant `client_credentials`, method `client_secret_basic`, access-token TTL 5 phút.

---

## 3. Kiến trúc filter chain — 2 chain, tách phạm vi

`applyDefaultSecurity(http)` bên trong đặt `http.securityMatcher(endpointsMatcher)` với
`endpointsMatcher` = OR của **mọi** endpoint auth-server. Vì vậy:

```
Chain #1 (@Order HIGHEST_PRECEDENCE): securityMatcher = /oauth2/**   → chỉ nuốt request auth-server
Chain #2 (mặc định của ta):           /actuator/** permitAll, còn lại authenticated
```

- Request `/oauth2/token` → khớp chain #1.
- Request `/actuator/health`, `/v1/payments` → KHÔNG khớp chain #1 → rớt xuống chain #2.

**Hai tầng lọc:** ngoài `securityMatcher` của cả chain, mỗi filter trong chain còn có `RequestMatcher`
riêng (dòng đầu filter: `if (!matcher.matches(req)) { chain.doFilter(); return; }`):
- `OAuth2TokenEndpointFilter` → chỉ `POST /oauth2/token`.
- `OAuth2ClientAuthenticationFilter` → các endpoint cần xác thực client (token, introspect, revoke…).

Muốn đổi path token: `AuthorizationServerSettings.builder().tokenEndpoint("/oauth2/v2/token")` → matcher tự đổi.

---

## 4. CỬA 1 — Xác thực client (PHA 1)

**Filter:** `OAuth2ClientAuthenticationFilter` (rút gọn)

```java
if (!requestMatcher.matches(request)) { chain.doFilter(request, response); return; }
Authentication authRequest = authenticationConverter.convert(request); // parse → token "thô"
if (authRequest != null) {
    Authentication result = authenticationManager.authenticate(authRequest); // validate thật
    SecurityContextHolder.getContext().setAuthentication(result);   // cất cho PHA 2 dùng (ThreadLocal)
}
chain.doFilter(request, response);
// OAuth2AuthenticationException → failureHandler → 401
```

**Converter (parse header):** `DelegatingAuthenticationConverter` thử lần lượt
`[JwtClientAssertion, ClientSecretBasic, ClientSecretPost, PublicClient]`.
Với header `Basic`, khớp `ClientSecretBasicAuthenticationConverter`:

```java
byte[] decoded = Base64.getDecoder().decode(part_after_"Basic ");
String[] cred = new String(decoded, UTF_8).split(":", 2);
String clientId = urlDecode(cred[0]);   // hub-webhook
String secret   = urlDecode(cred[1]);   // hub-webhook-secret
return new OAuth2ClientAuthenticationToken(
        clientId, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, secret, ...); // ← DÁN NHÃN cách đến
```

**Provider (validate):** `ClientSecretAuthenticationProvider`:

```java
RegisteredClient rc = registeredClientRepository.findByClientId(clientId);   // JDBC SELECT
if (rc == null) throw INVALID_CLIENT;                                         // → 401

// (1) cách đến có được phép? — đối chiếu cột client_authentication_methods
if (!rc.getClientAuthenticationMethods().contains(token.getClientAuthenticationMethod()))
    throw INVALID_CLIENT;                                                     // → 401

// (2) secret đúng? — băm lại rồi so hash
if (!passwordEncoder.matches(secret, rc.getClientSecret()))
    throw INVALID_CLIENT;                                                     // → 401
```

→ **401 invalid_client có 2 nguyên nhân khác nhau ở Cửa 1:** sai *cách* (nhãn không nằm trong danh sách) hoặc sai *secret*.

---

## 5. CỬA 2 — Grant + đúc JWT (PHA 2)

**Filter:** `OAuth2TokenEndpointFilter` (chỉ `POST /oauth2/token`):

```java
Authentication grantAuth = authenticationConverter.convert(request);  // đọc grant_type/scope từ body
OAuth2AccessTokenAuthenticationToken result =
    (OAuth2AccessTokenAuthenticationToken) authenticationManager.authenticate(grantAuth);
sendAccessTokenResponse(response, result);   // JSON { access_token, token_type, expires_in, scope }
```

**Converter (theo grant_type):** `OAuth2ClientCredentialsAuthenticationConverter` đọc
`grant_type=client_credentials`, `scope=payments:read`, gắn client (đã auth ở PHA 1 lấy từ SecurityContext).

**Provider:** `OAuth2ClientCredentialsAuthenticationProvider`:

```java
RegisteredClient rc = clientPrincipal.getRegisteredClient();
if (!rc.getAuthorizationGrantTypes().contains(CLIENT_CREDENTIALS))  // đối chiếu cột authorization_grant_types
    throw UNAUTHORIZED_CLIENT;
Set<String> scopes = subsetOrThrow(requested, rc.getScopes());      // scope xin ⊆ scope đăng ký, else invalid_scope
OAuth2Token jwt = tokenGenerator.generate(context(clientPrincipal, scopes));
return new OAuth2AccessTokenAuthenticationToken(rc, clientPrincipal, jwt, ...);
```

**Pipeline sinh token:** `JwtGenerator` dựng claims → `NimbusJwtEncoder` hỏi `JWKSource`
lấy private key → ký RS256, gắn header `kid=idp-signing`.

```
JwtGenerator (claims) → JwtEncoder (ký) → JWKSource (nguồn khóa)
```

Claims điển hình: `iss`, `sub=hub-webhook`, `aud`, `iat`, `exp=iat+300s`, `scope=[payments:read]`.

---

## 6. Lưu trữ client — JDBC, KHÔNG phải ORM

`JdbcRegisteredClientRepository` **không** dùng JPA/Hibernate/`@Entity`. Nó là **JDBC trần**:

- Tên bảng + cột là **hằng số chuỗi** trong class; SQL viết cứng:
  ```java
  private static final String TABLE_NAME = "oauth2_registered_client";
  private static final String COLUMN_NAMES = "id, client_id, client_secret, "
      + "client_authentication_methods, authorization_grant_types, scopes, "
      + "client_settings, token_settings, ...";
  ```
- Map tay 2 chiều:
  - **đọc:** `RegisteredClientRowMapper` (`rs.getString("client_id")` → `RegisteredClient.builder()`).
  - **ghi:** `RegisteredClientParametersMapper` (field → `PreparedStatement`).
- Hệ quả: **tên bảng/cột phải khớp CHÍNH XÁC**; đổi tên là hỏng (khác hẳn JPA sinh SQL từ metadata).
- DDL do Flyway chạy (`V2__oauth2_registered_client.sql`, sao từ schema chuẩn của thư viện).
- Cột JSON `client_settings`/`token_settings` → vì thế ta seed client bằng `repo.save()` (code)
  thay vì viết SQL INSERT tay (dễ sai JSON).

---

## 7. Ba cột hay bị lẫn

| Cột | Trả lời câu hỏi | Kiểm ở | Ví dụ |
|---|---|---|---|
| `client_authentication_methods` | Chứng minh danh tính **BẰNG CÁCH NÀO** | Cửa 1 | `client_secret_basic`, `client_secret_post`, `private_key_jwt`, `none` |
| `authorization_grant_types` | Muốn chạy **LUỒNG cấp token NÀO** | Cửa 2 | `client_credentials`, `authorization_code`, `refresh_token` |
| `scopes` | Token được cấp **QUYỀN GÌ** | Cửa 2 (và enforce ở resource server) | `payments:read`, `ledger:write` |

Ba câu: **"vào bằng khóa gì" → "xin vé loại gì" → "vé cho phép làm gì".**

Chi tiết `client_authentication_methods`:
- `client_secret_basic`: secret ở **header** `Authorization: Basic base64(id:secret)`. ← ta dùng.
- `client_secret_post`: secret trong **body** form (`client_id`, `client_secret`).
- `private_key_jwt`: client **ký một JWT** bằng private key riêng, gửi `client_assertion` — secret không rời client (an toàn hơn).
- `none`: public client (SPA/mobile), không có secret, bắt buộc **PKCE**.

Nhãn method do converter ở Cửa 1 gán theo *cách* request gửi lên; provider so nhãn đó với cột này.
→ Gửi Basic nhưng client chỉ khai `private_key_jwt` ⇒ **401**, dù secret đúng.

---

## 8. Bản đồ lỗi 401 vs 403 (sinh ở đâu)

| Mã | Nơi sinh | Nguyên nhân |
|---|---|---|
| **401 invalid_client** | IdP, Cửa 1 | thiếu/sai secret, hoặc dùng *cách* auth không được khai |
| **invalid_scope** | IdP, Cửa 2 | xin scope client không có |
| **unauthorized_client** | IdP, Cửa 2 | xin grant client không được phép |
| **401 (Bearer)** | payment-hub | thiếu token / token sai định dạng / chữ ký sai / hết hạn |
| **403** | payment-hub | token hợp lệ nhưng **thiếu `SCOPE_payments:read`** |

Ghi nhớ: **403 KHÔNG bao giờ sinh ở IdP** — nó là chuyện của resource server sau khi token đã hợp lệ.

---

## 9. Kỹ thuật thiết kế đáng học (patterns)

| Pattern | Ở đâu | Lợi ích |
|---|---|---|
| Chain of Responsibility | Servlet Filter chain | mỗi filter tự quyết xử/chuyền tiếp |
| Strategy + Delegating | `DelegatingAuthenticationConverter` (nhiều cách auth) | thêm cách auth mới không sửa filter |
| Tách Converter ↔ Provider | parse (đọc request) tách khỏi validate (nghiệp vụ) | Single Responsibility, dễ test |
| ThreadLocal state | `SecurityContextHolder` | truyền client đã auth từ PHA 1 → PHA 2 |
| Pipeline khóa | `TokenGenerator → JwtEncoder → JWKSource` | đổi thuật toán/khóa không đụng logic |
| RequestMatcher | mỗi filter + `securityMatcher` cả chain | giới hạn phạm vi rõ ràng |

---

## 10. Ví dụ end-to-end (giá trị thật)

```
POST http://localhost:9000/oauth2/token
Authorization: Basic base64("hub-webhook:hub-webhook-secret")
Body: grant_type=client_credentials&scope=payments:read

CỬA 1: decode Basic → hub-webhook / hub-webhook-secret
       findByClientId → có ; method client_secret_basic hợp lệ ; bcrypt.matches ✅
CỬA 2: grant client_credentials ⊆ grant client ✅ ; scope payments:read ⊆ [payments:read] ✅
       JwtGenerator → NimbusJwtEncoder ký RS256 (kid=idp-signing)

← 200 { access_token: "eyJraWQ...", token_type: "Bearer", expires_in: 299, scope: "payments:read" }
  header : { alg: RS256, kid: idp-signing }
  payload: { sub: hub-webhook, scope: ["payments:read"], iss, iat, exp: iat+300s }

GET http://localhost:8080/v1/payments/pay_123
Authorization: Bearer eyJraWQ...
→ payment-hub: BearerTokenAuthenticationFilter → JwtDecoder verify chữ ký (JWKS, kid) → SCOPE_payments:read ✅ → 200
```

Các biến thể để test:
- token `hub-ledger` (chỉ `ledger:write`) → gọi payment-hub → **403**.
- không Bearer → **401**. Bearer rác `abc.def.ghi` → **401**.

---

## 11. Phía payment-hub (resource server) — đối xứng, tóm tắt

- Cấu hình: `jwk-set-uri = http://identity-provider:8080/oauth2/jwks`; rule `/v1/payments/** → SCOPE_payments:read`; stateless; CSRF off; `/actuator/** permitAll`.
- Thư viện lo: `BearerTokenAuthenticationFilter` đọc `Authorization: Bearer` →
  `JwtDecoder` (NimbusJwtDecoder) **fetch JWKS một lần, cache**, chọn key theo `kid`, **verify chữ ký RS256 + exp cục bộ** (không gọi ngược IdP mỗi request) →
  map claim `scope` → authorities `SCOPE_xxx` → so với rule → 401/403.
- Hệ quả kiến trúc: tắt IdP sau khi payment-hub đã cache JWKS ⇒ vẫn validate được token còn hạn (đã verify thực nghiệm ở Increment 1, bước B5).

> Chi tiết sâu phía resource server (làm mới JWKS, rotation, `kid` không khớp) để ghi bổ sung khi cần.
