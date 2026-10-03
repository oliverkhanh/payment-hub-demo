# Increment 1 — Học từ gốc: Authentication → Manager → Provider → JWT

> Tài liệu **dạy học** (đọc để hiểu), xây từ ví dụ đơn giản nhất rồi tiến dần tới OAuth2
> đúng như code trong project. Đọc kèm:
> - Cơ chế nội bộ thư viện: [oauth2-authorization-server-internals.md](oauth2-authorization-server-internals.md)
> - Tổng hợp cấu hình/DoD: [increment-1-idp-machine.md](increment-1-idp-machine.md)
>
> Code minh hoạ được **rút gọn** cho dễ đọc (không phải bản gốc thư viện). Code THẬT của
> project nằm ở mục 9.

---

## 0. Increment 1 làm gì (1 phút)

Boundary "service nội bộ Hub gọi nhau": một service đưa **clientId + clientSecret** cho IdP,
đổi lấy **JWT**; service kia **verify JWT** (chữ ký + scope) mà không hỏi ngược IdP mỗi request.

```
identity-provider :9000   ──── JWT ────►   payment-hub :8080
(Authorization Server)                     (Resource Server)
PHÁT + ký token                            VERIFY token + enforce scope
```

Toàn bộ cơ chế xoay quanh **một khung xương duy nhất của Spring Security**:
`Authentication → AuthenticationManager → AuthenticationProvider`. Hiểu khung này là hiểu tất cả.

---

## 1. Nền tảng: 3 nhân vật (ví dụ username/password cho dễ)

Tạm quên OAuth2. Hình dung API `POST /login` với `username=khanh, password=123456`.

### 1.1 `Authentication` — CÁI HỘP chứa thông tin, KHÔNG phải kẻ xác thực

```java
// Hộp "chưa duyệt": mới chỉ nói "tôi là khanh, đây là mật khẩu"
Authentication request = new UsernamePasswordAuthenticationToken("khanh", "123456");
```

```
Authentication (request)
├── principal     = "khanh"
├── credentials   = "123456"
└── authenticated = false      ← QUAN TRỌNG: chưa chứng minh gì
```

> Ghi nhớ #1: `Authentication` chỉ là **dữ liệu**. Nó không tự kiểm tra mình đúng hay sai.

### 1.2 `AuthenticationProvider` — NGƯỜI KIỂM TRA thật sự

```java
public class MyProvider implements AuthenticationProvider {

    @Override
    public Authentication authenticate(Authentication authentication) {
        String username = authentication.getName();                    // "khanh"
        String password = authentication.getCredentials().toString();  // "123456"

        if (!username.equals("khanh"))  throw new BadCredentialsException("User not found");
        if (!password.equals("123456")) throw new BadCredentialsException("Wrong password");

        // Trả HỘP MỚI "đã duyệt": có authorities, authenticated=true
        return new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
```

Provider: **nhận hộp chưa duyệt → trả hộp đã duyệt** (hoặc ném lỗi).

### 1.3 `AuthenticationManager` — NGƯỜI ĐIỀU PHỐI

```java
Authentication result = authenticationManager.authenticate(request);
```

Bên trong (bản `ProviderManager`, rút gọn):

```java
for (AuthenticationProvider provider : providers) {
    if (!provider.supports(request.getClass())) continue;   // không hợp loại → thử tiếp
    return provider.authenticate(request);                  // hợp loại → giao việc
}
throw new ProviderNotFoundException();
```

```
AuthenticationManager
   ├── DaoAuthenticationProvider   supports? NO
   ├── LdapAuthenticationProvider  supports? NO
   └── MyProvider                  supports? YES → authenticate()
```

Bạn **không cần biết** provider nào xử lý — Manager tự chọn qua `supports()`. Đây là
**Chain of Responsibility** ở tầng "loại Authentication".

### 1.4 Kết quả

```java
result.getName();          // "khanh"
result.isAuthenticated();  // true
result.getAuthorities();   // [ROLE_USER]
```

**Khung xương chốt (nhớ đúng đoạn này là đủ):**
```
new XxxAuthenticationToken(...)      → hộp CHƯA duyệt (authenticated=false)
        ↓ authenticationManager.authenticate(...)
        ↓ Manager chọn Provider qua supports()
        ↓ provider.authenticate(...)   → xét thật
new XxxAuthenticationToken(...)      → hộp ĐÃ duyệt (authenticated=true, có quyền)
```

