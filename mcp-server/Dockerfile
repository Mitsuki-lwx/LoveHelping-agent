# ============================================================
# mcp-server 工具面独立进程 Dockerfile（V1 发布，2026-09-07）
# 构建上下文 = 仓库根（context: .），独立 maven 子工程。
# 主应用经 MCP_SERVER_URL=http://mcp-server:8125/mcp 调用（Streamable HTTP）。
# ============================================================

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY mcp-server/pom.xml ./mcp-server/pom.xml
RUN mvn -q -B -f mcp-server/pom.xml dependency:go-offline -DskipTests || true

COPY mcp-server/src ./mcp-server/src
RUN mvn -q -B -f mcp-server/pom.xml package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd -r -u 1002 mcpuser

COPY --from=build /build/mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar /app/mcp-server.jar

USER mcpuser

ENV JAVA_OPTS="-Xmx512m -Xms128m"

EXPOSE 8125

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/mcp-server.jar --server.port=8125"]
