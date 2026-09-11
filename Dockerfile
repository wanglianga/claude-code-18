# syntax=docker/dockerfile:1

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 先单独拷贝 pom，利用 Docker 层缓存加速依赖下载
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# 创建非 root 用户与日志目录
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/logs && chown -R app:app /app

COPY --from=build /build/target/agrimach.jar /app/app.jar
RUN chown app:app /app/app.jar

USER app

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