---

## 2. Áp vào OAuth2 — CỬA 1: xác thực client

Trong project, "username/password" đổi thành **clientId/clientSecret**. Request:

```
POST /oauth2/token
Authorization: Basic base64("hub-webhook:hub-webhook-secret")
```

### 2.1 Converter ĐỌC header → hộp chưa duyệt

`ClientSecretBasicAuthenticationConverter` (chạy vì thấy header `Basic`):

```java
byte[] decoded = Base64.getDecoder().decode(part_after_"Basic ");
String[] cred = new String(decoded, UTF_8).split(":", 2);
String clientId = cred[0];   // "hub-webhook"
String secret   = cred[1];   // "hub-webhook-secret"

// ⚠️ CHƯA tra DB → principal chỉ là clientId dạng STRING, CHƯA có RegisteredClient
return new OAuth2ClientAuthenticationToken(
        clientId,                                   // String!
        ClientAuthenticationMethod.CLIENT_SECRET_BASIC,
        secret, null);                              // authenticated = false
```

```
OAuth2ClientAuthenticationToken (chưa duyệt)
├── principal     = "hub-webhook"        ← STRING, không phải object
├── method        = CLIENT_SECRET_BASIC
├── credentials   = "hub-webhook-secret"
└── authenticated = false
```

### 2.2 Provider XÉT → hộp đã duyệt

`ClientSecretAuthenticationProvider`:

```java
public Authentication authenticate(Authentication authentication) {
    OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;

    // (a) BÂY GIỜ mới tra DB → có RegisteredClient
    RegisteredClient rc = registeredClientRepository.findByClientId(token.getName());
    if (rc == null) throw new OAuth2AuthenticationException(INVALID_CLIENT);            // 401

    // (b) cách auth có được phép? — đối chiếu cột client_authentication_methods
    if (!rc.getClientAuthenticationMethods().contains(token.getClientAuthenticationMethod()))
        throw new OAuth2AuthenticationException(INVALID_CLIENT);                        // 401

    // (c) secret đúng? — băm lại so hash (BCrypt), KHÔNG so chuỗi thô
    if (!passwordEncoder.matches((String) token.getCredentials(), rc.getClientSecret()))
        throw new OAuth2AuthenticationException(INVALID_CLIENT);                        // 401

    // đã duyệt: principal giờ là RegisteredClient (object), authenticated=true
    return new OAuth2ClientAuthenticationToken(rc, token.getClientAuthenticationMethod(),
            token.getCredentials());
}
```

> **Ghi nhớ #2 (điểm dễ sai nhất):** trước Provider bạn chỉ có **cái TÊN** (clientId String);
> sau Provider mới có **HỒ SƠ** (`RegisteredClient`). `findByClientId` là ranh giới "đọc → xét".

Token đã duyệt được cất vào `SecurityContextHolder` (ThreadLocal) để **Cửa 2** dùng lại.

---

## 3. CỬA 2: grant `client_credentials` — sinh token

Đây là **một Authentication KHÁC** (khác loại, khác provider). Cùng khung xương, lặp lại.

### 3.1 Converter đọc body → hộp chưa duyệt

`OAuth2ClientCredentialsAuthenticationConverter`:

```java
// body: grant_type=client_credentials&scope=payments:read
Authentication clientPrincipal = SecurityContextHolder.getContext().getAuthentication(); // client ĐÃ duyệt ở Cửa 1
return new OAuth2ClientCredentialsAuthenticationToken(
        clientPrincipal, requestedScopes /* [payments:read] */, additionalParams);
```

> Chú ý: Cửa 2 **không auth lại secret** — nó nhận thẳng client đã duyệt từ Cửa 1.

### 3.2 Provider xét grant + scope → sinh JWT

`OAuth2ClientCredentialsAuthenticationProvider`:

