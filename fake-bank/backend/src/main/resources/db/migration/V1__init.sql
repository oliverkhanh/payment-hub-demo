-- Increment 0: migration khởi tạo, chứng minh Flyway chạy. CHƯA có nghiệp vụ.
-- Bảng marker trung tính để verify migration + kết nối DB.
CREATE TABLE app_info (
    id             BIGSERIAL PRIMARY KEY,
    service_name   VARCHAR(64)  NOT NULL,
    version        VARCHAR(32)  NOT NULL,
    initialized_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO app_info (service_name, version) VALUES ('fake-bank', '0.0.1');
