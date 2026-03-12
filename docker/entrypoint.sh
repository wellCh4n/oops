#!/bin/sh
set -e

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:+ExitOnOutOfMemoryError"

if [ -n "${BASIC_AUTH_USER:-}" ] || [ -n "${BASIC_AUTH_PASS:-}" ]; then
  if [ -z "${BASIC_AUTH_USER:-}" ] || [ -z "${BASIC_AUTH_PASS:-}" ]; then
    echo "BASIC_AUTH_USER 和 BASIC_AUTH_PASS 必须同时设置" >&2
    exit 1
  fi
  if ! command -v htpasswd >/dev/null 2>&1; then
    echo "找不到 htpasswd，请在镜像中安装 apache2-utils" >&2
    exit 1
  fi
  mkdir -p /etc/nginx
  printf "%s\n" "${BASIC_AUTH_PASS}" | htpasswd -ic /etc/nginx/.htpasswd "${BASIC_AUTH_USER}"
  # 调整属组与权限，确保 Nginx 进程可读
  if getent group www-data >/dev/null 2>&1; then
    chgrp www-data /etc/nginx/.htpasswd || true
    chmod 640 /etc/nginx/.htpasswd || true
  elif getent group nginx >/dev/null 2>&1; then
    chgrp nginx /etc/nginx/.htpasswd || true
    chmod 640 /etc/nginx/.htpasswd || true
  else
    chmod 644 /etc/nginx/.htpasswd || true
  fi
fi

CONFIG_ARGS=""
if [ -n "${SPRING_CONFIG_LOCATION:-}" ]; then
  CONFIG_ARGS="--spring.config.location=${SPRING_CONFIG_LOCATION}"
elif [ -f "/app/config/application.properties" ] || [ -f "/app/application.properties" ]; then
  CONFIG_ARGS="--spring.config.additional-location=file:/app/config/,file:/app/"
fi

java -jar /app/oops.jar ${CONFIG_ARGS} --server.port=${BACKEND_PORT:-8080} &
BACK_PID=$!

cd /app/web
if [ ! -f "server.js" ]; then
  kill "$BACK_PID" 2>/dev/null || true
  exit 1
fi
PORT=${FRONTEND_PORT:-3000} HOSTNAME=0.0.0.0 node /app/web/server.js &
FRONT_PID=$!

nginx -g "daemon off;" &
NGINX_PID=$!

term() {
  kill "$BACK_PID" "$FRONT_PID" "$NGINX_PID" 2>/dev/null || true
}
trap term INT TERM

while kill -0 "$BACK_PID" 2>/dev/null && kill -0 "$FRONT_PID" 2>/dev/null && kill -0 "$NGINX_PID" 2>/dev/null; do
  sleep 1
done

if ! kill -0 "$BACK_PID" 2>/dev/null; then
  kill "$FRONT_PID" "$NGINX_PID" 2>/dev/null || true
  wait "$FRONT_PID" 2>/dev/null || true
  wait "$NGINX_PID" 2>/dev/null || true
  exit 1
fi

if ! kill -0 "$FRONT_PID" 2>/dev/null; then
  kill "$BACK_PID" "$NGINX_PID" 2>/dev/null || true
  wait "$BACK_PID" 2>/dev/null || true
  wait "$NGINX_PID" 2>/dev/null || true
  exit 1
fi

if ! kill -0 "$NGINX_PID" 2>/dev/null; then
  kill "$BACK_PID" "$FRONT_PID" 2>/dev/null || true
  wait "$BACK_PID" 2>/dev/null || true
  wait "$FRONT_PID" 2>/dev/null || true
  exit 1
fi