```java
public Authentication authenticate(Authentication authentication) {
    OAuth2ClientCredentialsAuthenticationToken req = (...) authentication;
    OAuth2ClientAuthenticationToken clientPrincipal = getAuthenticatedClientElseThrowInvalidClient(req);
    RegisteredClient rc = clientPrincipal.getRegisteredClient();

    // (a) client có được phép luồng này? — đối chiếu cột authorization_grant_types
    if (!rc.getAuthorizationGrantTypes().contains(AuthorizationGrantType.CLIENT_CREDENTIALS))
        throw new OAuth2AuthenticationException(UNAUTHORIZED_CLIENT);

    // (b) scope xin ⊆ scope đăng ký? (cột scopes) — else invalid_scope
    Set<String> authorizedScopes = validateSubset(req.getScopes(), rc.getScopes());

    // (c) SINH + KÝ token (xem mục 4)
    OAuth2TokenContext ctx = context(clientPrincipal, authorizedScopes, ACCESS_TOKEN);
    OAuth2Token generated = this.tokenGenerator.generate(ctx);
    OAuth2AccessToken accessToken = new OAuth2AccessToken(BEARER, generated.getTokenValue(), ...);

    // (d) trả hộp "đã có token"
    return new OAuth2AccessTokenAuthenticationToken(rc, clientPrincipal, accessToken);
}
```

> **Ghi nhớ #3 (điểm dễ hiểu lầm thứ hai):** bước (c),(d) là **của THƯ VIỆN**. Project KHÔNG
> tự viết method kiểu `generateTokenPair()`. Có 2 cách xây auth server:
> - **(A) Library-driven** — provider thư viện lo sinh/ký token. ← *ta chọn cái này.*
> - **(B) Hand-rolled** — tự viết `generateTokenPair()` bằng lib `jjwt`. ← nhiều tutorial làm, KHÔNG phải ta.

---

## 4. Sinh & ký JWT (bên trong bước (c))

Pipeline: `JwtGenerator → JwtEncoder → JWKSource`.

```java
// JwtGenerator (rút gọn): dựng claims rồi nhờ encoder ký
JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer(...)                          // iss
        .subject("hub-webhook")               // sub = clientId
        .issuedAt(now).expiresAt(now + 300s)  // TTL 5 phút (từ TokenSettings)
        .claim("scope", authorizedScopes)     // ["payments:read"]
        .build();
return jwtEncoder.encode(JwtEncoderParameters.from(header, claims));

// NimbusJwtEncoder: hỏi JWKSource lấy private key RSA, gắn kid, ký RS256
```

Kết quả — JWT 3 phần `header.payload.signature`:

```
header : { "alg": "RS256", "kid": "idp-signing" }
payload: { "sub": "hub-webhook", "scope": ["payments:read"], "iss": ..., "iat": ..., "exp": iat+300 }
signature: <ký bằng private key của IdP>
```

Response cuối:

```json
{ "access_token": "eyJraWQ...", "token_type": "Bearer", "expires_in": 299, "scope": "payments:read" }
```

`JWKSource` cũng là thứ đứng sau endpoint **`/oauth2/jwks`** — nơi công bố **public key** (kèm `kid`)
cho ai muốn verify.

---

## 5. Phía payment-hub: VERIFY token (đối xứng)

Request tới payment-hub:
```
GET /v1/payments/pay_123
Authorization: Bearer eyJraWQ...
```

Cũng là khung Filter → (decoder thay cho provider):

```java
// BearerTokenAuthenticationFilter đọc "Authorization: Bearer ..."
// rồi giao token cho JwtDecoder verify:
Jwt jwt = jwtDecoder.decode(tokenValue);
// NimbusJwtDecoder:
//   1) fetch JWKS từ jwk-set-uri (LẦN ĐẦU) rồi CACHE
//   2) đọc kid trong header JWT → chọn đúng public key
//   3) verify chữ ký RS256 + kiểm exp   → tất cả CỤC BỘ, không gọi ngược IdP
// map claim "scope" → authorities: "payments:read" → SCOPE_payments:read
```

Rồi rule bảo mật enforce:

```java
.requestMatchers(HttpMethod.GET, "/v1/payments/**").hasAuthority("SCOPE_payments:read")
```

- token có `SCOPE_payments:read` → **200**
- token hợp lệ nhưng thiếu scope đó → **403**
- token thiếu/sai/hết hạn → **401**

> Hệ quả kiến trúc: vì verify **cục bộ bằng JWKS đã cache**, **tắt IdP** mà token còn hạn thì
> payment-hub vẫn trả 200 (đã thử ở bước B5). Đây là điểm mạnh của JWT bất đối xứng.

