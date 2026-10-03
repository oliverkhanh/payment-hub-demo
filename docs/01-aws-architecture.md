# 01 — AWS Architecture & Deployment

> Định hướng đã chốt: **Cost-optimized / gần production**. Thiết kế theo chuẩn production
> về *pattern*, nhưng runtime chọn phương án rẻ, tắt được, một người vận hành nổi.
> Kafka là backbone (self-managed), SQS/SNS phụ trợ.

---

## 1. Triết lý deployment cho một project học

Có 3 mức "thật" khi deploy, ta chọn mức 2:

| Mức | Cách làm | Chi phí | Học được | Chọn? |
|---|---|---|---|---|
| 1. Localhost/compose | Chạy tất cả trên máy | 0 | Ít về ops/cloud | Dev daily ✅ |
| **2. Cost-optimized AWS** | **Fargate + RDS nhỏ + self-managed Kafka, tắt khi không dùng** | **~vài chục USD/tháng** | **Rất nhiều: IaC, networking, deploy, observability** | **✅ Mục tiêu** |
| 3. Full production | EKS + MSK + multi-AZ + WAF… | hàng trăm USD | Sâu về ops nhưng nặng & đắt | ❌ (chỉ nhắc như "upgrade path") |

**Nguyên tắc:** *develop & test ở mức 1 (miễn phí, nhanh), deploy demo ở mức 2 (thật,
tắt được).* Dùng **LocalStack** ở local để code IaC/SQS/SNS/S3 mà không tốn tiền, rồi
`terraform apply` lên AWS thật khi cần demo.

---

## 2. Sơ đồ kiến trúc AWS (mục tiêu)

```
                            Internet
                               │
                               ▼
                    ┌──────────────────────┐
                    │   Route 53 (DNS)     │   (optional, có thể dùng domain sẵn)
                    └──────────┬───────────┘
                               ▼
                    ┌──────────────────────┐
                    │  Application LB (ALB)│  1 ALB, nhiều listener rule theo host/path
                    │  merchant.* hub.* bank.*
                    └───┬─────────┬────────┘
        ┌───────────────┘         └───────────────┐
        ▼                         ▼                ▼
┌───────────────┐        ┌────────────────┐  ┌──────────────┐   ── public subnets ──
│ Merchant Demo │        │  Payment Hub    │  │  Fake Bank   │
│  ECS Fargate  │        │  ECS Fargate    │  │  ECS Fargate │
│  (FE+BE task) │        │  (nhiều service)│  │  (BE+UI task)│
└───────┬───────┘        └───┬────────┬────┘  └──────┬───────┘
        │                    │        │              │        ── private subnets ──
        │            ┌───────▼──┐ ┌───▼─────┐        │
        │            │  Kafka   │ │  Redis  │        │
        │            │ (Fargate │ │ Elasti- │        │
        │            │ 1 broker)│ │ Cache   │        │
        │            └──────────┘ └─────────┘        │
        ▼                    ▼                       ▼
   ┌─────────────────────────────────────────────────────┐
   │                     RDS PostgreSQL                    │  1 instance, nhiều DB/schema
   │   (merchant_db, hub_*, bank_db — DB-per-service)      │  (production: tách instance)
   └─────────────────────────────────────────────────────┘

   Bổ trợ (managed, rẻ/không tốn khi idle):
   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐ ┌──────────┐ ┌──────────┐
   │   SQS    │ │   SNS    │ │    S3    │ │ Secrets Mgr  │ │CloudWatch│ │   ECR    │
   │(async+DLQ)│ │(fan-out)│ │(recon file│ │  (API keys)  │ │(log/metric│ │ (images) │
   └──────────┘ └──────────┘ │ /report) │ └──────────────┘ └──────────┘ └──────────┘
                             └──────────┘
   Tất cả trong 1 VPC · IAM roles theo task · NAT hoặc VPC endpoints cho outbound
```

**Ý tưởng chính:** 3 hệ thống = 3 nhóm ECS service riêng, sau một ALB dùng
host/path-based routing. Hạ tầng dùng chung (RDS, Redis, Kafka) được *chia sẻ để tiết
kiệm* nhưng *tách logic bằng DB/topic/prefix* để vẫn giữ ranh giới DB-per-service.

---

## 3. Chọn / không chọn từng AWS service — kèm lý do

Bảng này trả lời trực tiếp yêu cầu "giải thích vì sao chọn, cái gì chưa cần".

### 3.1 Compute — **ECS Fargate** ✅ (không EKS, không Lambda cho core)

