-- Chạy MỘT LẦN khi container Postgres khởi tạo (docker-entrypoint-initdb.d).
-- DB-per-service + USER-per-service: mỗi service 1 database + 1 user riêng,
-- user chỉ sở hữu database của nó → Postgres không cho query xuyên database
-- => ép cứng luật "service không đụng dữ liệu service khác".
--
-- LƯU Ý: mật khẩu ở đây là DEV-ONLY và phải KHỚP với .env (biến *_DB_PASSWORD).
-- Production: dùng AWS Secrets Manager, KHÔNG hardcode.

CREATE USER identity_user WITH PASSWORD 'identity_pass';
CREATE DATABASE identity_db OWNER identity_user;

CREATE USER merchant_user WITH PASSWORD 'merchant_pass';
CREATE DATABASE merchant_db OWNER merchant_user;

CREATE USER hub_user WITH PASSWORD 'hub_pass';
CREATE DATABASE hub_db OWNER hub_user;

CREATE USER bank_user WITH PASSWORD 'bank_pass';
CREATE DATABASE bank_db OWNER bank_user;
