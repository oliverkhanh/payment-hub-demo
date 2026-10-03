# CLAUDE.md — Bộ não context cho AI

> File này được Claude Code tự động nạp mỗi phiên. Mục tiêu: cho AI đủ context để
> làm việc **nhất quán, đúng kiến trúc, không đi chệch hướng**. Giữ file này ngắn gọn
> nhưng đủ — mọi quy ước quan trọng phải nằm ở đây, đừng bắt AI đoán.

## 1. Project là gì (đọc trước khi làm bất cứ gì)

Payment Hub là **learning project** mô phỏng một Payment Gateway gần production
(kiểu Stripe/VNPay/Adyen thu nhỏ). **Không** phải e-commerce, **không** phải core
banking. Mọi quyết định ưu tiên theo thứ tự:

1. **Học được nhiều nhất** về distributed systems & backend architecture.
2. **Sát production** về pattern và cách hệ thống giao tiếp.
3. **Một người tự build & vận hành được** (không over-engineer).
4. **Rẻ** khi chạy trên AWS (tắt được khi không dùng).

**Ưu tiên SÁT THỰC TẾ nhất có thể** về mặt *kiến trúc, pattern, cách hệ thống giao
tiếp* — đây là mong muốn rõ ràng của người dùng. Việc đơn giản hóa **chỉ được phép ở
tầng quy mô vận hành** (số instance, HA, cluster size), không được đơn giản hóa ở tầng
thiết kế/luồng nghiệp vụ.

Khi 4 tiêu chí xung đột: giữ *thiết kế* sát production, chỉ hạ *quy mô vận hành* cho một
người kham nổi, và **luôn ghi chú rõ** "production sẽ làm X, ở đây làm Y vì Z". Ví dụ:
vẫn dùng Outbox + Kafka + Saga đúng chuẩn (thiết kế thật), nhưng chạy Kafka 1 broker thay
vì cluster (quy mô nhỏ) — kèm ghi chú.

## 2. Ba hệ thống

| Hệ thống | Thư mục | Vai trò | Độ ưu tiên |
|---|---|---|---|
| Identity Provider | `identity-provider/` | **Platform IAM** (Spring Authorization Server), 2 realm tách khoang: **machine** (service nội bộ Hub, client_credentials→JWT) + **merchant** (con người của merchant, OIDC login + RBAC + multi-tenant). Kiểu Cognito thu nhỏ | Nền tảng |
| Merchant Demo | `merchant-demo/` | Tạo dữ liệu, demo, giả lập lỗi merchant | Phụ trợ, tối giản |
| Payment Hub | `payment-hub/` | Hệ thống chính, chứa toàn bộ độ phức tạp | **Trọng tâm** |
| Fake Bank | `fake-bank/` | Giả lập ngân hàng + giả lập lỗi để test Hub | Vừa phải |

**Merchant Demo KHÔNG cần:** login, user management, inventory, voucher, promotion,
shipping, admin phức tạp, dashboard. Đừng tự ý thêm.

> Lưu ý: *con người* của merchant đăng nhập vào **Merchant Portal phía Payment Hub** (như
> Stripe Dashboard) — thuộc `payment-hub/`, KHÔNG phải trong Merchant Demo. Merchant Demo
> vẫn giữ tối giản, không login.

## 3. Tech stack (mặc định — đừng đổi nếu không được yêu cầu)

- **Ngôn ngữ/Framework:** Java 21 + Spring Boot 3.x. Build bằng **Maven**.
- **DB:** PostgreSQL 16, mỗi service một schema/DB riêng (DB-per-service). ORM: Spring Data JPA + Flyway migration.
- **Cache/Lock:** Redis 7 (cache, distributed lock qua Redisson, idempotency key store).
- **Auth — machine (cross-org KHÔNG dùng IdP nội bộ):** (1) Merchant server→Hub: **API key + HMAC** (Hub cấp); (2) Hub↔Bank: **mTLS hoặc API key/HMAC do Bank cấp** + callback ký HMAC; (3) service nội bộ Hub gọi nhau: **OAuth2 client-credentials → JWT** (IdP realm "machine"), validate JWKS + scopes.
- **Auth — human:** nhân viên merchant đăng nhập **Merchant Portal (phía Hub)** qua **OIDC authorization_code + PKCE** (IdP realm "merchant") + **MFA bắt buộc (TOTP)**, **BFF** (token giữ server, cookie httpOnly), có **RBAC + multi-tenant** (token mang `merchantId` + `role`). **Logout** = xóa session + **revoke refresh token** (RFC 7009); access token TTL ngắn; refresh token rotation. IdP là Platform IAM 2 realm (machine + merchant), tách khoang.
- **Messaging:** Kafka là event backbone. SQS/SNS cho async task + fan-out webhook/notification + DLQ. RabbitMQ chỉ nhắc tới để so sánh, **không** deploy.
- **Real-time:** `hub-notification` đẩy noti real-time qua **WebSocket** (Spring WebSocket, self-managed), fan-out giữa instance qua **Redis Pub/Sub**, sau ALB. API Gateway WebSocket là bản managed thay thế (upgrade path).
- **Frontend:** UI giàu tương tác (merchant shop, Merchant Portal) = **React + Vite**; trang form/kết quả/auth đơn giản (IdP login+MFA, fake-bank outcome, có thể cả hub checkout) = **server-rendered Thymeleaf** (gọn, ít JS, an toàn hơn). Không cần đẹp.
- **Infra:** Docker + docker-compose (dev), ECS Fargate (AWS), Terraform (IaC), LocalStack (giả lập AWS ở local).
- **Observability:** OpenTelemetry SDK → OTLP. Metrics: Prometheus + Micrometer. Dashboard: Grafana. Trace: Jaeger/Tempo. Log: JSON structured, gom về CloudWatch/Loki.
- **CI/CD:** GitHub Actions.