| Lựa chọn | Quyết định | Lý do |
|---|---|---|
| **ECS Fargate** | ✅ **Chọn** | Serverless container: không quản lý EC2/node. Trả theo task chạy, tắt = ~0. Đủ để học container orchestration mà không gánh độ phức tạp Kubernetes. Deploy 1 Docker image — sát cách production đóng gói Spring Boot. |
| EKS (Kubernetes) | ❌ Để nâng cao | Học rất nhiều nhưng: control plane ~$73/tháng *chạy suốt*, cấu hình nặng, một người dễ sa lầy vào YAML thay vì học payment. Ghi là "upgrade path" khi muốn học K8s riêng. |
| Lambda | ⚠️ Chọn lọc | Spring Boot core không hợp Lambda (cold start, stateful, long connection tới Kafka/DB). **Nhưng** dùng Lambda cho việc *đúng chất event*: xử lý message SQS lẻ, cron reconciliation nhỏ, xử lý S3 upload. Dạy được serverless đúng chỗ. |
| EC2 tự quản | ❌ | Phải tự vá OS, scale tay. Không đáng cho project này. |

> **Trade-off ECS vs EKS:** EKS = học K8s + hệ sinh thái (rất giá trị nghề nghiệp) nhưng
> đắt & phức tạp. ECS = học container/deploy ở mức vừa đủ, rẻ, nhanh có kết quả. Với mục
> tiêu *học payment/distributed systems* (không phải *học K8s*), ECS thắng. Nếu sau này
> muốn học K8s, migrate 3 Docker image sang EKS là bài tập riêng.

### 3.2 Ingress — **ALB** ✅ + **API Gateway** ⚠️

| Service | Quyết định | Lý do |
|---|---|---|
| **Application Load Balancer** | ✅ Chọn | L7 routing theo host/path → chia 3 hệ thống sau *một* ALB (tiết kiệm). Health check, TLS termination, tích hợp ECS. Chuẩn cho HTTP service chạy liên tục. |
| **API Gateway** | ⚠️ Nên có ở Hub | Đặt trước Payment Hub public API để học: rate limiting, API key, request validation, usage plan, throttling — *đúng những thứ một gateway cần*. Trade-off: thêm 1 lớp + chi phí per-request. Khuyến nghị: **bản đầu dùng ALB cho tất cả; thêm API Gateway trước Hub ở sprint bảo mật** để học rate limiting/throttling managed. |

> **Vì sao có cả hai:** ALB = load balancing thuần. API Gateway = quản trị API (auth,
> quota, throttle). Production payment thường: API Gateway (mép ngoài) → ALB → service.
> Ta làm dần: ALB trước, API Gateway thêm sau khi học rate limiting.

### 3.3 Database — **RDS PostgreSQL** ✅

| Quyết định | Chi tiết | Lý do |
|---|---|---|
| **RDS PostgreSQL, 1 instance nhỏ (db.t4g.micro), Single-AZ** | ✅ | Managed Postgres: backup, patch tự động. `t4g.micro` rất rẻ, đủ cho học. Single-AZ (production sẽ Multi-AZ) để tiết kiệm. |
| **DB-per-service bằng nhiều database trong 1 instance** | ✅ | Giữ *ranh giới logic* DB-per-service (mỗi service chỉ được đụng DB của nó) nhưng *chia sẻ instance* để tiết kiệm. Ghi chú: production tách hẳn instance/cluster. |
| Aurora Serverless v2 | ⚠️ Cân nhắc | Auto-scale, scale-to-low khi idle. Hơi đắt hơn t4g.micro ở tải thấp nhưng "thật" hơn. Optional. |

> **Nguyên tắc DB-per-service quan trọng hơn số instance.** Cái ta học là *service không
> chia sẻ bảng, chỉ giao tiếp qua API/event*. Việc chúng tình cờ nằm chung một RDS box là
> chuyện hạ tầng/tiền, tách được bất cứ lúc nào.

### 3.4 Cache & Lock — **ElastiCache Redis** ✅

| Quyết định | Lý do |
|---|---|
| **ElastiCache for Redis, node nhỏ (cache.t4g.micro)** | Dùng cho: (1) cache read (merchant config, payment status), (2) **distributed lock** (Redisson) chống double-processing, (3) **idempotency key store** với TTL, (4) **rate limiting** (token bucket). Một Redis phục vụ 4 bài học kiến trúc. |

### 3.5 Messaging — **Kafka (self-managed trên Fargate)** ✅ + **SQS/SNS** ✅

Đây là quyết định lớn nhất về chi phí.

