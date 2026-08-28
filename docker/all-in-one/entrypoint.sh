#!/bin/sh
set -e

# OOPS_CONFIG overrides the default mount point of the shared application.yml.
/app/oops -config "${OOPS_CONFIG:-/app/config/application.yml}" -listen ":${BACKEND_PORT:-8080}" &
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
