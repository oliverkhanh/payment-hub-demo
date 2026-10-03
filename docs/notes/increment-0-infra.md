# Ghi chú kỹ thuật — Docker & Kafka (từ Increment 0)

> Kiến thức hạ tầng nền tảng, dùng lại suốt dự án. Tra nhanh khi cần.

## 1. Docker Volume — giữ dữ liệu & đưa file vào container

Container "phù du": xóa là mất mọi thứ bên trong. Cú pháp mount: **`NGUỒN:ĐÍCH`**
(trái = ngoài container, phải = đường dẫn trong container).

| | **Named volume** | **Bind mount** |
|---|---|---|
| Ví dụ | `postgres-data:/var/lib/postgresql/data` | `./postgres/init:/docker-entrypoint-initdb.d` |
| Nguồn | Vùng lưu trữ Docker tự quản (khai ở `volumes:` cuối file) | Thư mục thật trong dự án (`./` = cạnh compose) |
| Mục đích | **Lưu dữ liệu bền** (sống lâu hơn container) | **Đưa file host vào** (script, config) |
| Sửa từ host | Không trực tiếp | Có |

Quy tắc: giữ dữ liệu → named volume; nhét file vào → bind mount.

## 2. `.env` → biến môi trường

`.env` KHÔNG phải script. Docker Compose **tự đọc** file tên đúng `.env` ở **thư mục chạy lệnh**,
rồi **thay `${...}` trong compose** (trên host, lúc parse).

```
.env: IDENTITY_DB_PASSWORD=identity_pass
  → compose: SPRING_DATASOURCE_PASSWORD: ${IDENTITY_DB_PASSWORD}
  → container env: SPRING_DATASOURCE_PASSWORD=identity_pass
  → Spring: ${SPRING_DATASOURCE_PASSWORD:default} → spring.datasource.password
```

- Auto-load nếu: tên đúng `.env` + ở thư mục chạy lệnh. Khác tên/chỗ → `--env-file path`.
- Kiểm tra: `docker compose config` (in ra bản đã thay biến).
- **2 loại `${}`:** trong compose = Compose xử lý (từ `.env`); trong application.yml = Spring xử lý (từ env var, có default).
- Đừng nhầm top-level `.env` (substitute trong compose) với `env_file:` trong service (nạp biến vào container).

## 3. Init script Postgres — chạy khi nào

Postgres tự chạy file trong `/docker-entrypoint-initdb.d/` **MỘT LẦN, lúc data directory rỗng
(khởi tạo lần đầu)**. Ta đưa vào bằng bind mount. Chạy theo thứ tự alphabet (`01-`, `02-`), quyền superuser.

- Việc: tạo **DATABASE + USER** (khác Flyway — Flyway tạo **TABLE** bên trong DB).
- **Bẫy:** dữ liệu persist trong named volume → lần sau data không rỗng → init **không chạy lại**.
  Muốn chạy lại: `docker compose down -v` (xóa volume) → up.

## 4. Kafka: 3 tầng config

| Tầng | Cấu hình gì | Ở đâu | Increment |
|---|---|---|---|
| **Broker (server)** | listener, quorum, replication topic nội bộ | docker-compose | 0 |
| **Client (producer/consumer)** | acks, retries, poll interval, offset reset | application.yml của app | 5 |
| **Topic** | partition, replication, retention | lúc tạo topic | 5 |

→ File compose chỉ dựng **broker**. Producer/consumer + retry/DLQ = phía app (Inc 5).

## 5. KRaft vs ZooKeeper

| | ZooKeeper (cũ) | **KRaft** |
|---|---|---|
| Metadata lưu ở | ZooKeeper (hệ riêng) | Trong chính Kafka (metadata log, Raft) |
| Config đặc trưng | `zookeeper.connect` | `process.roles`, `controller.quorum.voters`, `controller.listener.names` |
| Số hệ chạy | Kafka + ZooKeeper | Chỉ Kafka |

**Các env KRaft chính (trong compose):**
- `KAFKA_NODE_ID` — id node (cũ: `broker.id`).
- `KAFKA_PROCESS_ROLES: broker,controller` — node đóng vai gì (kiêm cả hai = dev).
- `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093` — danh sách controller (Raft), thay `zookeeper.connect`.
- `KAFKA_LISTENERS` — PLAINTEXT (client, 9092) + CONTROLLER (Raft, 9093).
- `KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092` — địa chỉ báo cho client (→ app nối `kafka:9092`).
- `KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER` — listener nào cho controller.
- `*_REPLICATION_FACTOR: 1`, `MIN_ISR: 1` — vì chỉ 1 broker (production = 3).

## Lệnh hay dùng
```bash
cd infra && cp .env.example .env && docker compose up --build   # lên cụm
docker compose ps                    # trạng thái + health
docker compose config                # xem compose sau khi thay biến
docker compose down                  # tắt, giữ dữ liệu
docker compose down -v               # tắt + xóa volume (reset DB, init chạy lại)
```