| Lựa chọn | Quyết định | Lý do |
|---|---|---|
| **Kafka self-managed** (1 broker KRaft mode, chạy như 1 ECS Fargate service) | ✅ **Chọn làm backbone** | MSK managed rẻ nhất cũng ~$150–200/tháng chạy suốt — quá đắt cho học. Self-managed 1 broker (KRaft, không cần Zookeeper) cho bạn *học đủ mọi khái niệm Kafka*: topic, partition, consumer group, offset, log compaction, exactly-once semantics, transactional producer. Trade-off: không HA (1 broker), phải tự vận hành — nhưng đó cũng là bài học. |
| **Amazon MSK** | ❌ Để nâng cao | Managed Kafka thật (HA, multi-AZ). Ghi là "production upgrade": khi muốn học vận hành Kafka thật/HA, đổi endpoint là xong vì code không phụ thuộc. |
| **SQS** | ✅ Chọn (phụ trợ) | Hàng đợi async managed, rẻ (free tier rộng), có **DLQ built-in**, visibility timeout, redrive. Dùng cho: task async không cần ordering/replay (gửi email, xử lý webhook delivery job), và **DLQ** cho message chết. Dạy at-least-once + DLQ *dễ hơn* tự làm trên Kafka. |
| **SNS** | ✅ Chọn (phụ trợ) | Pub/sub fan-out managed. Dùng cho: fan-out notification (1 event → nhiều subscriber: email + audit + fraud), và mô hình **SNS→SQS fanout** kinh điển. |
| **RabbitMQ** | ❌ Không deploy | Chỉ nhắc trong `05-learning-map.md` để so sánh mô hình (AMQP, exchange/queue) với Kafka log. Deploy thêm một broker nữa không đáng. |

> **Vì sao vừa Kafka vừa SQS/SNS (không thừa)?** Chúng dạy 2 mô hình *khác bản chất*:
> - **Kafka = distributed log.** Message được *giữ lại*, có offset, *replay* được, nhiều
>   consumer group đọc độc lập. Hợp cho: event sourcing, outbox stream, reconciliation
>   feed, audit stream. → *"Sự thật xảy ra"* (facts) cần lưu & phát lại.
> - **SQS = work queue.** Message *bị tiêu thụ rồi biến mất*, DLQ sẵn, quản lý đơn giản.
>   Hợp cho: "làm việc này giúp tôi" (commands/jobs) — gửi webhook, gửi mail.
> - **SNS = fan-out.** 1 sự kiện → nhiều nơi quan tâm.
>
> Học được *khi nào dùng log vs queue vs pub/sub* là một trong những bài giá trị nhất.

### 3.6 Object Storage — **S3** ✅

| Dùng cho | Lý do |
|---|---|
| File đối soát (reconciliation), báo cáo settlement, export transaction (CSV) | Rẻ gần như free ở dung lượng nhỏ. Dạy: batch file recon (bank gửi file cuối ngày), presigned URL, S3 event → Lambda xử lý file. Rất sát cách bank thật gửi *settlement file*. |

### 3.7 Secrets & Security

| Service | Quyết định | Lý do |
|---|---|---|
| **Secrets Manager** | ✅ | Lưu API key nội bộ, HMAC secret, DB credential, Kafka cred. Có rotation. Bài học: *không hardcode secret*. (Trade-off chi phí: ~$0.40/secret/tháng — dùng ít secret hoặc Parameter Store cho cái không nhạy.) |
| **SSM Parameter Store** | ✅ (bổ sung) | Miễn phí cho standard param. Lưu config không nhạy (feature flag, endpoint). Kết hợp: Parameter Store cho config, Secrets Manager cho secret thật. |
| **IAM** | ✅ Bắt buộc | *Task role riêng cho mỗi service* — merchant task không được đọc secret của hub. Dạy least-privilege, role-per-service. |
| **KMS** | ⚠️ Ngầm | Mã hóa RDS/S3/Secrets at-rest (bật mặc định). Không cần cấu hình sâu ban đầu. |
| **WAF** | ❌ Nâng cao | Web firewall trước ALB. Chỉ khi học security sâu. |
| **ACM** | ✅ | Cấp TLS cert miễn phí cho ALB (HTTPS). |

### 3.8 Networking — **VPC** ✅

