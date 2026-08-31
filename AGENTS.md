# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Project Overview

OOPS is a Kubernetes-based PaaS (Platform as a Service) for deploying applications. It provides a web UI for managing applications, environments, pipelines (CI/CD builds), and IDE instances backed by code-server.

## Architecture

### Backend (Spring Boot)

**Java version:** 25  
**Spring Boot:** 3.5.3

The backend follows a **DDD layered architecture** under `com.github.wellch4n.oops`:

- **`interfaces/`**: REST controllers (`interfaces/rest/`, including dual-mapped `/api` + selected `/openapi` endpoints), WebSocket handlers (`interfaces/websocket/`), and interface DTOs. `GlobalExceptionHandler` lives here.
- **`application/`**: Application services (use cases), DTOs, Spring events, and **ports** (`application/port/repository`, `application/port/external`) — interfaces that infrastructure implements.
- **`domain/`**: Pure domain model split by aggregate (`application`, `delivery`, `environment`, `identity`, `namespace`, `routing`, `sandbox`) plus shared value types (`domain/shared`). Repository interfaces are defined as application ports under `application/port/repository`, not in `domain/`.
- **`infrastructure/`**: Adapters and integrations
  - `infrastructure/persistence/jpa/` — JPA entities, repositories, attribute converters, mappers
  - `infrastructure/kubernetes/` — Fabric8 client glue (`KubernetesClients`, `KubernetesClientPool`), `task/` (`ArtifactDeployTask`, `PipelineExecuteTask`), `task/processor/` (deploy processor chain), `container/`, `pod/`, `crds/`, `stream/`, `volume/`, `ide/`, `sandbox/`
  - `infrastructure/external/feishu/` — Feishu (Lark) OAuth + messaging
  - `infrastructure/objectstorage/` — S3-compatible storage for ZIP source uploads and static assets
  - `infrastructure/scheduler/` — `@Scheduled` jobs (e.g. `PipelineInstanceScanJob`)
  - `infrastructure/config/` — Spring config beans, `@ConfigurationProperties` classes, `SpringContext` static accessor, security filters (`JwtAuthFilter`, `OpenApiAuthFilter`), and startup initializers (`UserInitializer` seeds the default admin)
- **`shared/`**: Cross-cutting utilities and exceptions (`BizException`).

**Multi-host support**: Environment entity stores K8s API server credentials, allowing management of multiple clusters.

Key technologies:
- Fabric8 Kubernetes client for K8s operations
- MySQL for persistence (external)
- JWT-based authentication with Feishu (Lark) external auth support
- WebSocket for real-time logs (pipelines, pod logs, terminals)
- Spring Data JPA with Hibernate

### Frontend (Next.js)

**Next.js:** 16.2.12  
**React:** 19.2.3  
**Package manager:** pnpm

Key directories:

- `web/app/`: Next.js App Router pages
- `web/components/`: React components including `app-layout.tsx`, `app-sidebar.tsx`
- `web/components/ui/`: Radix UI-based components (shadcn/ui style)
- `web/lib/`: Utilities including i18n, API clients
- `web/store/`: Zustand state management
- `web/hooks/`: Custom React hooks
- `web/locales/`: Translation files (`zh-CN`, `en-US`, `zh-TW`, `ja-JP`)

Key technologies:
- Tailwind CSS v4
- Radix UI primitives
- Zustand for state management
- Monaco Editor for code editing
- XTerm.js for terminal emulation
- React Hook Form + Zod for forms

## Development Commands

### Backend

```bash
# Run the application (requires config/application.yml — cp from config/application.yml.example)
./mvnw spring-boot:run

# Build JAR
./mvnw package -DskipTests

# Run tests
./mvnw test

# Run single test class
./mvnw test -Dtest=ClassName

# Run single test method
./mvnw test -Dtest=ClassName#methodName
```

### Frontend

```bash
cd web

# Install dependencies
pnpm install

# Development server (runs on localhost:3000; API_BASE_URL defaults to localhost:8080)
pnpm dev

# Build
pnpm build

# Lint
pnpm lint
```

### Docker Build

Three compose/runtime layouts live under `docker/`:

- **Prebuilt multi-service stack**:
  ```bash
  docker compose -f docker/docker-compose.yml up -d
  ```
  Four services on a shared bridge network: `mysql` (8.4) + prebuilt `ghcr.io/wellch4n/oops-backend:latest` + prebuilt `ghcr.io/wellch4n/oops-frontend:latest` + `nginx` (1.27-alpine reverse proxy on host port 8080). Backend config is injected by bind-mounting `docker/application.yml` to `/app/config/application.yml`.
- **Local-build multi-service stack** (use when changing Dockerfiles or validating image builds):
  ```bash
  docker compose -f docker/docker-compose.build.yml up -d
  ```
  Same service topology as above, but builds backend and frontend from [docker/Dockerfile.backend](docker/Dockerfile.backend) and [docker/Dockerfile.frontend](docker/Dockerfile.frontend).
- **Local-build stack with external MySQL**:
  ```bash
  docker compose -f docker/docker-compose.local.yml up -d
  ```
  Builds backend/frontend locally and omits the bundled `mysql` service. Point the datasource in `docker/application.yml` at `host.docker.internal` or another reachable MySQL host.
- **All-in-one image** (single container, three processes):
  ```bash
  docker build -f docker/all-in-one/Dockerfile -t oops .
  ```
  Uses [docker/all-in-one/Dockerfile](docker/all-in-one/Dockerfile), [docker/all-in-one/nginx.conf](docker/all-in-one/nginx.conf), and [docker/all-in-one/entrypoint.sh](docker/all-in-one/entrypoint.sh). Mount your `application.yml` at `/app/config/application.yml` (Spring Boot default external-config path) or set `SPRING_CONFIG_LOCATION` for a custom path. Requires an external MySQL — configure it in the mounted yml.

