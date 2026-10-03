-- Increment 0: migration khởi tạo, chứng minh Flyway chạy. CHƯA có nghiệp vụ.
CREATE TABLE app_info (
    id             BIGSERIAL PRIMARY KEY,
    service_name   VARCHAR(64)  NOT NULL,
    version        VARCHAR(32)  NOT NULL,
    initialized_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO app_info (service_name, version) VALUES ('payment-hub', '0.0.1');
