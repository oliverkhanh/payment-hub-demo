# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# lombok.config PHẢI vào image: thiếu nó thì lombok.copyableAnnotations không chạy,
# @Value trên field không được chép sang tham số constructor -> app chết lúc khởi động.
# (Test ở máy vẫn xanh vì ở máy có file này — đúng kiểu lỗi "chạy máy tôi thì được".)
COPY lombok.config .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=40s \
    CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