---

## 6. Ba cột định danh client (đóng đinh)

```
oauth2_registered_client (dòng hub-webhook)
├── client_authentication_methods = [client_secret_basic]   "vào BẰNG KHÓA gì"  → Cửa 1
├── authorization_grant_types     = [client_credentials]    "xin VÉ loại gì"    → Cửa 2
└── scopes                        = [payments:read]         "vé CHO LÀM gì"     → Cửa 2 (trần) + payment-hub (enforce)
```

`scopes` kiểm **2 nơi**: IdP giới hạn *trần* lúc phát; payment-hub enforce *đủ/thiếu* lúc dùng.
→ Vì thế **403 sinh ở payment-hub, KHÔNG ở IdP**.

Bản đồ lỗi:

| Vi phạm | Lỗi | Sinh ở |
|---|---|---|
| sai *cách* auth / sai secret | `401 invalid_client` | IdP Cửa 1 |
| xin grant không được phép | `unauthorized_client` | IdP Cửa 2 |
| xin scope quá trần | `invalid_scope` | IdP Cửa 2 |
| thiếu/sai/hết hạn Bearer | `401` | payment-hub |
| token OK, thiếu scope endpoint | **`403`** | payment-hub |

`hub-ledger` (scope `ledger:write`) là "quân xanh" cố ý để test 403.

---

## 7. Hai Pattern lộ ra ở đâu

| Pattern | Chỗ trong code | Ý nghĩa |
|---|---|---|
| **Chain of Responsibility** | (a) Servlet Filter tự bỏ qua bằng `RequestMatcher`; (b) `ProviderManager` chọn provider qua `supports()` | "của tôi thì xử, không thì chuyền" — 2 tầng |
| **Strategy + Delegating** | `DelegatingAuthenticationConverter` gồm [Basic, Post, JWT, None]; các `AuthenticationProvider` (Dao/Ldap/OAuth2) | nhiều cách làm cùng interface, thay nhau/thêm mới không sửa cũ |
| **Tách Converter ↔ Provider** | Converter *đọc* (không đụng DB); Provider *xét* (không đụng HttpServletRequest) | ghép chéo, test dễ, đổi nguồn dữ liệu không đụng luật |

---

## 8. Toàn cảnh một request (bản đồ đã đúng)

```
POST /oauth2/token  Basic(hub-webhook:secret)  grant=client_credentials scope=payments:read
      │
      ▼ Converter đọc Basic
OAuth2ClientAuthenticationToken(principal="hub-webhook" [String], authenticated=FALSE)
      │ Manager → ClientSecretAuthenticationProvider.supports()==YES
      ▼ findByClientId → RegisteredClient ; bcrypt.matches(secret) ; method hợp lệ
OAuth2ClientAuthenticationToken(principal=<RegisteredClient>, authenticated=TRUE)   ── CỬA 1 xong
      │ cất SecurityContextHolder
      ▼ Converter đọc grant_type/scope
OAuth2ClientCredentialsAuthenticationToken(principal = client-đã-duyệt)
      │ Manager → OAuth2ClientCredentialsAuthenticationProvider (THƯ VIỆN)
      ▼ check grant ⊆ ; check scope ⊆ ; JwtGenerator→NimbusJwtEncoder ký RS256 (kid)
OAuth2AccessTokenAuthenticationToken(accessToken = JWT)                              ── CỬA 2 xong
      │
      ▼  ← 200 { access_token, expires_in:299, scope }

GET /v1/payments/pay_123  Bearer <JWT>
      │ BearerTokenAuthenticationFilter → JwtDecoder (JWKS cache, kid) verify chữ ký+exp cục bộ
      ▼ scope→SCOPE_payments:read ; rule hasAuthority("SCOPE_payments:read") khớp
   ← 200 stub
```

---

## 9. Code THẬT trong project (không rút gọn)

### identity-provider — `config/AuthorizationServerConfig.java` (điểm chính)

