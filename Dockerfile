FROM node:20-alpine AS web-deps
WORKDIR /build/web
COPY web/package.json web/pnpm-lock.yaml ./
RUN corepack enable && corepack prepare pnpm@latest --activate && pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

FROM maven:3.9-eclipse-temurin-21 AS backend-builder
WORKDIR /build/backend
COPY pom.xml ./
COPY src ./src
RUN mvn -DskipTests package

FROM node:20-slim AS runtime
ENV NODE_ENV=production \
    NEXT_PUBLIC_API_URL=/api \
    FRONTEND_PORT=3000 \
    BACKEND_PORT=8080

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates nginx apache2-utils \
    && rm -f /etc/nginx/sites-enabled/default \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN mkdir -p /app/config
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=backend-builder /opt/java/openjdk /opt/java/openjdk

COPY --from=backend-builder /build/backend/target/oops-0.0.1-SNAPSHOT.jar /app/oops.jar

RUN mkdir -p /app/web
COPY --from=web-deps /build/web/.next/standalone /app/web
COPY --from=web-deps /build/web/.next/static /app/web/.next/static
COPY --from=web-deps /build/web/public /app/web/public

COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
COPY docker/nginx.conf /etc/nginx/conf.d/app.conf

EXPOSE 80
CMD ["/entrypoint.sh"]
