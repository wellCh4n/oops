# OOPS Go Server

The Go rewrite of the OOPS backend. It serves the same `/api` and `/openapi`
surfaces with wire-compatible responses, shares the MySQL schema (Flyway
migrations stay owned by the Java side until final cut-over), reads the same
`config/application.yml`, and signs/verifies the same HS256 JWTs — so the
Next.js frontend and the repo-vendored CLI work unchanged, and nginx can run
both backends side by side during migration.

## Stack

- **Gin** for HTTP; **gorilla/websocket** for the four WebSocket protocols
- `database/sql` + go-sql-driver (hand-written SQL; a GORM refactor of the
  store layer is possible later without touching handlers)
- **client-go** (+ dynamic client for Traefik CRDs, metrics client for
  metrics-server) for everything Kubernetes
- **go-git** for remote branch listing, **minio-go** for S3 presign
- stdlib AES-256-GCM in `internal/crypto`, byte-compatible with the Java
  `EncryptionUtils` (verified cross-decrypting live)

## Layout

Idiomatic Go layering (package-per-concern) rather than a copy of the Java
four-layer DDD tree; the ports idea survives in that handlers reach
infrastructure only through `store` / `k8s` / `objectstorage` / `feishu`
exported APIs:

```
cmd/oops/                entrypoint (flags: -config, -listen)
cmd/devtool/             local verification scratch tool (ws probe, pod seed…)
internal/config/         shared application.yml loader, JDBC→DSN translation
internal/httpapi/        Gin server: /api + /openapi, WebSockets, SSE
internal/store/          MySQL adapter (entities, JSON-blob configs, pipelines)
internal/engine/         pipeline engine: build Jobs, deploy chain, scan loops,
                         scheduled restarts, resource alerts, notifications
internal/k8s/            cluster gateways: nodes, status, configmaps, pod fs,
                         sandbox, IDE, resources, metrics history (PromQL proxy)
internal/objectstorage/  S3 presign for ZIP sources and static assets
internal/feishu/         Lark OAuth + interactive-card messages (raw HTTP)
internal/crypto/         AES-GCM codec for environment secrets
internal/cron/           5-field cron evaluation (restart schedules, /api/cron/next)
```

## Run

```bash
go build -o oops-server ./cmd/oops
./oops-server -config ../config/application.yml -listen :8081
```

`.claude/launch.json` has a `web-go` configuration that runs the frontend dev
server against this backend.

## Coverage

Everything the Java backend exposes is ported. Verified live against the
running Java backend, a real OrbStack cluster, GitHub, and a local MinIO:

- **Auth**: login (BCrypt), JWTs accepted by both backends, cookie/`?token=`
  fallbacks, `/openapi` access-token auth (`sk-oops-…`), hidden endpoints → 405,
  Feishu OAuth (flow implemented; disabled-config parity verified)
- **CRUD**: applications + all five per-app configs (basic-auth hashing and
  blank-password carry-forward included), users, namespaces, environments
  (secrets encrypted at rest, cross-decryptable with Java), domains (PEM
  parsing, host matching, in-use rebinding guard)
- **Kubernetes reads**: nodes, pod status + SSE watch, events, live metrics,
  metrics history (`query_range` via the API-server service proxy), resource
  viewer, current image, ConfigMap editor (annotation metadata round trip
  byte-identical)
- **Pipeline engine**: deploy trigger (GIT + ZIP presign), build Job with the
  fetch/dockerfile/compile/publish init chain, optimistic-lock state machine,
  5s scan loops, rollout health (fatal reasons + 5-minute timeout), manual
  deploy, stop (Job suspend), rollback — full SUCCEEDED and ERROR cycles
  exercised on the real cluster
- **Deploy chain**: namespace, image pull secret, priority class, StatefulSet
  (probes, env-from, file mounts, expert config), Service, Traefik
  IngressRoutes (redirect + basic auth middlewares, TLS secret sync)
- **WebSockets/SSE**: pod log (tail 2000 + ping/pong), pod terminal (TTY),
  pipeline log (steps/step/done protocol), sandbox terminal, status watch SSE,
  sandbox execution SSE
- **Sandbox**: ephemeral Jobs (blocking + streaming), persistent instances
  (alpine-mate builtin + custom), exec with exit codes, files, terminal
- **Pod filesystem**: list/read/write/upload/download/delete/rename/mkdir via
  exec, with the Java scripts verbatim
- **IDE**: code-server StatefulSet + Service + IngressRoute with rollback,
  ide-config ConfigMap auto-creation, list/delete
- **Object storage**: ZIP source presign, asset browser
  (list/upload-url/delete), public + signed URLs — verified against MinIO
- **Schedulers**: pipeline scan, scheduled rolling restarts (cron-matched,
  `kubectl.kubernetes.io/restartedAt`), resource alert scan (edge-triggered
  `application_alert_state`, min_over_time probes; opt-in)
- **Notifications**: pipeline transition + alert cards to the operator's
  linked Feishu account (dormant until `oops.feishu.enabled`)

Full-frontend regression: every page (apps, detail tabs, status, pipelines,
sandboxes, assets, environments, domains, nodes, users, namespaces) served by
this backend with zero non-200 API responses.

## Known gap

The Feishu **inbound event long connection** (`contact.user.deleted_v3` →
account deactivation) uses the Lark SDK's proprietary WebSocket framing and is
not ported; resignation sync stays on the Java side (or off) until that
protocol is implemented.

## Measurements (same machine, 2026-08-27)

| | Java backend | Go server |
|---|---|---|
| Physical footprint under load | 719 MB | 17 MB |
