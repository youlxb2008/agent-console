# Agent Console 后端 Dockerfile
# 多阶段构建：Maven 编译 → JRE 运行

# 阶段1：编译
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY settings.xml .
# 先下载依赖（利用 Docker 缓存层）
RUN mvn dependency:go-offline -s settings.xml -q
COPY src ./src
RUN mvn package -s settings.xml -DskipTests -q

# 阶段2：运行
FROM eclipse-temurin:17-jre
WORKDIR /app

# 创建非 root 用户
RUN groupadd -r appuser && useradd -r -g appuser appuser

COPY --from=builder /app/target/app.jar /app/app.jar

# 数据目录（H2 文件数据库）
RUN mkdir -p /app/data && chown -R appuser:appuser /app

USER appuser

EXPOSE 8000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