| Thành phần | Quyết định | Lý do |
|---|---|---|
| **1 VPC, 2 AZ, public + private subnet** | ✅ | Public subnet: ALB. Private subnet: ECS task, RDS, Redis, Kafka — *không* expose ra internet. Dạy network segmentation đúng chuẩn. |
| **Security Groups** | ✅ | SG-per-tier: ALB→app (chỉ port app), app→db (chỉ 5432), app→redis (6379), app→kafka (9092). Least-privilege ở tầng mạng. |
| **NAT Gateway vs VPC Endpoints** | ⚠️ Cân nhắc chi phí | NAT Gateway ~$32/tháng *chạy suốt* + data — khá đắt cho học. **Thay bằng VPC Gateway/Interface Endpoints** cho S3, SQS, SNS, Secrets Manager, ECR để task trong private subnet gọi AWS service *không cần NAT*. Chỉ cần NAT nếu task phải gọi internet công cộng (thường không). → Tiết kiệm đáng kể. |

> **Mẹo tiết kiệm lớn:** tránh NAT Gateway bằng VPC Endpoints là một trong những bài học
> "cost-aware architecture" thực dụng nhất — cũng là câu hỏi phỏng vấn AWS phổ biến.

### 3.9 Observability — **CloudWatch** ✅ + stack tự host

| Service | Quyết định | Lý do |
|---|---|---|
| **CloudWatch Logs** | ✅ | Điểm đến log mặc định của ECS. Rẻ ở mức nhỏ. Dạy log group, insights query. |
| **CloudWatch Metrics + Alarms** | ✅ | Metric hạ tầng (CPU, mem, RDS conn) + custom metric + **Alarm** (yêu cầu "Alert"). Alarm → SNS → email. |
| **Prometheus + Grafana** (self-host container hoặc AMP/AMG) | ✅ Nên có | Học *đúng* stack metrics chuẩn ngành: Micrometer expose `/actuator/prometheus` → Prometheus scrape → Grafana dashboard. Bản rẻ: chạy Prom+Grafana như container. Bản managed: AMP+AMG (đắt hơn). |
| **Distributed Tracing (OpenTelemetry → Jaeger/Tempo)** | ✅ Nên có | ⭐ Trace một payment xuyên 3 hệ thống. Chạy Jaeger/Tempo container. Đây là *đỉnh cao học tập* của observability. |
| **X-Ray** | ⚠️ Thay thế | Tracing managed của AWS. Dễ tích hợp nhưng ta ưu tiên OpenTelemetry (chuẩn mở, không khóa hãng). |

### 3.10 CI/CD & Registry

| Service | Quyết định | Lý do |
|---|---|---|
| **ECR** | ✅ | Lưu Docker image cho ECS. Rẻ. |
| **GitHub Actions** | ✅ | CI/CD: build → test (Testcontainers) → build image → push ECR → deploy ECS. Miễn phí cho repo cá nhân. Dạy pipeline thật. |
| CodePipeline/CodeBuild | ❌ | AWS-native nhưng GitHub Actions phổ biến & miễn phí hơn cho cá nhân. |
| **Terraform** | ✅ | IaC cho *toàn bộ* hạ tầng. Dạy declarative infra, state, module. Chạy `apply` để bật, `destroy` để tắt sạch (tiết kiệm). |

---

## 4. Bảng tổng: dùng ngay / thêm sau / bỏ qua

| Service | Sprint đầu (MVP) | Thêm sau | Không dùng |
|---|:---:|:---:|:---:|
| ECS Fargate | ✅ | | |
| ALB + ACM | ✅ | | |
| RDS PostgreSQL | ✅ | | |
| ElastiCache Redis | ✅ | | |
| Kafka (self-managed) | ✅ | | |
| SQS + DLQ | ✅ | | |
| SNS | | ✅ (khi làm notification) | |
| S3 | | ✅ (khi làm reconciliation) | |
| Secrets Manager + Parameter Store | ✅ | | |
| IAM roles-per-service | ✅ | | |
| VPC + SG + VPC Endpoints | ✅ | | |
| CloudWatch Logs/Metrics/Alarms | ✅ | | |
| Prometheus + Grafana | | ✅ (sprint observability) | |
| OpenTelemetry + Jaeger/Tempo | | ✅ (sprint observability) | |
| ECR + GitHub Actions + Terraform | ✅ | | |
| API Gateway | | ✅ (sprint bảo mật/rate limit) | |
| Lambda | | ✅ (S3 recon, cron nhỏ) | |
| EKS | | | ❌ (upgrade path) |
| MSK | | | ❌ (upgrade path) |
| RabbitMQ | | | ❌ (chỉ so sánh) |
| WAF | | | ❌ (nâng cao) |

---

## 5. Local development (mức 1) — nơi bạn code hằng ngày

**Toàn bộ chạy bằng `docker-compose` + LocalStack**, không tốn tiền AWS:

