# syntax=docker/dockerfile:1

# --- 阶段 1: 前端构建 ---
FROM node:20-alpine AS web-deps
WORKDIR /build/web
RUN corepack enable && corepack prepare pnpm@9.0.0 --activate
RUN pnpm config set registry https://registry.npmmirror.com

COPY web/package.json web/pnpm-lock.yaml ./
RUN --mount=type=cache,id=pnpm,target=/root/.local/share/pnpm/store \
    pnpm install --no-frozen-lockfile
COPY web/ ./
RUN pnpm build

# --- 阶段 2: 后端构建 (GraalVM Native Image) ---
FROM ghcr.io/graalvm/native-image-community:25 AS backend-builder
WORKDIR /build/backend

# 安装 Maven (基础镜像是 Oracle Linux 9, 自带 microdnf)
RUN microdnf install -y maven && microdnf clean all

# 强力手段：直接覆盖 Maven 默认的 settings.xml 为阿里云源
RUN mkdir -p /root/.m2 && echo '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" \
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
</settings>' > /root/.m2/settings.xml

COPY pom.xml ./

# 预下载依赖
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn dependency:go-offline -B

COPY src ./src

# Native Image 编译 (12-16GB 内存, 6-12 分钟; QEMU 模拟下 30-90 分钟)
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -Pnative -DskipTests native:compile

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

COPY --from=backend-builder /build/backend/target/oops /app/oops
RUN chmod +x /app/oops

COPY --from=web-deps /build/web/.next/standalone /app/web
COPY --from=web-deps /build/web/.next/static /app/web/.next/static
COPY --from=web-deps /build/web/public /app/web/public

COPY docker/nginx.conf /etc/nginx/conf.d/app.conf
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 80
CMD ["/entrypoint.sh"]