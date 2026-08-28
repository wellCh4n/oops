# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Project Overview

OOPS is a Kubernetes-based PaaS (Platform as a Service) for deploying applications. It provides a web UI for managing applications, environments, pipelines (CI/CD builds), sandboxes, and IDE instances backed by code-server. The backend is written in Go (it replaced an earlier Java/Spring Boot implementation; the wire protocol and database schema carry some of that heritage, e.g. `spring.datasource` config keys).

## Architecture

### Backend (Go)

**Go version:** 1.27 · **Module:** `github.com/wellch4n/oops/server` (lives in `server/`)

Mainstream Go layout — one noun per topic, reads and writes together, no repository interfaces or DDD ceremony. Dependency direction is enforced by imports: `httpapi → engine/store/k8s → domain`.

- **`cmd/oops/`** — entrypoint: flags (`-config`, `-listen`, `-migrate-down`), config load, store open + migrations, server + engine start.
- **`cmd/devtool/`** — local verification scratch tool (not shipped).
- **`internal/domain/`** — rules shared by several packages, nothing else: pipeline status state machine (`EnsurePipelineTransition`, `ActivePipelineStatuses`), `BizError`/`Bizf`, resource/environment/host name validation, `NewID` (24-char NanoId).
- **`internal/httpapi/`** — Gin handlers, one file per resource (`user.go`, `application.go`, `environment.go`, ...), `routes.go` is the single grouped route table, `server.go` wires dependencies. WebSocket handlers in `websocket.go`/`pipeline_log.go` (gorilla/websocket).
- **`internal/store/`** — GORM persistence, one file per topic (`user.go`, `application.go`, `appconfig.go`, `pipeline.go`, `environment.go`, `domain.go`, ...). `orm.go` has the generic `JSONField[T]` valuer/scanner for JSON-blob columns (preserves null-vs-`[]`). `migrate.go` + `migrations/` run goose migrations at startup. `localtime.go`'s `LocalDateTime` renders Java-compatible timestamps (local time, no zone).
- **`internal/engine/`** — pipeline lifecycle: `buildjob.go` (build Job assembly), `scan.go` (5s scan loops, optimistic-lock state transitions, `evaluateRollout` health verdict), `deploy*.go` (processor chain: namespace → pull secret → StatefulSet → Service → IngressRoute), `restartjob.go`, `alerts.go`, `migration.go`, `notify.go`.
- **`internal/k8s/`** — client-go glue: `client.go` (`NewCluster`, kubeconfig fallback when the environment URL is empty), `appruntime.go`, `configmap.go`, `podfs.go`, `sandbox*.go`, `ide.go` (embeds `ide-default-config.json`), `metricshistory.go` (Prometheus via API-server service proxy), Traefik CRDs via the dynamic client (`traefik.io/v1alpha1`, server-side apply, FieldManager "oops").
- **`internal/config/`** — the full `oops.*`/`spring.datasource` config tree from `application.yml`.
- **`internal/crypto/`** — AES-256-GCM codec for environment secrets (key = SHA-256 of `oops.crypto.secret-key`).
- **`internal/feishu/`** — Feishu OAuth + interactive-card messages, and the larkws long-connection event client (user-deactivation sync).
- **`internal/objectstorage/`** — MinIO S3 client for ZIP source uploads and static assets.
- **`internal/cron/`** — 5-field cron parsing/next-run for scheduled restarts.

Only two interfaces exist, both consumer-defined and small: `engine.Notifier` and `store.Codec`. Logging is `log/slog` with structured key-value fields.

**Multi-host support**: the environment table stores per-cluster API server credentials (encrypted at rest), so one OOPS manages multiple clusters.

### Frontend (Next.js)

**Next.js:** 16.2.12 · **React:** 19.2.3 · **Package manager:** pnpm

- `web/app/`: App Router pages · `web/components/` (+ `components/ui/` shadcn-style Radix components)
- `web/lib/`: utilities, i18n, API clients · `web/store/`: Zustand stores · `web/contexts/`: work-context provider · `web/locales/`: `zh-CN`, `en-US`, `zh-TW`, `ja-JP`

Key technologies: Tailwind CSS v4, Radix UI, Zustand, Monaco Editor, XTerm.js, React Hook Form + Zod.

