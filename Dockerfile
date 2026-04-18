# syntax=docker/dockerfile:1

# --- 阶段 1: 前端构建 ---
FROM node:20-alpine AS web-deps
WORKDIR /build/web
RUN corepack enable && corepack prepare pnpm@9.0.0 --activate
RUN pnpm config set registry https://registry.npmmirror.com

COPY web/package.json web/pnpm-lock.yaml ./
RUN --mount=type=cache,id=pnpm,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

# --- 阶段 2: 后端构建 (修正 Maven 镜像源) ---
FROM maven:3.9-eclipse-temurin-21 AS backend-builder
WORKDIR /build/backend

# 强力手段：直接覆盖 Maven 默认的 settings.xml 为阿里云源
RUN echo '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" \
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
  xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd"> \
  <mirrors> \
    <mirror> \
      <id>aliyunmaven</id> \
      <mirrorOf>central</mirrorOf> \
      <name>Aliyun Maven</name> \
      <url>https://maven.aliyun.com/repository/public</url> \
    </mirror> \
  </mirrors> \
</settings>' > /usr/share/maven/conf/settings.xml

COPY pom.xml ./

# 预下载依赖
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -DskipTests package

# --- 阶段 3: 最终运行镜像 ---
FROM node:20-slim AS runtime
ENV NODE_ENV=production \
    NEXT_PUBLIC_API_URL=/api \
    FRONTEND_PORT=3000 \
    BACKEND_PORT=8080 \
    TZ=Asia/Shanghai

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates nginx \
    && rm -f /etc/nginx/sites-enabled/default \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN mkdir -p /app/config /app/web

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=backend-builder /opt/java/openjdk /opt/java/openjdk
COPY --from=backend-builder /build/backend/target/oops-0.0.1-SNAPSHOT.jar /app/oops.jar

COPY --from=web-deps /build/web/.next/standalone /app/web
COPY --from=web-deps /build/web/.next/static /app/web/.next/static
COPY --from=web-deps /build/web/public /app/web/public

COPY docker/nginx.conf /etc/nginx/conf.d/app.conf
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 80
CMD ["/entrypoint.sh"]