# Tổng hợp kiến thức — Increment 1: IdP realm "machine" (client_credentials → JWT)

> Tổng kết cả Increment 1: mục tiêu, kiến trúc, khái niệm, quyết định thiết kế, cấu hình,
> cách test, DoD. Phần cơ chế nội bộ sâu của thư viện: xem [oauth2-authorization-server-internals.md](oauth2-authorization-server-internals.md).

---

## 1. Mục tiêu & vị trí trong hệ

**Mục tiêu:** dựng **1 trong 3 trust boundary** — *service nội bộ Hub gọi nhau* — bằng
OAuth2 `client_credentials`: service lấy `clientId/clientSecret` đổi ra **JWT**, service
kia **validate JWT** (chữ ký + scope) mà không cần hỏi ngược IdP mỗi request.

Ba trust boundary của dự án (Increment 1 chỉ làm cái số 3):
1. Merchant server → Hub: **API key + HMAC** (Hub cấp) — *chưa làm*.
2. Hub ↔ Bank (cross-org): **mTLS / HMAC do Bank cấp** — *chưa làm*.
3. **Service nội bộ Hub ↔ nhau: OAuth2 client_credentials → JWT (IdP realm machine)** — ✅ *Increment 1*.

> IdP **chỉ** dùng cho boundary nội bộ. Fake-bank là hệ ngoài → KHÔNG dùng IdP.

**Realm "machine":** client là *service*, không phải người. Không có username/password,
không có màn hình login. Client tự xưng danh bằng chính nó (client_credentials).

---

## 2. Kiến trúc: 2 vai, 2 thư viện

```
identity-provider :9000                          payment-hub :8080
(Authorization Server)                           (Resource Server)
spring-boot-starter-                             spring-boot-starter-
  oauth2-authorization-server                      oauth2-resource-server
─ PHÁT + ký JWT                    JWT (Bearer)   ─ VALIDATE JWT
─ /oauth2/token, /oauth2/jwks   ───────────────►  ─ verify chữ ký (JWKS) + scope
```

- **Authorization Server** = "nhà máy cấp vé" 2 cửa: Cửa 1 xác thực client, Cửa 2 cấp token.
- **Resource Server** = "soát vé": đọc Bearer, verify chữ ký bằng public key (JWKS), enforce scope.
- Ta viết **rất ít** — chủ yếu cắm cấu hình; filter/converter/provider/encoder do thư viện lo.

---

## 3. Luồng client_credentials end-to-end (giá trị thật)

```
① BẠN → IdP: xin token
   POST http://localhost:9000/oauth2/token
   Authorization: Basic base64("hub-webhook:hub-webhook-secret")
   Body: grant_type=client_credentials&scope=payments:read

   Cửa 1 (xác thực client):
     - decode Basic → clientId=hub-webhook, secret=hub-webhook-secret
     - findByClientId (JDBC) → có
     - method client_secret_basic ∈ client_authentication_methods? ✅
     - bcrypt.matches(secret, hash) ✅
   Cửa 2 (grant + đúc token):
     - client_credentials ∈ authorization_grant_types? ✅
     - scope payments:read ⊆ scopes đăng ký? ✅
     - JwtGenerator → NimbusJwtEncoder ký RS256 (kid=idp-signing)

   ← 200 { access_token, token_type: Bearer, expires_in: 299, scope: "payments:read" }
     header : { alg: RS256, kid: idp-signing }
     payload: { sub: hub-webhook, scope: ["payments:read"], iss, iat, exp: iat+300s }

② BẠN → payment-hub: dùng token
   GET http://localhost:8080/v1/payments/pay_123
   Authorization: Bearer <access_token>

   - BearerTokenAuthenticationFilter đọc Bearer
   - JwtDecoder: fetch JWKS (1 lần, cache) → chọn key theo kid → verify chữ ký + exp CỤC BỘ
   - map claim scope → authority SCOPE_payments:read
   - endpoint cần SCOPE_payments:read? khớp ✅
   ← 200 { id, amount: 150000, currency: VND, status: AUTHORIZED }  (stub)
```

---

## 4. Khái niệm chốt

### 4.1 client_credentials — dùng khi nào
- Service gọi service (M2M), **không thay mặt user nào**. Token mang danh tính *service*.
- Use case đã chốt cho IdP admin sau này: *get list users*, *tạo/sửa/xóa user* (machine gọi IdP).
- KHÁC `authorization_code` (Increment 7): luồng đó có user thật login qua trình duyệt.