## Development Commands

### Backend

```bash
cd server

# Run (requires config/application.yml — cp from config/application.yml.example at repo root)
go run ./cmd/oops -config ../config/application.yml -listen :8080

# Build / test / vet
go build ./...
go test ./...
go vet ./...

# Single test
go test ./internal/engine/ -run TestEvaluateRollout
```

### Frontend

```bash
cd web
pnpm install
pnpm dev     # localhost:3000; API_BASE_URL defaults to localhost:8080
pnpm build
pnpm lint
```

### Docker Build

Compose/runtime layouts under `docker/` (backend image builds the Go binary in `golang:1.27-alpine`, runs on `alpine`):

- Prebuilt stack: `docker compose -f docker/docker-compose.yml up -d` (mysql + ghcr images + nginx on :8080)
- Local build: `docker compose -f docker/docker-compose.build.yml up -d`
- Local build, external MySQL: `docker compose -f docker/docker-compose.local.yml up -d`
- All-in-one image: `docker build -f docker/all-in-one/Dockerfile -t oops .` (nginx :80 → Go :8080 + Next.js :3000; mount `application.yml` at `/app/config/application.yml` or set `OOPS_CONFIG`)

## Configuration

Single `application.yml` for everything. Templates: `config/application.yml.example` (local dev, cp to `config/application.yml`) and `docker/application.yml.example` (compose stacks, cp to `docker/application.yml`, bind-mounted to `/app/config/application.yml`).

Key properties: `datasource.*` (MySQL — `url` accepts a native Go DSN or a Spring JDBC URL; the Java-era `spring.datasource.*` block still works as a fallback), `oops.admin.password`, `oops.jwt.secret` (min 32 chars), `oops.crypto.secret-key`, `oops.pipeline.image.*` (clone/buildah/zip images, registry mirrors, unzip excludes), `oops.ingress.cert-resolver`, `oops.object-storage.*`, `oops.feishu.*` (+ `sync-user-deactivation` for the inbound long connection), `oops.ide.*`, `oops.sandbox.*`, `oops.pod-filesystem.*`, `oops.metrics.history.*` (Prometheus-compatible backend location; blank namespace hides charts), `oops.metrics.alert.*` (global opt-in resource alerts).

Default admin: `admin` / `oops.admin.password` (default `admin123`), seeded on first startup.

## Database Migrations

goose migrations embedded in the binary (`server/internal/store/migrations/`), run automatically at startup; state lives in `goose_db_version`.

- `00001_baseline_schema.sql` is the full schema (dumped at the former Java Flyway V21) with `CREATE TABLE IF NOT EXISTS`, so it builds a fresh database and no-ops on one Flyway already built.
- New migrations are append-only `0000N_description.sql` files with `-- +goose Up` (and a `-- +goose Down` when a rollback path is feasible).
- **Upgrade guard**: a database whose `flyway_schema_history` stopped before V21 is refused at startup — such installations must upgrade through the latest 2.x release first.
- `oops -migrate-down` rolls back the most recent migration and exits (the release-rollback escape hatch).

## Key Concepts

### Environments
An Environment is a Kubernetes cluster: API server URL + token (encrypted), work namespace and optional build storage class, image registry credentials, optional Git credentials (synced into the work namespace as the `git-credential` Secret). Environments are referenced **by name** everywhere, and the reference is uniformly called `environment` — in DB columns, JSON blobs, and query parameters alike. Nothing outside the environment table knows environment ids.

### Pipelines
Builds run as Kubernetes Jobs with init containers: **fetch** (git clone or ZIP download via curl), optional **dockerfile** (inline USER content), optional **compile**, **publish** (Buildah build+push). Source types `GIT` and `ZIP` (presigned S3 PUT via `POST .../deployments/source-upload`).

Logs stream over WebSocket. Scan loops in `engine/scan.go` (5s) poll Jobs and StatefulSet rollout health; state transitions are optimistic-locked conditional UPDATEs (`UpdatePipelineStatusIfMatch`, rows-affected = won the race). Statuses: `INITIALIZED → RUNNING → (BUILD_SUCCEEDED) → DEPLOYING → ROLLING_OUT → SUCCEEDED / ERROR / STOPPED`.

