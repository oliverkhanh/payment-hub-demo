# ---- Build stage: build jar bằng Maven trong container (không cần Maven trên máy) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Cache lớp dependency: copy pom trước, tải offline, rồi mới copy src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Run stage: chỉ JRE + jar cho nhẹ ----
FROM eclipse-temurin:21-jre
WORKDIR /app
# curl để container healthcheck gọi /actuator/health
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=40s \
    CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
