#!/bin/sh
set -e

# Configuration: OOPS_CONFIG overrides the default mount point.
CONFIG_PATH="${OOPS_CONFIG:-/app/config/oops.yaml}"
if [ ! -f "$CONFIG_PATH" ]; then
  echo "no configuration at ${CONFIG_PATH}; mount oops.yaml there or set OOPS_CONFIG" >&2
  exit 1
fi

/app/oops --config "$CONFIG_PATH" --port "${BACKEND_PORT:-8080}" &
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