## Configuration

OOPS uses a single `application.yml` for all configuration. There are two checked-in templates:

- `config/application.yml.example` → cp to `config/application.yml` for local dev (`./mvnw spring-boot:run` or IDE Run). Spring Boot's default external-config search path includes `./config/` (relative to the working directory), so the file is picked up automatically when launched from the project root. The runtime file is gitignored and is outside `src/main/resources/`, so dev secrets never end up in the packaged JAR.
- `docker/application.yml.example` → cp to `docker/application.yml` for any docker-compose stack. All compose files bind-mount `./application.yml` to `/app/config/application.yml`; the template's datasource block carries commented "scenario A / scenario B" alternatives for bundled vs external MySQL.

Key properties to configure:

- `spring.datasource.*`: MySQL JDBC URL / username / password
- `oops.admin.password`: default admin password (replaces the legacy `ADMIN_PASSWORD` env var)
- `oops.jwt.secret`: JWT signing key (min 32 chars)
- `oops.crypto.secret-key`: encryption key for environment K8s tokens
- `oops.pipeline.image.*`: Clone, Buildah, and ZIP (curl) images for builds, registry mirrors, and ZIP unzip excludes
- `oops.ingress.cert-resolver`: Traefik certificate resolver name
- `oops.object-storage.*`: S3-compatible object storage for ZIP source uploads and asset hosting (`enabled`, `endpoint`, `region`, `bucket`, `access-key`, `secret-key`, `path-style-access`, `key-prefix`, `asset-key-prefix`, `asset-base-url`, URL expiration, max file size)
- `oops.feishu.*`: Feishu (Lark) OAuth configuration (optional); the same credentials also drive the inbound event long connection, which additionally needs `sync-user-deactivation`
- `oops.ide.*`: code-server IDE configuration (optional)
- `oops.sandbox.*`: Sandbox runtime image allowlist and execution defaults
- `oops.pod-filesystem.*`: Pod file browser limits, especially max download size
- `oops.metrics.history.*`: usage-chart data source — `backend.namespace` / `backend.service-name` / `backend.port` locate the cluster's Prometheus-compatible Service (one convention for every environment, defaulting to kube-prometheus-stack's `monitoring/prometheus-operated:9090`; blank the namespace to hide the charts), plus `interval-seconds` and `max-range-hours` which shape the chart buckets
- `oops.metrics.alert.*`: resource alert thresholds — `enabled`, `cpu.{threshold-percent,sustained-minutes}`, `memory.{threshold-percent,sustained-minutes}`, `repeat-interval-minutes`. One set for the whole installation; there is no per-application alert config

## Key Concepts

### Environments
An Environment represents a Kubernetes cluster. Each environment has:
- Kubernetes API server URL and token
- Work namespace and optional build storage class for builds
- Image registry credentials
- Optional Git credentials (`username`/`password` or `privateKey`) synced into the work namespace as the `git-credential` Secret for private repository clones

Applications can be deployed to any configured environment.

Environments are referenced **by name** everywhere, and the reference is uniformly called
`environment` — in DB columns, in the `environment_configs` JSON blobs, and in query parameters
alike (`?environment={name}`, never `?env=`). Nothing outside the `environment` table knows
environment ids.

### Pipelines
Build pipelines run as Kubernetes Jobs with init containers:
1. **clone**: Clones source code from git (or downloads ZIP via curl for ZIP source type)
2. **dockerfile** (optional): Writes an inline `USER` Dockerfile into the workspace
3. **build** (optional): Runs custom build commands
4. **push**: Builds and pushes Docker image using Buildah, with the `overlay` storage driver over the `container-storage` emptyDir that `ContainerStorageVolume` mounts at `/var/lib/containers`. Both halves matter: the store defaults into the container's writable layer, where every write pays an overlayfs copy-up, and `vfs` (used until the volume existed) copies the entire tree per layer — together they made a build of any size stall for minutes with no log output after its last line. Buildah's own image declares a `VOLUME` for this path, but Kubernetes ignores image `VOLUME` declarations, so the mount has to be explicit.

Two source types exist: `GIT` (default) and `ZIP`. ZIP uploads use presigned S3 URLs via `BuildSourceObjectStorageService` — the frontend gets a presigned PUT URL from `POST .../deployments/source-upload`, uploads the file, then triggers the pipeline. ZIP builds use `oops.pipeline.image.zip` (defaults to `alpine/curl:8.17.0`) to download the archive.

Pipeline logs are streamed via WebSocket (`PipelineLogWebSocketHandler`). A `@Scheduled(fixedRate=5000)` job (`PipelineInstanceScanJob`) polls K8s for build completion and rollout convergence. Pipeline state transitions use optimistic locking: `PipelineRepository.updateStatusIfMatch()` does a conditional UPDATE and returns row count (0 = lost the race).

**Duplicate deploy guard**: Before starting any pipeline, manual deploy, or rollback, `DeploymentConcurrencyPolicy.activePipelineStatuses()` checks for in-flight pipelines (status `RUNNING`, `DEPLOYING`, or `ROLLING_OUT`) through `PipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn()`. If one exists, a `BizException("Application is being deployed")` is thrown. This is separate from optimistic locking — it's a pre-check to prevent duplicate concurrent deployments.

