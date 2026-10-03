# Infra — chạy cụm local (Increment 0)

## Yêu cầu
- Docker + Docker Compose

## Chuẩn bị (một lần) — sinh keystore RSA cho IdP (Increment 1+)
Keystore ký JWT là secret dev, **gitignored**, phải sinh 1 lần:
```bash
keytool -genkeypair -alias idp-signing -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore ../identity-provider/backend/src/main/resources/keys/idp-signing.p12 \
  -storepass changeit -dname "CN=payment-hub-idp" -validity 3650
```
(Mật khẩu phải khớp `IDP_KEYSTORE_PASSWORD` trong `.env`.)

## Chạy
```bash
cd infra
cp .env.example .env          # tạo file .env (đã gitignore)
docker compose up --build     # build 4 backend + lên Postgres/Redis/Kafka
```
Lần đầu sẽ lâu (build 4 image Maven). Các lần sau nhanh hơn nhờ cache.

## Kiểm tra (Definition of Done)
Khi tất cả đã `healthy`, gọi health từng service:
```bash
curl -s localhost:9000/actuator/health   # identity-provider
curl -s localhost:8081/actuator/health   # merchant-demo
curl -s localhost:8080/actuator/health   # payment-hub
curl -s localhost:8082/actuator/health   # fake-bank
```
Mỗi cái trả `{"status":"UP",...}` (bao gồm cả check DB) là đạt.

Kiểm tra Flyway đã migrate (mỗi service 1 database riêng):
```bash
docker exec -it payment-hub-postgres-1 psql -U postgres -d bank_db -c "select * from app_info;"
```

## Cổng
| Service | Host port | DB (trong Postgres) |
|---|---|---|
| identity-provider | 9000 | identity_db |
| merchant-demo | 8081 | merchant_db |
| payment-hub | 8080 | hub_db |
| fake-bank | 8082 | bank_db |
| Postgres | 5432 | (4 database) |
| Redis | 6379 | — |
| Kafka | 9092 | — |

## Dừng
```bash
docker compose down            # dừng, giữ dữ liệu
docker compose down -v         # dừng + xóa volume (reset sạch DB)
```
