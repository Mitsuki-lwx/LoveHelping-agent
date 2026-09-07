# ============================================================
# lwx-ai-agent 主应用 Dockerfile（V1 发布，2026-09-07）
# 多阶段：maven 构建 → JRE 运行。
# 前端产物已编译进 src/main/resources/static（入库），构建无需 node。
# 运行配置全部来自环境变量（profile=prod，见 docker-compose.yml / .env.example）
# ============================================================

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先拷贝 pom 以利用依赖缓存层
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline -DskipTests || true

# 拷贝源码构建（跳过测试——测试在 CI/发布流水线单独执行）
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# 非 root 运行
RUN useradd -r -u 1001 appuser

COPY --from=build /build/target/lwx-ai-agent-0.0.1-SNAPSHOT.jar /app/app.jar

USER appuser

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-Xmx768m -Xms256m"

EXPOSE 8088

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