- **Duplicate deploy guard**: an in-flight pipeline (`RUNNING`/`DEPLOYING`/`ROLLING_OUT`) blocks new deploys of the same application.
- **Deploy modes**: `IMMEDIATE` (default) deploys after build; `MANUAL` stops at `BUILD_SUCCEEDED` until an explicit deploy call.
- **Rollback**: `POST .../pipelines/{id}/rollback` creates a `triggerType=ROLLBACK` pipeline reusing the historic artifact, skipping the build.
- **Notifications**: state transitions notify the operator via Feishu interactive cards (`engine/notify.go` → `httpapi/notifier.go`): colored header by level, two-column fact grid, artifact/detail sections.
- **"all" scope**: pipeline listing accepts namespace `all` (spans namespaces) and environment `""`/`all` (no filter).

### Application Deployment
Applications deploy as **StatefulSets** (`enableServiceLinks: false`) with Traefik **IngressRoute CRDs** (skipped gracefully if the CRD is absent). A ConfigMap named after the application is injected via `envFrom`. Rollout health: fatal waiting reasons (`ImagePullBackOff`, `ErrImagePull`, `CrashLoopBackOff`) fail fast, and the `oops.rollout.started-at` annotation bounds convergence at 5 minutes.

**Config item metadata**: env/file config items carry UI-only metadata (`mountPath`, `group`, `comment`) serialized as JSON in annotations on the `{app}` / `{app}.files` ConfigMap/Secret: `oops.mounts` and `oops.config-meta`. The metadata never enters the container.

**Per-application config** (JSON-blob columns via `JSONField[T]`): build config (source, build image/commands, Dockerfile), service config (port, per-environment hosts with HTTPS and optional HTTP basic auth — BCrypt hash only, write-only password DTO field, blank password carries the stored hash forward), runtime spec (per-environment resources/replicas + liveness/readiness probes), expert config (ServiceAccount, priority, scheduled restart cron).

**Status & metrics**: live per-pod readings from the K8s PodMetrics API; usage history via `query_range` against whatever Prometheus-compatible backend the cluster runs, reached through the API server service proxy (`services/proxy` permission needed). Pods matched by StatefulSet naming (`{app}-[0-9]+`). No backend → `MONITORING_NOT_AVAILABLE` → setup prompt. `resources/vmsingle.yaml` is a minimal VictoriaMetrics for bare clusters.

**Resource alerts** (`engine/alerts.go`, opt-in via `oops.metrics.alert.enabled`, global config only): the sustained-for-N-minutes condition is one PromQL `min_over_time` instant query per target; `application_alert_state` makes it edge-triggered (notify on OK→FIRING, repeat interval, once on recovery). Memory 90%/5min, CPU 95%/10min by design.

**Scheduled restarts** (`engine/restartjob.go`): per-environment cron in expert config, scanned every minute, `GET /api/cron/next` previews fire times.

**Deletion & migration**: Danger Zone cascade-deletes K8s resources + DB rows (owner/admin only); namespace migration moves DB rows and live workloads per environment with per-environment results.

### Ownership & Collaborators
`application.owner` (user NanoId) is stamped at creation. Collaborators get read/operate access; delete stays with owner/admins.

### External Accounts & Feishu Events
`external_account` links OAuth identities for notification routing. Inbound events use the Feishu SDK's **long connection** (`internal/feishu/events.go`) — no webhook, no public endpoint; subscription method in the Feishu console must be 长连接. Gated on both `oops.feishu.enabled` and `oops.feishu.sync-user-deactivation`. The only subscription is `contact.user.deleted_v3` → disable the linked OOPS account (login and both auth filters enforce `enabled`); the last enabled admin is never disabled.

### Domain Management
`domain` rows: `host`, `https`, `certMode` (`AUTO` = Traefik certResolver, `UPLOADED` = TLS secret synced per namespace), optional environment binding. Longest-suffix matching; wildcards are stripped on input. HTTPS hosts get an HTTP→HTTPS redirect middleware on a companion `web` entrypoint route.

