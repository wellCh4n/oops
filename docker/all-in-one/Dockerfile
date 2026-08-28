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
FROM golang:1.27-alpine AS backend-builder
WORKDIR /build/backend

# Pull modules through the goproxy.cn mirror for speed; "direct" keeps a
# fallback for anything the mirror cannot serve.
ENV GOPROXY=https://goproxy.cn,direct

COPY server/go.mod server/go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download

COPY server/ ./
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /oops ./cmd/oops

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

COPY --from=backend-builder /oops /app/oops

COPY --from=web-deps /build/web/.next/standalone /app/web
COPY --from=web-deps /build/web/.next/static /app/web/.next/static
COPY --from=web-deps /build/web/public /app/web/public

COPY docker/all-in-one/nginx.conf /etc/nginx/conf.d/app.conf
COPY docker/all-in-one/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 80
CMD ["/entrypoint.sh"]
