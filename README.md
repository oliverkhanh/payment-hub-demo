# Payment Hub — Learning Project

Một **Payment Gateway mô phỏng gần production**, xây để học Backend Architecture &
Distributed Systems. Không phải website bán hàng, không phải core banking — trọng tâm
là các bài toán của một payment gateway thật: idempotency, retry, consistency,
outbox/saga, reconciliation, webhook delivery, fraud, settlement…

## Ba hệ thống độc lập

```
  ┌────────────────┐      ┌──────────────────┐      ┌──────────────┐
  │  Merchant Demo │─────▶│   Payment Hub    │─────▶│  Fake Bank   │
  │  (tạo dữ liệu, │◀─────│  (hệ thống chính)│◀─────│ (giả lập NH) │
  │   nhận webhook)│      │                  │      │              │
  └────────────────┘      └──────────────────┘      └──────────────┘
```

| Thư mục             | Hệ thống          | Vai trò |
|---------------------|-------------------|---------|
| `identity-provider/`| Identity Provider | Platform IAM (kiểu Cognito thu nhỏ) — 2 realm: **machine** (service nội bộ, client_credentials→JWT) + **merchant** (con người của merchant, OIDC + RBAC + multi-tenant) |
| `merchant-demo/`    | Merchant Demo     | Sinh dữ liệu + demo flow + mô phỏng lỗi phía merchant |
| `payment-hub/`      | Payment Hub       | Hệ thống chính — nơi tập trung toàn bộ độ phức tạp |
| `fake-bank/`        | Fake Bank         | Giả lập ngân hàng/acquirer + mô phỏng đủ loại lỗi để test Hub |
| `infra/`            | Infrastructure    | Terraform, docker-compose, cấu hình LocalStack |
| `docs/`             | Documentation     | Toàn bộ tài liệu thiết kế |

**Monorepo, mỗi hệ thống là Maven project độc lập** (không parent/common). Hệ thống có UI thì
tách `backend/` + `frontend/`:

```
identity-provider/backend/          # Spring Authorization Server (login/MFA: Thymeleaf trong BE)
merchant-demo/{backend, frontend}   # frontend: React+Vite (Inc 2)
fake-bank/{backend, frontend}       # frontend: UI chọn success/fail/cancel (Inc 4)
payment-hub/backend                 # modular monolith
payment-hub/checkout-web            # hosted checkout người mua (Inc 3)
payment-hub/portal-web              # Merchant Portal dashboard (Inc 7)
```

DB-per-service (database + user riêng mỗi service) · Kafka KRaft (1 broker) · config qua
`.env`/env var (AWS: Secrets Manager). Frontend tạo *đúng lúc increment cần*.

## Tài liệu (đọc theo thứ tự)

| # | File | Nội dung |
|---|------|----------|
| 00 | [docs/00-overview.md](docs/00-overview.md) | Mục tiêu, phạm vi, nguyên tắc thiết kế, thành phần bắt buộc/nên có/nâng cao |
| 01 | [docs/01-aws-architecture.md](docs/01-aws-architecture.md) | Kiến trúc AWS, chọn/không chọn từng service + lý do |
| 02 | [docs/02-business-flows.md](docs/02-business-flows.md) | 15+ business flow: mô tả, sequence, API, DB, event, retry, error |
| 03 | [docs/03-system-design.md](docs/03-system-design.md) | Bounded context, DB-per-service, state machine, outbox, saga, cache |
| 04 | [docs/04-design-patterns.md](docs/04-design-patterns.md) | Design pattern nào, ở đâu, vì sao |
| 05 | [docs/05-learning-map.md](docs/05-learning-map.md) | Bảng kiến thức + usecase đặc biệt từng công nghệ |
| 06 | [docs/06-roadmap.md](docs/06-roadmap.md) | Chia Sprint, mỗi sprint chạy được + Definition of Done |
| 07 | [docs/07-ai-workflow.md](docs/07-ai-workflow.md) | Dùng AI (CLAUDE.md, skills, subagent) hiệu quả |

**Specs & tham chiếu** (`docs/specs/`): [identity-and-auth.md](docs/specs/identity-and-auth.md) (use-case auth, clientId/secret, token propagation) · [01-merchant-spec.md](docs/specs/01-merchant-spec.md) (Merchant module).

## Tech stack (tóm tắt)

- **Backend:** Java 21, Spring Boot 3.x (Web, Security, Data JPA), Spring Cloud (chọn lọc)
- **Data:** PostgreSQL (DB-per-service), Redis (cache + distributed lock + idempotency)
- **Messaging:** Kafka (event backbone) + SQS/SNS (async task, fan-out webhook, DLQ)
- **Frontend:** React+Vite (merchant shop, Merchant Portal) + Thymeleaf server-rendered (IdP login/MFA, fake-bank outcome) — tối giản
- **Infra:** Docker, ECS Fargate, Terraform, LocalStack (dev), GitHub Actions (CI/CD)
- **Observability:** OpenTelemetry → Prometheus + Grafana + CloudWatch, Jaeger/Tempo (tracing)

> Chi tiết & lý do chọn từng thứ: xem `docs/`.