### Sandboxes & Pod Filesystem
`/api/sandbox` runs commands in isolated K8s Jobs (per-environment runtime image allowlist, CPU/memory/timeout/TTL, poll or SSE streaming); long-lived instances add terminal + file operations. Listing without `environment` spans every environment. The pod filesystem browser (`.../pods/{pod}/files`) lists/reads/writes/uploads/renames inside application pods.

## API Patterns

- **Envelope**: every endpoint returns `Result{success, message, data}`; frontend `ApiResponse<T>` mirrors it. Errors carrying `domain.BizError` render as `Result.failure` with the message.
- **URLs**: `/api/namespaces/{namespace}/...`, applications keyed by name. Two surfaces: `/api/**` (UI, JWT via `Authorization: Bearer` or `?token=` for WebSocket) and `/openapi/**` (CLI/machine, user access token). The repo-vendored CLI (`skills/oops/scripts/oops.py`) targets `/openapi/**`.
- **Environment parameter**: always `?environment={name}` (never `env`).
- **Identity**: 24-char NanoId strings (`domain.NewID`).
- **Pagination**: `Page{total, data, size, totalPages}`, `page` is 1-based.
- **JWT claims**: `sub` = username, `userId`, `role` (`ADMIN`/`USER`); HS256 with `oops.jwt.secret`.
- **Timestamps**: `LocalDateTime` — local time, microseconds, no zone suffix.

## WebSocket Protocols

| Path | Purpose |
|---|---|
| `.../pods/{pod}/terminal` | TTY exec (binary+text stdin) |
| `.../pods/{pod}/log` | Text lines, tail last 2000 |
| `.../pipelines/{pipelineId}/log` | JSON: `{type: steps\|step\|error, ...}` |
| `/api/sandbox/instances/{id}/terminal` | Sandbox TTY (also on `/openapi`, header auth only) |

Log streams split lines like `BufferedReader.readLine` (`\n`, `\r\n`, or bare `\r` — see `k8s.ScanLogLines`) so progress redraws don't glue together. Log handlers answer text `"ping"` with `"pong"`; terminal handlers must not (stdin is live).

## Frontend Patterns

- **Navigation**: sidebar groups in `web/lib/nav-config.ts` (Cluster / Network / App Management / System Settings / Help).
- **Auth**: JWT in cookie `auth_token`; `web/proxy.ts` (not `middleware.ts`) redirects unauthenticated users to `/login`; `apiFetch` attaches the bearer and hard-redirects on 401. `userId`/`username`/`role` in localStorage.
- **Work context**: namespace/app/environment selection lives in the Zustand work-context store; `lib/work-context-url.ts` stamps it onto links — the internal param is `env` but it always travels in URLs as `environment` (`urlKeyFor`). URL wins over the store.
- **Feature flags**: `useFeaturesStore` (`feishu`, `ide`, `objectStorage`, ...) gates optional UI.
- **i18n**: four locales under `web/locales/`; `t()` falls back to the key.
- **Command palette**: `/` opens it (outside inputs); two-stage command → app search.

## Testing

Backend tests live beside the code (`*_test.go`, 8 packages): domain state machine and policies, engine rollout verdict / build job assembly / deploy helpers / alert parsing, store probe normalization / JSONField / LocalDateTime / PEM parsing, k8s configmap annotations / metrics selectors, httpapi auth, config, cron, crypto. `go test ./...` needs no database or cluster. No frontend tests currently exist.

## Code Style

### Backend
- Mainstream Go: initialisms upper-case (`IDE`, `API`, `JWT`), error strings lower-case, `*Request` for request DTOs, interfaces defined by consumers, `log/slog` for logging.
- **Full descriptive variable names** — `environment`, `statefulSet`, `containerStatus`; never `e`, `ss`, `cs`.
- Verify with `go build ./... && go vet ./... && go test ./...`; staticcheck should stay clean.

### Frontend
- ESLint (`web/eslint.config.mjs`), TypeScript strict, Tailwind, `@/components/` alias. Try shadcn/ui first: `npx shadcn add <component>`.
- **Every clickable element needs `cursor-pointer`** (Tailwind v4 dropped the button default), paired with `disabled:cursor-not-allowed`.

## Collaboration

Conventional commit subjects (`type(scope): summary`) with a meaningful body for non-trivial changes, and a `Co-authored-by:` trailer naming the assisting AI coding agent.