### 4.2 JWT tự xác thực (self-contained)
- 3 phần `header.payload.signature`, ký **RS256** (bất đối xứng: private ký, public verify).
- **kid** trong header = "dùng chìa khóa nào để verify" → phục vụ **key rotation**.
- **JWKS** (`/oauth2/jwks`) = nơi IdP công bố **public key**. Resource server fetch về, cache.
- Hệ quả lớn: payment-hub verify **cục bộ**, không gọi IdP mỗi request → nhanh, chịu lỗi tốt
  (tắt IdP, token còn hạn vẫn dùng được — đã thử ở bước B5).

### 4.3 Ba cột định danh client (dễ lẫn)
| Cột | Câu hỏi | Kiểm ở | `hub-webhook` |
|---|---|---|---|
| `client_authentication_methods` | vào **bằng khóa gì** | Cửa 1 | `client_secret_basic` |
| `authorization_grant_types` | xin **vé loại gì** | Cửa 2 | `client_credentials` |
| `scopes` | vé **cho làm gì** | Cửa 2 (trần) + resource server (enforce) | `payments:read` |

`scopes` đặc biệt: **kiểm 2 nơi** — IdP giới hạn trần lúc phát; payment-hub enforce đủ/thiếu lúc dùng.

### 4.4 401 vs 403 — sinh ở đâu
| Mã | Nơi | Vì sao |
|---|---|---|
| `401 invalid_client` | IdP Cửa 1 | sai secret **hoặc** sai *cách* auth |
| `unauthorized_client` | IdP Cửa 2 | xin grant không được phép |
| `invalid_scope` | IdP Cửa 2 | xin scope quá trần |
| `401` (Bearer) | payment-hub | thiếu / sai / hết hạn token |
| **`403`** | payment-hub | token hợp lệ nhưng **thiếu scope** |

→ **403 KHÔNG bao giờ sinh ở IdP.** (`hub-ledger` chỉ có `ledger:write` là "quân xanh" để test 403.)

---

## 5. Quyết định thiết kế & trade-off

| Quyết định | Vì sao | Trade-off / ghi chú |
|---|---|---|
| **Client registry lưu JDBC** (`JdbcRegisteredClientRepository`) | chuẩn thư viện, persist qua restart, thật như production | JDBC trần (không ORM), tên bảng/cột cố định `oauth2_registered_client` |
| **Seed client bằng code** (`repo.save`) chứ không INSERT SQL | cột `client_settings`/`token_settings` là JSON, viết SQL tay dễ sai | *lệch nhẹ so với "mọi thứ qua Flyway"*: DDL do Flyway, DATA do code (idempotent, seed-if-absent) |
| **Ký bằng keystore PKCS12** (RSA 2048), không sinh key runtime | JWKS **ổn định qua restart** (kid không đổi) → token cũ vẫn verify được | keystore là secret dev, gitignored; production → KMS/Secrets Manager |
| **`jwk-set-uri`, KHÔNG `issuer-uri`** ở resource server | tránh lệch hostname internal (`identity-provider:8080`) vs external (`localhost:9000`) | mất auto-discovery `/.well-known`; đổi lại ổn định trong docker network |
| **Access token TTL ngắn (5 phút)** | rủi ro lộ token thấp; hợp M2M (xin lại rẻ) | client phải xin token thường xuyên hơn |
| **Validate cục bộ (JWKS cache)** thay vì introspection | nhanh, ít phụ thuộc IdP lúc runtime | revoke tức thời khó hơn (bù bằng TTL ngắn) |
| **In-memory `OAuth2AuthorizationService`** (chỉ registry client ở JDBC) | client_credentials không cần lưu authorization/consent | nếu cần audit token cấp phát → sau này chuyển JDBC |

---

## 6. Cơ chế nội bộ (tóm tắt — chi tiết ở file internals)