**Pipeline notifications**: A Spring event system sends notifications at each pipeline state transition. `PipelineNotificationEvent` is published and `PipelineNotificationListener` (`@EventListener`) formats messages sent to the operator via `ExternalMessageService` (Feishu). Notification types: `CREATED`, `BUILD_SUCCEEDED`, `DEPLOYING`, `ROLLING_OUT`, `SUCCEEDED`, `FAILED`, `STOPPED`.

### Application Deployment
Applications deploy as **StatefulSets** (not Deployments) with `enableServiceLinks(false)` to prevent K8s env var pollution. Ingress uses **Traefik IngressRoute CRDs** (not standard Kubernetes Ingress) — the code checks for CRD existence and skips gracefully if absent. ConfigMap named after the application is injected via `envFrom: configMapRef`.

**Config item metadata (no DB table)**: Env/file config items carry UI-only metadata — `mountPath` (file mounts), plus `group` and `comment` for organizing and documenting keys in the editor. Rather than a new table, `KubernetesConfigMapGateway` serializes this as JSON in resource annotations on each `{app}` / `{app}.files` ConfigMap/Secret: `oops.mounts` (`key → mountPath`) and `oops.config-meta` (`key → {group, comment}`). Each annotation only describes the keys in its own resource's `data`, so items moving between env/secret/mounted carry their metadata along on the next full-rewrite save. The metadata never enters the container. Frontend `application-config-info.tsx` renders collapsible per-group sections with key/comment search; `env-import-dialog.tsx` round-trips groups as `## group: xxx` markers and comments as `#` lines in the dotenv import/export format.

Deployment triggering logic lives in `DeploymentService` (not `PipelineService`). The `DeploymentController` at `/api/namespaces/{namespace}/applications/{name}/deployments` handles deploy triggers and source uploads.

**Deploy processor chain**: `ArtifactDeployTask` orchestrates deployment via a sequence of `DeployProcessor`s sharing a `DeployContext`:
1. `NamespaceProcessor` — ensures target namespace exists
2. `ImagePullSecretProcessor` — creates registry pull secret
3. `StatefulSetProcessor` — applies the StatefulSet (sets `ownerReference` on context)
4. `ServiceProcessor` — creates Service with `ownerReference` → StatefulSet
5. `IngressRouteProcessor` — creates Traefik IngressRoutes with TLS/redirect logic and `ownerReference` → StatefulSet

The K8s client is created per-task and closed via try-with-resources in `ArtifactDeployTask.call()`.

