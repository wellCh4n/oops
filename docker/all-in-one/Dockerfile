# syntax=docker/dockerfile:1

# --- Stage 1: frontend build ---
FROM node:22-slim AS web-deps
WORKDIR /build/web
RUN corepack enable && corepack prepare pnpm@11.8.0 --activate
RUN pnpm config set registry https://registry.npmmirror.com

COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml ./
RUN --mount=type=cache,id=pnpm,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

# --- Stage 2: backend build ---
FROM maven:3.9-eclipse-temurin-25 AS backend-builder
WORKDIR /build/backend

# Build through the Aliyun mirror for speed, but keep central as a fallback: the mirror
# syncs from central on demand, so a newly published artifact can still 404 there.
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
  <profiles> \
    <profile> \
      <id>fallback-central</id> \
      <repositories> \
        <repository> \
          <id>maven-central-fallback</id> \
          <url>https://repo1.maven.org/maven2</url> \
        </repository> \
      </repositories> \
    </profile> \
  </profiles> \
  <activeProfiles><activeProfile>fallback-central</activeProfile></activeProfiles> \
</settings>' > /usr/share/maven/conf/settings.xml

COPY pom.xml ./

# Prefetch dependencies. -U re-checks cached resolution failures: /root/.m2 is a persistent
# buildkit cache, so one 404 from the mirror sticks as "artifact absent" for every later build.
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B -U

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -DskipTests package

# --- Stage 3: runtime ---
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

COPY docker/all-in-one/nginx.conf /etc/nginx/conf.d/app.conf
COPY docker/all-in-one/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 80
CMD ["/entrypoint.sh"]