```
request ─► [Filter] (Chain of Responsibility: RequestMatcher, của tôi thì xử)
              │ dùng
              ▼
          [Converter] (Strategy + Delegating: thử Basic/Post/JWT/None → parse, chưa xét)
              │ ra Authentication(authenticated=false)
              ▼
          [ProviderManager] (chọn provider qua supports(token.getClass()))
              ▼
          [Provider] (xét: tra DB, so BCrypt, kiểm grant/scope → authenticated=true)
```
- **2 SecurityFilterChain:** #1 `@Order(HIGHEST)` `securityMatcher(/oauth2/**)`; #2 actuator permitAll + còn lại authenticated.
- **Converter tách Provider:** Converter *đọc* (không đụng DB), Provider *xét* (không đụng HttpServletRequest) → ghép chéo, test dễ.
- 5 bean ta cắm: `RegisteredClientRepository`(JDBC), `PasswordEncoder`(BCrypt), `JWKSource`(keystore), `AuthorizationServerSettings`, `seedClients`.

---

## 7. Cấu hình cụ thể (file & env)

**identity-provider:**
- `config/AuthorizationServerConfig.java` — 2 filter chain, JDBC repo, BCrypt, JWKSource từ keystore, seed 2 client.
- `resources/db/migration/V2__oauth2_registered_client.sql` — DDL bảng chuẩn.
- `resources/keys/idp-signing.p12` — keystore RSA 2048 (gitignored, sinh bằng keytool).
- `application.yml` — `idp.jwk.keystore-location/password/key-alias`.
- env: `IDP_KEYSTORE_PASSWORD`.

**payment-hub:**
- `config/ResourceServerConfig.java` — `/actuator/** permitAll`; `GET /v1/payments/** → SCOPE_payments:read`; `.oauth2ResourceServer(jwt)`; STATELESS; CSRF off.
- `api/PaymentDemoController.java` — `GET /v1/payments/{id}` trả stub.
- `application.yml` — `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`.
- env: `IDP_JWK_SET_URI = http://identity-provider:8080/oauth2/jwks` (docker network, cổng nội bộ 8080).

**Client đã seed:**
| clientId | secret (dev) | scope | grant | method |
|---|---|---|---|---|
| `hub-webhook` | `hub-webhook-secret` | `payments:read` | client_credentials | client_secret_basic |
| `hub-ledger` | `hub-ledger-secret` | `ledger:write` | client_credentials | client_secret_basic |

---

## 8. Cách test

**curl (2 bước gộp):**
```bash
TOKEN=$(curl -s -u hub-webhook:hub-webhook-secret \
  -d 'grant_type=client_credentials&scope=payments:read' \
  http://localhost:9000/oauth2/token | jq -r .access_token)
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" http://localhost:8080/v1/payments/pay_123
```

**Postman:** Request 1 (POST token, Basic auth + body form) → copy access_token → Request 2 (GET, Bearer).
Hoặc dùng Authorization type **OAuth 2.0 / Client Credentials** để Postman tự lấy token.

**4 kịch bản DoD:**
| # | Làm | Kỳ vọng |
|---|---|---|
| Happy | token `hub-webhook` (payments:read) → GET | **200** |
| 403 | token `hub-ledger` (ledger:write) → GET | **403** |
| 401 no token | GET không Bearer | **401** |
| 401 bad token | Bearer `abc.def.ghi` | **401** |
| B5 (chịu lỗi) | tắt IdP sau khi cache JWKS, token còn hạn → GET | **200** |

---

## 9. Definition of Done

- [x] IdP cấp JWT qua client_credentials (kid, sub, scope, TTL 300s, RS256).
- [x] payment-hub validate JWT cục bộ (JWKS cache), enforce scope.
- [x] 200 / 403 / 401 đúng như bảng; B5 chịu lỗi khi IdP tắt.
- [ ] **Automated test (Testcontainers)** — CÒN THIẾU → Increment 1 đang `[~]` trong CLAUDE.md.

---

## 10. Bảo mật & production notes

- Secret client (`hub-webhook-secret`…) và `IDP_KEYSTORE_PASSWORD=changeit`: **DEV-ONLY**, gitignored.
  Production → **AWS Secrets Manager**; keystore/khóa ký → **KMS**, có **key rotation** (kid giúp việc này).
- Secret lưu **hash BCrypt** trong DB, không plaintext.
- Access token TTL ngắn + validate cục bộ; revoke tức thời (nếu cần) là hạn chế đã biết.
- Chưa có: rate-limit token endpoint, audit token phát hành, xoay khóa tự động — để tăng cường sau.