```java
@Bean @Order(Ordered.HIGHEST_PRECEDENCE)
public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
    OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);   // bật /oauth2/token, /oauth2/jwks...
    return http.build();
}

@Bean
public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/**").permitAll()
            .anyRequest().authenticated());
    return http.build();
}

@Bean
public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
    return new JdbcRegisteredClientRepository(jdbcTemplate);             // JDBC, KHÔNG ORM
}

@Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

@Bean
public JWKSource<SecurityContext> jwkSource(...) throws Exception {      // RSA từ keystore PKCS12
    KeyStore ks = KeyStore.getInstance("PKCS12");
    ks.load(resourceLoader.getResource(keystoreLocation).getInputStream(), keystorePassword.toCharArray());
    RSAKey rsaKey = RSAKey.load(ks, keyAlias, keystorePassword.toCharArray());
    RSAKey withKid = new RSAKey.Builder(rsaKey).keyID(keyAlias).build(); // kid = alias (rotation)
    return new ImmutableJWKSet<>(new JWKSet(withKid));
}

@Bean
public ApplicationRunner seedClients(RegisteredClientRepository repo, PasswordEncoder encoder) {
    return args -> {
        saveIfAbsent(repo, encoder, "hub-webhook", "hub-webhook-secret", "payments:read");
        saveIfAbsent(repo, encoder, "hub-ledger",  "hub-ledger-secret",  "ledger:write");
    };
}
// saveIfAbsent → RegisteredClient.withId(uuid).clientId(..).clientSecret(encoder.encode(secret))
//   .clientAuthenticationMethod(CLIENT_SECRET_BASIC).authorizationGrantType(CLIENT_CREDENTIALS)
//   .scope(scope).tokenSettings(accessTokenTimeToLive(5m)).build()  → repo.save(client)
```

### payment-hub — `config/ResourceServerConfig.java`

```java
@Configuration @EnableWebSecurity
public class ResourceServerConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/payments/**").hasAuthority("SCOPE_payments:read")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))  // validate JWT
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
```

### payment-hub — `api/PaymentDemoController.java`

```java
@RestController @RequestMapping("/v1/payments")
public class PaymentDemoController {
    @GetMapping("/{id}")
    public Map<String, Object> getPayment(@PathVariable String id) {
        return Map.of("id", id, "amount", 150000, "currency", "VND",
                      "status", "AUTHORIZED", "_note", "stub data - Increment 1");
    }
}
```

### payment-hub — `application.yml` (điểm chính)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${IDP_JWK_SET_URI:http://localhost:9000/oauth2/jwks}  # KHÔNG issuer-uri
```

---

## 10. Cách test nhanh

```bash
# happy path → 200
TOKEN=$(curl -s -u hub-webhook:hub-webhook-secret \
  -d 'grant_type=client_credentials&scope=payments:read' \
  http://localhost:9000/oauth2/token | jq -r .access_token)
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" http://localhost:8080/v1/payments/pay_123

# 403 (hub-ledger thiếu payments:read) | 401 (no token) | 401 (bad token)
```

Postman: Request 1 (POST token, Basic auth + body form) → copy `access_token` →
Request 2 (GET, Bearer). Hoặc Authorization = **OAuth 2.0 / Client Credentials** cho Postman tự lấy.

---

## 11. Ba điều phải nhớ (rút gọn tối đa)

1. **Khung xương:** `Authentication (dữ liệu) → Manager (điều phối, chọn qua supports) → Provider (xét thật)`.
   Hộp đi từ `authenticated=false` → `true`.
2. **Trước Provider chỉ có TÊN (clientId String); sau Provider mới có HỒ SƠ (`RegisteredClient`).**
   `findByClientId` là ranh giới đọc→xét.
3. **Ta dùng provider của thư viện để sinh/ký token — KHÔNG tự viết `generateTokenPair`.**
   Đó là điểm khác nhau giữa "library-driven" (ta) và "hand-rolled" (tutorial).

Và một request `/oauth2/token` chạy khung xương **HAI LẦN**: Cửa 1 (anh là ai) → Cửa 2 (anh xin vé gì).

---

## 12. DoD Increment 1

- [x] Cấp JWT qua client_credentials (kid, sub, scope, TTL 300s, RS256).
- [x] payment-hub verify cục bộ (JWKS cache) + enforce scope.
- [x] 200 / 403 / 401 đúng; B5 chịu lỗi khi tắt IdP.
- [ ] **Automated test (Testcontainers)** — còn thiếu (CLAUDE.md để `[~]`).