```
infra/
├── docker-compose.yml          # postgres, redis, kafka(KRaft), localstack, jaeger,
│                               # prometheus, grafana, + 3 hệ thống
├── localstack/                 # seed S3 bucket, SQS queue, SNS topic, secrets
└── terraform/                  # IaC cho AWS thật (mức 2)
    ├── modules/                # vpc, ecs-service, rds, redis, kafka, alb...
    └── envs/
        ├── local/              # trỏ vào LocalStack (endpoint override)
        └── aws/                # AWS thật
```

- **LocalStack** giả lập SQS/SNS/S3/Secrets Manager/Lambda ngay trên máy → code SDK y
  hệt AWS thật, chỉ đổi endpoint. Học IaC & AWS SDK *miễn phí*.
- **Kafka/Postgres/Redis** chạy container thật (không cần giả lập).
- Cùng một Terraform code, đổi `env` để deploy LocalStack ↔ AWS.

> **Vòng lặp phát triển:** code → `docker-compose up` → test (Testcontainers) →
> `terraform apply -var-file=local` (LocalStack) → khi cần demo thật:
> `terraform apply -var-file=aws` → xong demo: `terraform destroy` để khỏi tốn tiền.

---

## 6. Ước lượng chi phí (mức 2, bật liên tục — để bạn liệu cơm gắp mắm)

| Hạng mục | Cấu hình rẻ | ~USD/tháng nếu chạy 24/7 |
|---|---|---|
| ECS Fargate (vài task nhỏ) | 0.25 vCPU/0.5GB mỗi task | ~$15–30 |
| RDS t4g.micro Single-AZ | | ~$12–15 |
| ElastiCache t4g.micro | | ~$11 |
| Kafka trên Fargate (1 task) | | ~$10–15 |
| ALB | | ~$16 + data |
| S3/SQS/SNS/Secrets/CloudWatch | mức nhỏ | ~$1–5 |
| NAT Gateway | **tránh bằng VPC Endpoints** | ~$0 (nếu tránh) / ~$32 (nếu dùng) |
| **Tổng (tránh NAT)** | | **~$70–90/tháng nếu bật 24/7** |

> **Chiến lược tiền:** *không bật 24/7.* `terraform apply` khi học/demo, `destroy` khi
> xong. Chi phí thực tế có thể còn ~vài USD/tháng nếu chỉ bật vài giờ mỗi tuần. Đây cũng
> là một kỹ năng: *ephemeral environments*.

---

## 7. Ánh xạ 3 hệ thống ↔ hạ tầng (deploy độc lập ra sao)

Mỗi hệ thống là **một stack triển khai độc lập** (repo folder riêng, Dockerfile riêng,
ECS service riêng, pipeline riêng) — đúng tinh thần "3 hệ thống độc lập giao tiếp qua
network":

| | Merchant Demo | Payment Hub | Fake Bank |
|---|---|---|---|
| ECS service | `merchant-svc` | nhiều: `hub-gateway`, `hub-payment`, `hub-webhook`, `hub-worker`… | `bank-svc` |
| DB (trong RDS) | `merchant_db` | `hub_payment_db`, `hub_ledger_db`… | `bank_db` |
| Kafka topics | consume webhook | `paymenthub.*` (owner) | phát callback event nội bộ |
| Giao tiếp | HTTPS → Hub API; nhận webhook | HTTPS ↔ merchant & bank; owner Kafka | HTTPS ← Hub; callback → Hub |
| Chaos | trang giả lập lỗi webhook | — | Chaos engine giả lập lỗi bank |

> Việc Payment Hub tách thành mấy service (không phải 1) là chủ đề của
> [03-system-design.md](03-system-design.md) — ở đó ta bàn bounded context & lý do chia.

---

## 8. Những gì **chưa cần** ở phiên bản đầu (và vì sao)

- **Multi-AZ / HA thật** — học được ít, tốn gấp đôi. Single-AZ, chấp nhận downtime khi học.
- **Auto-scaling** — tải học không cần. Cấu hình desired-count cố định nhỏ.
- **Blue/green, canary deploy** — hay nhưng để sprint CI/CD nâng cao.
- **Service mesh (App Mesh/Istio)** — quá sức & không cần với 3 hệ thống.
- **EKS/MSK/WAF/CloudFront** — upgrade path, không phải MVP.
- **Cross-region** — không liên quan mục tiêu học.

---

## 9. Tiếp theo

- Từng flow chạy chi tiết (success/fail/timeout/duplicate/refund/recon…) →
  [02-business-flows.md](02-business-flows.md)
- Chia service, DB schema, state machine, outbox/saga → [03-system-design.md](03-system-design.md)
- Sprint nào dựng phần hạ tầng nào → [06-roadmap.md](06-roadmap.md)