## 4. Nguyên tắc kiến trúc bắt buộc tuân theo

Đây là các "hiến pháp" của codebase. Vi phạm = sai.

1. **Idempotency-first.** Mọi API tạo/refund payment và mọi consumer event/webhook phải idempotent. Không có ngoại lệ. Dùng `Idempotency-Key` header + bảng `idempotency_records`.
2. **Outbox Pattern cho mọi state-change kèm event.** Không bao giờ ghi DB rồi publish Kafka trong cùng một chỗ mà không qua outbox. "DB commit + event" phải nguyên tử qua bảng `outbox`.
3. **State machine tường minh.** Payment/Refund có state machine rõ ràng. Chuyển trạng thái phải qua service kiểm tra transition hợp lệ, không set status tùy tiện.
4. **Money = số nguyên minor unit (long).** Tuyệt đối không dùng `float/double` cho tiền. Luôn kèm `currency` (ISO 4217). Dùng `BigDecimal` chỉ ở biên hiển thị.
5. **Không lưu dữ liệu thẻ thật.** Né PCI scope bằng tokenization giả lập. Không có PAN/CVV thật trong DB hay log.
6. **Mọi external call phải có timeout + retry policy + circuit breaker.** Không có "gọi HTTP trần".
7. **Audit log cho mọi hành động thay đổi tiền/trạng thái.** Append-only.
8. **Secrets không hardcode.** Local: `.env` (gitignored). AWS: Secrets Manager. Không commit key.

## 5. Quy ước code

- **Package:** `com.paymenthub.<service>.<layer>` — layer: `api` (controller/dto), `domain` (entity, state machine, service), `infra` (repo, kafka, external client), `config`.
- **Kiến trúc trong service:** Hexagonal (ports & adapters) nhẹ. Domain không phụ thuộc framework nếu tránh được.
- **Naming:** REST resource số nhiều (`/v1/payments`). Event: `<Aggregate>.<PastTenseVerb>` (`Payment.Authorized`). Kafka topic: `paymenthub.<domain>.<event>` (`paymenthub.payment.authorized`).
- **API versioning:** prefix `/v1`.
- **Error:** trả `application/problem+json` (RFC 7807). Có mã lỗi nội bộ (`error_code`) ổn định.
- **Test:** unit cho domain/state machine; integration test dùng Testcontainers (Postgres, Kafka, Redis, LocalStack). Không mock những gì Testcontainers chạy được.
- **Migration:** mọi thay đổi schema qua Flyway, không `ddl-auto=update`.

## 6. Quy ước khi làm việc với Claude

- **⭐ CHẾ ĐỘ HỌC "problem-first" (mặc định — quan trọng nhất, áp dụng MỌI QUY TRÌNH).**
  Người dùng đang HỌC và muốn tự làm để hiểu sâu. Áp dụng cho **tất cả**: thiết kế kiến
  trúc, tài liệu (state machine, bounded context, API contract, business flow…), lẫn code.
  KHÔNG làm hộ ngay. Thay vào đó theo 4 bước:
  1. **AI ra đề:** mô tả bài toán rõ ràng — yêu cầu, ràng buộc, input/output kỳ vọng,
     gợi ý hướng suy nghĩ (KHÔNG đưa lời giải).
  2. **Người dùng xác nhận đề & tự làm trước**, đưa ra giải pháp/thiết kế/code của mình.
  3. **Người dùng nộp** kết quả.
  4. **AI review:** chỉ ra đúng/sai, lỗ hổng (edge case, race condition, idempotency,
     thiết kế thiếu…), cách cải thiện, và *giải thích vì sao* — như senior review PR junior.
  → Chỉ làm hộ khi người dùng nói rõ "làm giúp/viết hộ". Khi bí, AI *gợi ý* hoặc cho
  *hint theo tầng* (hint nhỏ trước, lời giải sau), không nhảy thẳng tới đáp án.
  → Ngoại lệ: tài liệu nền đã soạn sẵn (docs 00, 01) giữ nguyên làm "sách giáo khoa";
  từ docs 02 trở đi ưu tiên để người dùng tự thiết kế trước rồi AI review.