**Per-application config entities**:
- `ApplicationBuildConfig`: Stores source type (`GIT`/`ZIP`), repository/source key, build image/commands, and Dockerfile config (`BUILTIN` path or inline `USER` content). Frontend: `application-build-info.tsx`.
- `ApplicationServiceConfig`: Stores container `port` and per-environment hostname/HTTPS overrides (`List<EnvironmentConfig>` as JSON blob). Frontend: `application-service-info.tsx`.

  Each host can also carry **HTTP basic auth** (`basicAuthEnabled` / `basicAuthUsername` / `basicAuthPasswordHash`, set in the host editor dialog `apps/components/host-editor-dialog.tsx`, which is the single add/edit form for a host's name, HTTPS and basic auth). Only the BCrypt hash is stored — the plaintext is hashed in `ApplicationService.updateApplicationServiceConfig` and never returned, so the DTO exposes a write-only `basicAuthPassword` plus a read-only `basicAuthPasswordSet` marker, and a blank password on update carries the stored hash forward. At deploy time `IngressRouteProcessor` writes an htpasswd Secret and a Traefik `basicAuth` Middleware named `{app}-basic-auth-{host}` (labelled `oops.resource=basic-auth` so hosts that turn auth off get their pair deleted), attached to the route that actually serves traffic — for an HTTPS host that is the `websecure` route, since the `web` one only redirects. Note the endpoint rewrites the whole service config: a client that PUTs `environmentConfigs` without these fields (e.g. `oops app service set`) clears basic auth, exactly as it already clears unlisted hosts.
- `ApplicationRuntimeSpec`: Stores per-environment resource limits (`cpuRequest`, `cpuLimit`, `memoryRequest`, `memoryLimit`, `replicas`) plus global health check settings split into `liveness` and `readiness` probes. Frontend: `application-runtime-spec.tsx`.
- `ApplicationExpertConfig`: Stores per-environment advanced deployment settings: the ServiceAccount name applied to the StatefulSet pod template, plus the scheduled-restart fields (`scheduledRestartEnabled`, `scheduledRestartCron`). Frontend: `application-expert-config.tsx` (the cron picker is `apps/components/cron-schedule-builder.tsx`); service account options come from `ServiceAccountController`.

**Application resource viewer**: `GET /api/namespaces/{namespace}/applications/{name}/resources?environment=...` returns read-only Kubernetes resources for expert inspection.

**Application status & metrics**: `GET .../applications/{name}/status?environment=...` returns per-pod status views and `GET .../applications/{name}/events?environment=...` returns recent K8s events. Resource usage comes from two independent sources:

- **Live readings** — `GET .../applications/{name}/metrics?environment=...` returns `List<PodMetricSnapshot>` (`podName`, `cpuMillis`, `memoryBytes`) via `ApplicationMetricsGateway` → `KubernetesApplicationMetricsGateway`, which reads the K8s `PodMetrics` API (requires metrics-server). The status table renders per-pod CPU/memory from this.
- **History** — `GET .../applications/{name}/metrics/history?environment=&range=&agg=` returns `PodMetricHistory` for the usage charts. **OOPS stores no history of its own**: `PodMetricHistoryService` resolves the window and bucket width (snapping both ends to the bucket grid, so refreshing does not shift every point), then `PodMetricHistoryProvider` → `PrometheusPodMetricHistoryProvider` runs a `query_range` against whatever Prometheus-compatible backend the cluster already runs. `ApiServerProxyPrometheusTransport` reaches that in-cluster ClusterIP through the API server service proxy, so no ingress or second credential is needed — but the environment's token needs `services/proxy`. Pods are matched by StatefulSet naming (`{app}-[0-9]+`) rather than the `oops.app.name` label, because kubelet/cAdvisor metrics carry no pod labels; namespace and application names are validated before being interpolated into the PromQL matcher. With no backend reachable the service throws `MONITORING_NOT_AVAILABLE`, which the drawer renders as a setup prompt rather than an empty chart.

Frontend: `apps/[namespace]/[name]/status/page.tsx`; the charts live in `apps/components/application-metrics-drawer.tsx`, which also draws the environment's `ApplicationRuntimeSpec` request/limit as reference lines.

`resources/vmsingle.yaml` is a minimal VictoriaMetrics manifest for clusters with no monitoring installed — one pod scraping the kubelet `/metrics/resource` endpoint, which carries exactly the two series the charts read.

**Resource alerts**: `ResourceAlertScanJob` (`infrastructure/scheduler/`, `@Scheduled(cron = "0 * * * * *")`) warns an application's owner when CPU or memory sits against its limit. **Off unless `oops.metrics.alert.enabled` is set** — it messages real people and queries a backend not every cluster runs, so it is opt-in. Configured **only globally** under `oops.metrics.alert` (`ResourceAlertProperties`) — there is no per-application alert setting; once on, an application becomes alertable by having limits in its `ApplicationRuntimeSpec` for that environment, and one without limits is skipped because `/metrics/resource` carries no limits series to take a percentage of (that would need kube-state-metrics). Thresholds differ per metric by design: memory 90%/5min (an OOMKill is fatal, so warn early), CPU 95%/10min (throttling only degrades latency, and `container_cpu_cfs_throttled_seconds_total` — the metric that would really show it — is cAdvisor-only, so "sitting against the limit" is a proxy that needs headroom).

The whole "sustained for N minutes" condition lives in PromQL: `ResourceAlertProbe` → `PrometheusResourceAlertProbe` runs one instant query per target of the form `min_over_time((…)[Ns:Is]) > threshold`, so a returned row *is* a pod that never dropped below the line — OOPS keeps no time series and no rolling buffer. `PrometheusSelectors` holds the pod matcher shared with the charts.

The scan is three phases and only the middle one fans out: targets and previous state are each read in one query on the scan thread, the network-bound probes run on virtual threads touching no database, and transitions are written back in one batch. `application_alert_state` (one row per app + environment + metric) makes it edge-triggered — notify on OK→FIRING, repeat every `repeat-interval-minutes`, notify once on recovery; a failed probe leaves state untouched so an unreachable backend neither repeats nor falsely resolves. Transitions publish `ApplicationAlertEvent`, and `ApplicationAlertListener` (`@Async("notificationTaskExecutor")`) resolves the owner and sends via `ExternalMessageService` — one message per application listing the offending pods, not one per pod.

**Scheduled rolling restart**: `ScheduledRestartJob` (`infrastructure/scheduler/`, `@Scheduled(cron = "0 * * * * *")`) scans every minute for applications whose `ApplicationExpertConfig` has `scheduledRestartEnabled` for an environment, matches the per-environment `scheduledRestartCron` (5-field cron, evaluated via `shared/util/CronSchedule`), and calls `ApplicationRuntimeGateway.rolloutRestart()`. `CronController` exposes `GET /api/cron/next?expression=...&count=N` to preview upcoming fire times for the UI.

**Application deletion**: The "Danger Zone" tab (`application-danger-zone.tsx`) provides cascade deletion that cleans up Kubernetes resources (StatefulSet, Service, IngressRoute) alongside the database record.

**Namespace migration**: The Danger Zone also migrates an application to another namespace. `POST .../applications/{name}/namespace-migration` → `NamespaceMigrationService.migrateNamespace()` updates the `application` and `pipeline` tables (via the repositories' `migrateNamespace()`) and attempts to move the live K8s workloads per environment, returning a `NamespaceMigrationResult` with per-environment success/failure.

### IDE Integration
Optional code-server integration (`oops.ide.enabled=true`) creates IDE instances as StatefulSets in the work namespace. `IDEConfig` and `IDEService` are both `@ConditionalOnProperty(prefix="oops.ide", name="enabled", havingValue="true")` — fully absent when disabled.

IDE-specific `oops.ide.*` properties:
- `domain`: base domain for IDE hostnames — IDE URL = `{statefulset-name}.{domain}`
- `https`: boolean, controls `websecure` vs `web` Traefik entrypoint
- `image`: code-server image
- `middleware`: comma-separated Traefik middleware names applied to IDE IngressRoute

IDE StatefulSet name: `{applicationName}-ide-{ideId}`. On delete, only the StatefulSet is deleted — Service and IngressRoute are cascade-deleted by K8s via `ownerReference`. IDE IngressRoutes set `syntax: "v3"` (application IngressRoutes do not).

Default IDE config (extensions, settings) is stored at `src/main/resources/ide-default-config.json`. An environment-scoped `ide-config` ConfigMap in the work namespace overrides it and is auto-created on first `getDefaultIDEConfig()` call.

### Application Ownership & Collaborators
`Application` has an `owner` field (User NanoId) and a `List<ApplicationCollaborator> collaborators`. `createApplication` stamps the caller's `userId` as `owner` automatically. `ApplicationResponse` includes both `owner` (ID) and `ownerName` (resolved via `UserService.getUsernameMapByIds()`).

Owners can grant other users access to their applications via collaborators (`Application.setCollaboratorUserIds()` de-duplicates and excludes the owner). The Danger Zone (cascade delete) is restricted to the owner and admins; collaborators have read/operate access but cannot delete.

### External Accounts
`ExternalAccount` entity stores linked OAuth accounts (`email`, `provider`, `providerUserId`). Used by the notification system to route pipeline status messages to the user's linked provider (e.g., Feishu).

### External Event Subscriptions (inbound)

Providers can also push events *into* OOPS. The path is split so a provider knows nothing about OOPS's domain and a business handler knows nothing about the provider:

`FeishuEventClient` (infrastructure adapter, receives and translates) → publishes a **provider-neutral Spring event** → a listener in `application/event/` acts on it.

**Transport is a long connection, not a webhook.** `FeishuEventClient` dials out with the SDK's `com.lark.oapi.ws.Client`, feeding an `EventDispatcher` that routes by event type. OOPS is self-hosted and frequently has no address Feishu's servers can reach, so having the backend dial out is the mode that works everywhere; it also removes the webhook attack surface entirely — no public endpoint, no verification token, no encrypt key, because the connection authenticates with the app credentials themselves. **Nothing beyond `oops.feishu.enabled` / `app-id` / `app-secret` needs configuring**, and in the Feishu console the subscription method must be 长连接, not 开发者服务器. The trade-off is that events only arrive while the process is up; acceptable because the only subscription is idempotent and nothing else depends on it.

- **Off unless both `oops.feishu.enabled` and `oops.feishu.sync-user-deactivation` are true** — `@ConditionalOnProperty` with two names requires both. Opt-in for the same reason resource alerts are: it takes people's access away on a signal from outside OOPS, so it must not switch itself on at upgrade. The gate sits on the bean rather than inside the handler because resignations are the only subscription — with the switch off there is nothing to listen for and no socket worth opening. (`ExternalUserDeactivatedListener` still exists either way; with no client it simply never hears anything.)
- It connects on `ApplicationReadyEvent` from a virtual thread, so an unreachable Feishu cannot hold up startup. **Reconnection is left entirely to the SDK**: `autoReconnect` already defaults to true and `Client.start()` routes ordinary failures into it, so the only thing it hands back is the kind of error (bad credentials) no retry fixes. There is no de-duplication either — delivery is at least once, but disabling an already-disabled account is a no-op.
- **Subscribed events.** `contact.user.deleted_v3` (resignation) → `ExternalUserDeactivatedEvent` → `ExternalUserDeactivatedListener` disables the OOPS account (`enabled=false`), which login, `JwtAuthFilter` and `OpenApiAuthFilter` all already enforce, so an issued JWT and an OpenAPI access token both stop working on the next request. It resolves the person by `ExternalAccount.providerUserId` first and falls back to email; someone with no OOPS account is an info log, not an error. Disabling rather than deleting keeps owned applications with a resolvable owner and lets an admin reverse a mistake. `UserService.deactivateUser()` refuses to disable the **last enabled admin** (`UserRepository.countEnabledByRole`) — an external directory has no idea it is holding the only key to the installation.
- The listener is **synchronous**, unlike the notification listeners: it is a single-row update running on the SDK's own connection thread, so an `@Async` hop would buy nothing. Nothing in this path touches an OOPS request, scheduler or pipeline thread. Note that `Client.handleDataFrame` catches whatever the handler throws **and acknowledges the event anyway**, so a failure is lost rather than redelivered — hence the listener logs the account it could not disable. Every event is logged twice at INFO, once on receipt and once for the outcome (disabled / no linked account / left as is): resignations are rare enough for the volume not to matter, and the SDK logs nothing at INFO, so without those lines an event that matched no account would leave no trace of having arrived.

Adding a provider means one new client adapter; adding a business reaction means one new listener. Neither touches the other. `FeishuEventClientTests` drives payloads straight through `EventDispatcher.doWithoutValidation()`, so the event handling is covered without a socket.

### Domain Management
`Domain` entities store managed domains with `host`, `https`, `certMode` (`AUTO` or `UPLOADED`), and optional PEM cert/key. Domains are admin-managed at `/api/domains` (ADMIN-only writes) and listed at `/networks/domains`. Domain lookup uses longest-suffix matching (supports wildcard by stripping `*.`).

When `IngressRouteProcessor` creates Traefik IngressRoutes, it resolves the domain for each host:
- **UPLOADED**: Syncs a TLS Secret (`domain-{host}`) into the application namespace and references it in the IngressRoute TLS block.
- **AUTO**: Uses the Traefik `certResolver` from `IngressConfig`.

HTTPS applications automatically get an HTTP→HTTPS redirect Middleware (`oops-redirect-https`) applied to a companion `web` entrypoint IngressRoute.

### Pipeline Deploy Modes
`Pipeline.deployMode` is either `IMMEDIATE` (default) or `MANUAL`. With `MANUAL`, `PipelineInstanceScanJob` transitions the pipeline to `BUILD_SUCCEEDED` after the K8s Job completes but does **not** automatically deploy — a separate `deployPipeline()` call is required. With `IMMEDIATE`, it continues through `DEPLOYING → ROLLING_OUT`; the scan job then marks `ROLLING_OUT → SUCCEEDED` or `ERROR` after checking StatefulSet rollout health.

### Pipeline Rollback
`PipelineController` supports `POST .../pipelines/{id}/rollback`. Rollback creates a new pipeline with `triggerType=ROLLBACK`, reuses the historic successful artifact, skips the build job, and transitions directly through the deploy path (`INITIALIZED → DEPLOYING → ROLLING_OUT`). Normal releases use `triggerType=RELEASE`.

### Sandbox Execution
The `domain/sandbox` aggregate plus `SandboxController` (`/api/sandbox`, `/openapi/sandbox`) run arbitrary commands inside isolated Kubernetes Jobs. Each execution selects a per-environment runtime image and supports configurable CPU/memory limits, timeout, and TTL after completion. Two response modes: non-streaming (poll) and streaming via SSE. Long-lived sandbox instances support terminal and file operations. Configured via `SandboxProperties` (`oops.sandbox.*`).

### Pod Filesystem Browser
`PodFileSystemController` at `/api/namespaces/{namespace}/applications/{name}/pods/{pod}/files` lists, downloads, reads, writes, uploads, deletes, renames, and creates directories inside a pod. Backed by `PodFileSystemProperties` (`oops.pod-filesystem.*`) — typically constrained by max download size.

### Static Assets
`StaticAssetController` at `/api/assets` lists and deletes object-storage-backed assets and creates presigned upload URLs. Frontend route: `/assets`. Static assets require `oops.object-storage.enabled=true`.

## API Patterns

### Response Envelope
All REST endpoints return `Result<T>` — a record with `boolean success`, `String message`, `T data`. Use `Result.success(data)` and `Result.failure(message)`. The frontend `ApiResponse<T>` mirrors this exactly.

### URL Convention
All REST controllers are namespaced under `/api/namespaces/{namespace}/...`. Applications are keyed by `name` in URLs (not numeric id).

**Two parallel API surfaces:**
- `/api/**` — UI-facing, authenticated by `JwtAuthFilter` (Bearer JWT or `?token=` query param for WebSocket).
- `/openapi/**` — Machine/CLI-facing, authenticated by `OpenApiAuthFilter` using `Authorization: Bearer <user access token>` (not the UI JWT). `ApplicationController`, `DeploymentController`, `PipelineController`, `ConfigMapController`, and `SandboxController` expose selected dual mappings; `OpenApiDiscoveryController` serves discovery under `/openapi`. The repo-vendored deploy CLI (`skills/oops/scripts/oops.py`) targets this surface. Methods annotated `@OpenApiHidden` return 405 on `/openapi/**`; currently application deletion is hidden while sandbox instance teardown remains available because it is part of the sandbox lifecycle.

**Exceptions to namespacing:**
- `GET /api/health` — health check, no auth required (explicitly permitted in `SecurityConfig`)
- `GET /api/search/applications?keyword=&size=5` — cross-namespace application search for command palette (defaults to 5 results)
- `GET /api/nodes?environment={name}` — node list for a given environment (frontend route `/clusters/nodes`)
- `GET|POST /api/users`, `PUT|DELETE /api/users/{id}` — user management (ADMIN-only writes)
- `GET /api/users/me` — current user from JWT principal
- `PUT /api/users/me`, `PUT /api/users/me/password`, `POST /api/users/me/access-token/reset` — profile, password, and OpenAPI access token self-service
- `/api/auth/external/**` — OAuth provider/callback endpoints and `ExternalAccount` management
- `GET /api/features` — feature flags (`feishu`, `ide`, `objectStorage`, `ideHost`, `ideHttps`)
- `GET /api/assets`, `POST /api/assets/upload-url`, `DELETE /api/assets` — object-storage-backed static asset browsing and upload URL creation
- `POST /api/image-repositories/validations` — validate image registry credentials
- `POST /api/kubernetes/validations` — validate K8s connectivity
- `POST /api/kubernetes/namespaces` — create a K8s namespace
- `GET /api/namespaces/{namespace}/service-accounts?environment={name}` — list ServiceAccounts for application expert config
- `GET /api/cron/next?expression=&count=` — preview upcoming fire times for a 5-field cron expression (scheduled-restart UI)
- `POST /api/index/pipelines`, `POST /api/index/applications` — cross-namespace queries

### Entity Identity
All entities extend `BaseDataObject` which auto-generates a 24-char NanoId via `@PrePersist` (using the `jnanoid` library). Complex collection fields (e.g. `List<EnvironmentConfig>`) are stored as JSON blobs via a custom `@AttributeConverter` — follow this pattern for new nested object collections.

### Pagination
`Page<T>` is a record `(long total, List<T> data, int size, int totalPages)`. `GET /api/namespaces/{namespace}/applications` returns `Page<ApplicationResponse>` with query params `keyword`, `page` (1-based), `size`. The frontend `Page<T>` type mirrors this exactly.

### JWT Claims
Token claims: `sub` = username, `userId` (custom), `role` (`ADMIN` or `USER`). `JwtAuthFilter` falls back to DB lookup by username if `userId` claim is absent (backward compat). `AuthUserPrincipal` is a record `(String userId, String username)` implementing `Principal`; Spring method security uses `ROLE_ADMIN` / `ROLE_USER`.

### Kubernetes Client
The `domain` layer holds no Fabric8 dependency — `Environment.KubernetesApiServer` is just a config holder (`url`, `token`). Fabric8 `KubernetesClient` instances are constructed in `infrastructure/kubernetes/KubernetesClients.from(apiServer)`. Most gateway adapters use `KubernetesClientPool`, a Caffeine cache keyed by `url|token` with `expireAfterAccess(10 minutes)` and a removal listener that closes clients. Short-lived tasks, validation/migration code, and WebSocket streams may still create direct clients and must close them via try-with-resources or `afterConnectionClosed`.

`application` and `interfaces` layers must NOT import `io.fabric8.*` or anything under `infrastructure.kubernetes.*`. Instead, they call K8s through `application/port/external` gateway interfaces (`ClusterNodeGateway`, `ApplicationRuntimeGateway`, `PipelineLogStreamGateway`, `TerminalSessionGateway`, etc.), each implemented by a `Kubernetes*Gateway` adapter in `infrastructure/kubernetes/`.

### Non-Bean Task Objects
`PipelineExecuteTask` is instantiated manually (not as a Spring bean) and uses `SpringContext.getBean()` to pull repositories from the application context. Follow this pattern for other manually-instantiated task objects.

## WebSocket Protocols

Four handlers registered in `WebSocketConfiguration` (all allow `setAllowedOrigins("*")`):

| Path | Purpose |
|---|---|
| `.../pods/{pod}/terminal` | Binary+text stdin, Fabric8 `exec` with TTY (`xterm-256color`) |
| `.../pods/{pod}/log` | Text lines, tail last 2000 lines |
| `.../pipelines/{pipelineId}/log` | JSON messages (see below) |
| `/api/sandbox/instances/{sandboxId}/terminal` | Binary+text stdin for a long-lived sandbox instance terminal (also mapped at `/openapi/sandbox/instances/{sandboxId}/terminal`, authenticated by `OpenApiAuthFilter` via `Authorization` header on the upgrade request — CLI/machine clients only, no `?token=` fallback) |

**Pipeline log message format:**
```json
{ "type": "steps", "data": ["clone","dockerfile","build","push","done"] }   // on connect; dockerfile/build may be absent
{ "type": "step", "data": "[containerName] line", "container": "containerName" }
{ "type": "error", "data": "..." }
```

Log handlers respond to text `"ping"` with `"pong"` and also start a native WebSocket ping-control-frame heartbeat. Terminal handlers write text/binary payloads to the remote TTY, so do not use text `"ping"` as a terminal heartbeat. JWT is accepted as `?token=` query param for WebSocket connections (browsers cannot set custom headers on upgrade).

## Frontend Patterns

### Navigation
Sidebar navigation is defined in `web/lib/nav-config.ts` with five groups:
- **Cluster**: Nodes (`/clusters/nodes`, admin-only)
- **Network**: Domains (`/networks/domains`)
- **App Management**: Apps (`/apps`), Pipelines (`/pipelines`), IDEs (`/ides`), Sandboxes (`/sandboxes`), Assets (`/assets`)
- **System Settings**: Users (`/settings/users`), Environments (`/settings/environments`), Namespaces (`/settings/namespaces`)
- **Help**: OpenAPI docs (`/help/docs/authentication`)

### Auth & Routing
JWT stored in cookie `auth_token` (SameSite=Lax, 7 days). The Next.js middleware is `web/proxy.ts` (not `middleware.ts`) — it reads the cookie and redirects unauthenticated requests to `/login`. Paths `/auth/feishu/callback` and `/api/auth/external` are exempt. `apiFetch` reads the cookie to attach `Authorization: Bearer` and hard-redirects to `/login` on HTTP 401.

Frontend also stores `userId`, `username`, and `role` in `localStorage` (keys `auth_user_id`, `auth_username`, `auth_role`). `isAdmin()` reads `auth_role` from localStorage. Login accepts username **or** email.

### Namespace State
`useNamespaceStore` (Zustand, persisted to `localStorage` key `oops-namespace`) stores the selected namespace. `NamespaceParamProvider` syncs this store bidirectionally with a `?namespace=` URL query parameter — URL takes priority, making namespace bookmarkable.

### Feature Flags
`useFeaturesStore` (not persisted) loads feature flags once on mount and exposes `{ feishu, ide, objectStorage, ideHost, ideHttps }`. Check this store before rendering IDE, Feishu-dependent, or ZIP-upload UI.

### Localization
Four locales: `zh-CN` (default), `en-US`, `zh-TW`, `ja-JP`. Stored in cookie `locale` (max-age 1 year) and `localStorage`. Use `web/locales/` for all user-facing strings. `t()` falls back to the key itself if a translation is missing.

### OpenAPI Docs Copy
The `/help/docs` pages hold no prose — every title, endpoint summary, field description and paragraph is a key in `locales/*/doc.ts` (`doc.<topic>.<section>.<slot>`), passed down as `titleKey` / `summaryKey` / `descriptionKey` / `textKey` so the pages stay server components. Paragraph strings carry an inline markup dialect rendered by `components/doc/doc-markup.tsx`: `` `text` `` → `InlineCode`, `**text**` → bold, and bold may wrap code. Code spans are opaque, so a glob like `` `/openapi/**` `` is never read as a bold marker. Section anchors are derived from the key by `doc-anchor.ts`, never from the rendered title, which keeps `#configmaps-write` pointing at the same heading in every locale — pass an explicit `id` only to keep an existing anchor alive.

### Command Palette
Triggered by pressing `/` (outside input/textarea). Two-stage: select command (Status, Deploy, IDEs, Pipeline, App), then search for an application via `/api/search/applications` with 150ms debounce. Most-recently-used app stored in Zustand persisted to `localStorage` key `oops:recent-app`. Backspace with empty input returns to command selection.

### Other Frontend State
- Sidebar open/closed persisted in cookie `sidebar_state` (`"true"`/`"false"`), read server-side in root layout.
- Theme via `next-themes` with `defaultTheme="system"`.
- `API_BASE_URL` is empty string in production (same-origin), `http://localhost:8080` in dev — set via `NEXT_PUBLIC_API_URL`.
- App owner filter persisted in Zustand under `oops:owner-filter`.

## Database Migrations

OOPS uses Flyway to apply schema and data migrations automatically during application startup.

- MySQL migrations live in `src/main/resources/db/migration/`
- Migration files must be append-only and named like `V2__add_pipeline_index.sql`
- Existing databases without Flyway history are baselined at version `1`; new databases run `V1__baseline_schema.sql`
- Hibernate DDL generation is disabled with `spring.jpa.hibernate.ddl-auto=none`
- `V22__unify_environment_naming.sql` renames the `environment_name` columns and the
  `environmentName` JSON keys to `environment`. Its column renames are guarded on
  `information_schema`, because a database that ran the 3.0 Go release already carries the new
  names while its `flyway_schema_history` stopped at V21 — the migration must be a no-op there

## Configuration Notes

Default admin credentials: `admin` / `oops.admin.password` from `application.yml` (defaults to `admin123` if unset), created by `UserInitializer` on first startup if no admin exists.

MySQL and all other OOPS settings are provided in `application.yml`. All compose stacks bind-mount `docker/application.yml` to `/app/config/application.yml`; `docker-compose.yml` and `docker-compose.build.yml` expect scenario A (bundled `mysql` service hostname), while `docker-compose.local.yml` expects scenario B (external MySQL via `host.docker.internal` or any reachable host). There is no `.env` file in this project — `OOPS_*` / `SPRING_*` env vars are no longer used.

**All-in-one container** (`docker/all-in-one/`): Nginx (port 80) reverse-proxies `/api/` and `/openapi/` to Spring Boot (port 8080) and `/` to Next.js (port 3000). Runtime image is `node:20-slim` (not a JVM base image) — the JDK is copied from the Maven builder stage. `NEXT_PUBLIC_API_URL=/api` and `TZ=Asia/Shanghai` are set. The entrypoint monitors all three PIDs (Java, Node, Nginx); if any process dies it kills the others and exits non-zero.

**Multi-service stack**: `docker/docker-compose.yml` uses prebuilt backend/frontend images; `docker/docker-compose.build.yml` and `docker/docker-compose.local.yml` build locally. The backend runtime image from [docker/Dockerfile.backend](docker/Dockerfile.backend) is `eclipse-temurin:25-jre-alpine`; the frontend runtime image from [docker/Dockerfile.frontend](docker/Dockerfile.frontend) is `node:20-slim`; nginx uses `nginx:1.27-alpine` with [docker/nginx.conf](docker/nginx.conf) bind-mounted to `/etc/nginx/conf.d/default.conf`. Upstreams use Docker DNS service names (`backend:8080`, `frontend:3000`).

**Config injection precedence**: `SPRING_CONFIG_LOCATION` env → `/app/config/application.yml` (compose mount point — Spring Boot's default external-config path when `WORKDIR=/app`) → classpath defaults. Note: the runtime `application.yml` lives in the project's top-level `./config/` directory, which is outside `src/main/resources/`, so it never ends up in the packaged JAR.

**Nginx**: `client_max_body_size 50m`. `proxy_read_timeout 1h` and `add_header X-Accel-Buffering no` on `/api/` and `/openapi/` for streaming/WebSocket. Both `nginx.conf` files share these settings.

## Testing

The project has focused backend tests under `src/test/java/com/github/wellch4n/oops/` covering the smoke context, pipeline state machine, concurrency guard, rollback, rollout health verification, persistence mapping, and publish container behavior. No frontend tests currently exist. New backend tests go in the matching package under `src/test/java/com/github/wellch4n/oops/`.

## Code Style

### Backend
- Lombok for boilerplate reduction
- Constructor injection for dependencies
- Virtual threads for concurrent tasks (`Thread.ofVirtual()`, `Thread.startVirtualThread()`)
- **Use full descriptive variable names** — readability over brevity. Avoid single-letter names like `e`, `n`, `ss`, `cs`, `c`. Prefer `environment`, `name`, `statefulSet`, `containerStatus`, `container`.
- **Avoid fully-qualified class names** — use imports instead of inline qualifiers like `java.util.stream.Collectors.toSet()` or `io.fabric8.kubernetes.api.model.Pod`. Only exception is when two classes with the same simple name are used in the same file and renaming is not practical.

### Frontend
- ESLint config in `web/eslint.config.mjs`
- TypeScript strict mode enabled
- Tailwind CSS for styling
- Component imports use `@/components/` path alias
- Try shadcn/ui components first. Use the auto-install feature: `npx shadcn add <component>`
- **All interactive elements must show the pointer cursor on hover** — add `cursor-pointer` to every clickable element (`<button>`, icon buttons, clickable `<div>`/`<span>`, custom controls). Tailwind v4 no longer defaults buttons to `cursor: pointer`, so it must be explicit. Pair it with `disabled:cursor-not-allowed` for elements that can be disabled.

## Repo-Vendored Deploy CLI

`skills/oops/SKILL.md` ships a Python CLI (`skills/oops/scripts/oops.py`) that wraps the OOPS REST API for end-to-end deploys: create app → configure build → bind env → set service → set runtime → trigger pipeline → wait for rollout. It targets the `/openapi/**` surface and is the preferred entry point when an agent needs to drive OOPS programmatically — prefer it over hand-rolled curl chains. The skill enforces a per-step confirmation policy for write actions.

## Collaboration

When committing, follow the repository's existing detailed commit style:

- Use a conventional commit subject (`type(scope): summary`).
- Include a meaningful body for non-trivial changes, explaining what changed and why in concise paragraphs.
- Include co-author information for the assisting AI coding agent using a `Co-authored-by:` trailer.