- **Trước khi code một feature:** đọc flow tương ứng trong `docs/02-business-flows.md` và state machine trong `docs/03-system-design.md`. Nếu tài liệu chưa có, hỏi hoặc bổ sung tài liệu trước.
- **Quy ước code chi tiết** (naming, package, error format, mẫu test) sẽ nằm ở **skill `paymenthub-conventions`** (tạo ở Sprint 0, khi có code mẫu thật), không nhồi vào file này.
- **Luôn giải thích trade-off** khi có nhiều phương án, đừng chỉ chọn im lặng.
- **Không tự ý mở rộng scope.** Nếu thấy thiếu thành phần quan trọng của gateway thật → đề xuất và giải thích, để người dùng quyết, đừng tự thêm.
- **Ưu tiên viết được test.** Feature không test được coi như chưa xong.
- **Commit nhỏ, thông điệp rõ.** Chỉ commit/push khi được yêu cầu.
- **Khi bí về AWS/LLM/pricing:** đọc tài liệu chính thức, đừng phán từ trí nhớ.

## 7. Trạng thái hiện tại

Dự án chia theo **Increment (lát cắt dọc)** — xem `docs/06-roadmap.md` (xương sống).
Mỗi increment: BA ra spec → engineer thiết kế → review → code tới DoD.

- [x] Nền tảng: README, CLAUDE.md, docs/00 (overview), docs/01 (AWS), docs/06 (roadmap)
- [x] **Increment 0** — Skeleton 4 backend + docker-compose (DONE, đã verify: 7 container healthy, 4 /health UP, Flyway migrate 4 DB riêng, JSON logging ECS)
- [~] Increment 1 — IdP realm machine (client_credentials→JWT): CODE + verify thủ công OK (token/200/403/401/validate cục bộ). Còn thiếu: automated test (Testcontainers)
- [~] Increment 2 — Merchant catalog→order. **Phase 1 DONE** (verify end-to-end OK): BE `merchant-demo` (products seed, POST /v1/orders với server-side total + snapshot giá + idempotency `UNIQUE(idempotency_key)` + problem+json) + FE React/Vite (`merchant-demo/frontend`, shop "Chợ Phiên"). Trade-off: cart giữ client-side (không persist). Còn Phase 2-5 (gọi Hub, webhook, list đơn, chaos). Còn thiếu: automated test.
- [~] **Increment 3** — Payment init (Merchant → Hub). **CODE + 29 automated test XANH** (Testcontainers Postgres): `merchants`/`payments`/`idempotency_records` + partial unique `uq_payment_active`, filter HMAC (canonical string 5 dòng, constant-time compare, cửa sổ 5 phút, xoay khoá 2 secret), 3 SecurityFilterChain (`/v1/**` HMAC · `/checkout/**` token · `/internal/**` JWT), idempotency 4 nhánh (201/200 replay/409/422), hosted checkout Thymeleaf, job quét hết hạn. Thiết kế: `docs/specs/02-payment-init-design.md`. Trade-off: `api_secret` lưu plaintext (DEV-ONLY, production = AES+KMS); chưa có rate limit (Inc 9); job chưa có distributed lock (1 instance).
- [~] **Increment 4** — Hub↔Bank. **ĐANG LÀM.** Xong & verify end-to-end: (1) danh sách bank ở
  trang checkout từ config `hub.banks`; (2) Adapter `VcbAdapter`/`TcbAdapter`/`AcbAdapter` +
  port `BankConnector` + `BankConnectorRegistry` (thêm bank = thêm 1 bean); (3) **CAS
  `CREATED→IN_PROGRESS`** (`PaymentRepository.markInProgress`) + **intent log
  `payment_attempts`** ghi-commit TRƯỚC khi gọi + **gọi Bank thật bằng HMAC** (credential do
  Bank cấp, mỗi bank một bộ) + timeout/retry + redirect sang trang bank. fake-bank có 3
  dialect API (JSON snake / JSON camel / form-urlencoded) xác thực HMAC trên **byte thô**.
  Trang thanh toán của bank hiện là **STUB** (hiện số tiền + mã GD).
  **Còn thiếu:** circuit breaker (Resilience4j, spec §2.3c — CLAUDE.md #6 chưa thoả),
  nhập tài khoản/PIN + trừ tiền ở bank, callback Bank→Hub, verify-on-return, webhook
  Hub→merchant (Outbox), đồng hồ đếm ngược, job đối soát, automated test cho Inc 4.
- [ ] Increment 5..6 — Webhook/Outbox → Refund+Ledger
- [ ] Increment 7 — Merchant Portal & Human IAM (OIDC + RBAC + multi-tenant)
- [ ] Increment 8 — Real-time Notification (WebSocket + Redis Pub/Sub + SNS)
- [ ] Increment 9..13 — Chaos → Recon/Settlement → Observability → AWS → Nâng cao

> Cập nhật mục này mỗi khi chốt DoD một increment.

> Mỗi khi hoàn thành một mốc, cập nhật mục này để phiên AI sau nắm được tiến độ.
