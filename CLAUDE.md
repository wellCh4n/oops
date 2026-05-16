# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OOPS is a Kubernetes-based PaaS (Platform as a Service) for deploying applications. It provides a web UI for managing applications, environments, pipelines (CI/CD builds), and IDE instances backed by code-server.

## Architecture

### Backend (Spring Boot)

**Java version:** 25  
**Spring Boot:** 3.5.3

The backend follows a **DDD layered architecture** under `com.github.wellch4n.oops`:

- **`interfaces/`**: REST controllers (`interfaces/rest/`) and WebSocket handlers (`interfaces/websocket/`). `GlobalExceptionHandler` lives here.
- **`application/`**: Application services (use cases), DTOs, Spring events, and **ports** (`application/port/repository`, `application/port/external`) — interfaces that infrastructure implements.
- **`domain/`**: Pure domain model split by aggregate (`application`, `delivery`, `environment`, `identity`, `namespace`, `routing`, `sandbox`) plus shared value types (`domain/shared`). Repository interfaces are defined as application ports under `application/port/repository`, not in `domain/`.
- **`infrastructure/`**: Adapters and integrations
  - `infrastructure/persistence/jpa/` — JPA entities, repositories, attribute converters, mappers
  - `infrastructure/kubernetes/` — Fabric8 client glue, `task/` (`ArtifactDeployTask`, `PipelineExecuteTask`), `task/processor/` (deploy processor chain), `container/`, `pod/`, `crds/`, `stream/`, `volume/`, `ide/`
  - `infrastructure/external/feishu/` — Feishu (Lark) OAuth + messaging
  - `infrastructure/objectstorage/` — S3-compatible storage for ZIP source uploads
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

**Next.js:** 16.1.6  
**React:** 19.2.3  
**Package manager:** pnpm

Key directories:

- `web/app/`: Next.js App Router pages
- `web/components/`: React components including `app-layout.tsx`, `app-sidebar.tsx`
- `web/components/ui/`: Radix UI-based components (shadcn/ui style)
- `web/lib/`: Utilities including i18n, API clients
- `web/store/`: Zustand state management
- `web/hooks/`: Custom React hooks
- `web/locales/`: Translation files (en, zh)

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

# Development server (runs on localhost:3000, proxies to localhost:8080)
pnpm dev

# Build
pnpm build

# Lint
pnpm lint
```

### Docker Build

Two layouts live under `docker/`:

- **Multi-service stack** (recommended for local dev):
  ```bash
  docker compose -f docker/docker-compose.yml up -d
  ```
  Four services on a shared bridge network: `mysql` (8.4) + `backend` (Spring Boot, JRE-only image) + `frontend` (Next.js standalone) + `nginx` (1.27-alpine reverse proxy on host port 8080). Build files are [docker/Dockerfile.backend](docker/Dockerfile.backend) and [docker/Dockerfile.frontend](docker/Dockerfile.frontend); the proxy config is [docker/nginx.conf](docker/nginx.conf). Backend config is injected by bind-mounting `docker/application.yml` to `/app/config/application.yml` (Spring Boot's default external-config search path). `docker-compose.local.yml` does the same for an external MySQL — the difference is the datasource block inside that mounted file.
- **All-in-one image** (single container, three processes):
  ```bash
  docker build -f docker/all-in-one/Dockerfile -t oops .
  ```
  Uses [docker/all-in-one/Dockerfile](docker/all-in-one/Dockerfile), [docker/all-in-one/nginx.conf](docker/all-in-one/nginx.conf), and [docker/all-in-one/entrypoint.sh](docker/all-in-one/entrypoint.sh). Mount your `application.yml` at `/app/config/application.yml` (or set `SPRING_CONFIG_LOCATION`); the entrypoint auto-detects either path. Requires an external MySQL — configure it in the mounted yml.

## Configuration

OOPS uses a single `application.yml` for all configuration. There are two checked-in templates:

- `config/application.yml.example` → cp to `config/application.yml` for local dev (`./mvnw spring-boot:run` or IDE Run). Spring Boot's default external-config search path includes `./config/` (relative to the working directory), so the file is picked up automatically when launched from the project root. The runtime file is gitignored and is outside `src/main/resources/`, so dev secrets never end up in the packaged JAR.
- `docker/application.yml.example` → cp to `docker/application.yml` for either docker-compose stack. Both `docker-compose.yml` and `docker-compose.local.yml` bind-mount `./application.yml` to `/app/config/application.yml`; the template's datasource block carries commented "scenario A / scenario B" alternatives for bundled vs external MySQL.

Key properties to configure:

- `spring.datasource.*`: MySQL JDBC URL / username / password
- `oops.admin.password`: default admin password (replaces the legacy `ADMIN_PASSWORD` env var)
- `oops.jwt.secret`: JWT signing key (min 32 chars)
- `oops.crypto.secret-key`: encryption key for environment K8s tokens
- `oops.pipeline.image.*`: Clone, Buildah, and ZIP (curl) images for builds
- `oops.ingress.cert-resolver`: Traefik certificate resolver name
- `oops.object-storage.*`: S3-compatible object storage for ZIP source uploads (`enabled`, `endpoint`, `region`, `bucket`, `access-key`, `secret-key`, `path-style-access`, `key-prefix`, URL expiration, max file size)
- `oops.feishu.*`: Feishu (Lark) OAuth configuration (optional)
- `oops.ide.*`: code-server IDE configuration (optional)

## Key Concepts

### Environments
An Environment represents a Kubernetes cluster. Each environment has:
- Kubernetes API server URL and token
- Work namespace for builds
- Image registry credentials

Applications can be deployed to any configured environment.

### Pipelines
Build pipelines run as Kubernetes Jobs with init containers:
1. **clone**: Clones source code from git (or downloads ZIP via curl for ZIP source type)
2. **build** (optional): Runs custom build commands
3. **push**: Builds and pushes Docker image using Buildah

Two source types exist: `GIT` (default) and `ZIP`. ZIP uploads use presigned S3 URLs via `BuildSourceObjectStorageService` — the frontend gets a presigned PUT URL from `POST .../deployments/source-upload`, uploads the file, then triggers the pipeline. ZIP builds use `oops.pipeline.image.zip` (defaults to `alpine/curl:8.17.0`) to download the archive.

Pipeline logs are streamed via WebSocket (`PipelineLogWebSocketHandler`). A `@Scheduled(fixedRate=5000)` job (`PipelineInstanceScanJob`) polls K8s for job completion and drives pipeline state. Pipeline state transitions use optimistic locking: `PipelineRepository.updateStatusIfMatch()` does a conditional UPDATE and returns row count (0 = lost the race).

**Duplicate deploy guard**: Before starting any pipeline or deployment, `PipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn()` checks for in-flight pipelines (status `RUNNING` or `DEPLOYING`). If one exists, a `BizException("Application is being deployed")` is thrown. This is separate from the optimistic locking — it's a pre-check to prevent duplicate concurrent deployments.

**Pipeline notifications**: A Spring event system sends notifications at each pipeline state transition. `PipelineNotificationEvent` is published and `PipelineNotificationListener` (`@EventListener`) formats messages sent to the operator via `ExternalMessageService` (Feishu). Notification types: `CREATED`, `BUILD_SUCCEEDED`, `DEPLOYING`, `SUCCEEDED`, `FAILED`, `STOPPED`.

### Application Deployment
Applications deploy as **StatefulSets** (not Deployments) with `enableServiceLinks(false)` to prevent K8s env var pollution. Ingress uses **Traefik IngressRoute CRDs** (not standard Kubernetes Ingress) — the code checks for CRD existence and skips gracefully if absent. ConfigMap named after the application is injected via `envFrom: configMapRef`.

Deployment triggering logic lives in `DeploymentService` (not `PipelineService`). The `DeploymentController` at `/api/namespaces/{namespace}/applications/{name}/deployments` handles deploy triggers and source uploads.

**Deploy processor chain**: `ArtifactDeployTask` orchestrates deployment via a sequence of `DeployProcessor`s sharing a `DeployContext`:
1. `NamespaceProcessor` — ensures target namespace exists
2. `ImagePullSecretProcessor` — creates registry pull secret
3. `StatefulSetProcessor` — applies the StatefulSet (sets `ownerReference` on context)
4. `ServiceProcessor` — creates Service with `ownerReference` → StatefulSet
5. `IngressRouteProcessor` — creates Traefik IngressRoutes with TLS/redirect logic and `ownerReference` → StatefulSet

The K8s client is created per-task and closed via try-with-resources in `ArtifactDeployTask.call()`.

**Per-application config entities**:
- `ApplicationServiceConfig`: Stores container `port` and per-environment hostname/HTTPS overrides (`List<EnvironmentConfig>` as JSON blob). Frontend: `application-service-info.tsx`.
- `ApplicationRuntimeSpec`: Stores per-environment resource limits (`cpuRequest`, `cpuLimit`, `memoryRequest`, `memoryLimit`, `replicas`) plus global health check settings. Frontend: `application-runtime-spec.tsx`.

**Application deletion**: The "Danger Zone" tab (`application-danger-zone.tsx`) provides cascade deletion that cleans up Kubernetes resources (StatefulSet, Service, IngressRoute) alongside the database record.

### IDE Integration
Optional code-server integration (`oops.ide.enabled=true`) creates IDE instances as StatefulSets in the work namespace. `IDEConfig` and `IDEService` are both `@ConditionalOnProperty(prefix="oops.ide", name="enabled", havingValue="true")` — fully absent when disabled.

IDE-specific `oops.ide.*` properties:
- `domain`: base domain for IDE hostnames — IDE URL = `{statefulset-name}.{domain}`
- `https`: boolean, controls `websecure` vs `web` Traefik entrypoint
- `image`: code-server image
- `middleware`: comma-separated Traefik middleware names applied to IDE IngressRoute
- `proxy-domain`: template for code-server port proxy (default `{{port}}-{{host}}`); must contain both placeholders or is ignored

IDE StatefulSet name: `{applicationName}-ide-{ideId}`. On delete, only the StatefulSet is deleted — Service and IngressRoute are cascade-deleted by K8s via `ownerReference`. IDE IngressRoutes set `syntax: "v3"` (application IngressRoutes do not).

Default IDE config (extensions, settings) is stored at `src/main/resources/ide-default-config.json`. An environment-scoped `ide-config` ConfigMap in the work namespace overrides it and is auto-created on first `getDefaultIDEConfig()` call.

### Application Ownership & Collaborators
`Application` has an `owner` field (User NanoId) and a `List<ApplicationCollaborator> collaborators`. `createApplication` stamps the caller's `userId` as `owner` automatically. `ApplicationResponse` includes both `owner` (ID) and `ownerName` (resolved via `UserService.getUsernameMapByIds()`).

Owners can grant other users access to their applications via collaborators (`Application.setCollaboratorUserIds()` de-duplicates and excludes the owner). The Danger Zone (cascade delete) is restricted to the owner and admins; collaborators have read/operate access but cannot delete.

### External Accounts
`ExternalAccount` entity stores linked OAuth accounts (`email`, `provider`, `providerUserId`). Used by the notification system to route pipeline status messages to the user's linked provider (e.g., Feishu).

### Domain Management
`Domain` entities store managed domains with `host`, `https`, `certMode` (`AUTO` or `UPLOADED`), and optional PEM cert/key. Domains are admin-managed at `/api/domains` (ADMIN-only writes) and listed at `/networks/domains`. Domain lookup uses longest-suffix matching (supports wildcard by stripping `*.`).

When `IngressRouteProcessor` creates Traefik IngressRoutes, it resolves the domain for each host:
- **UPLOADED**: Syncs a TLS Secret (`domain-{host}`) into the application namespace and references it in the IngressRoute TLS block.
- **AUTO**: Uses the Traefik `certResolver` from `IngressConfig`.

HTTPS applications automatically get an HTTP→HTTPS redirect Middleware (`oops-redirect-https`) applied to a companion `web` entrypoint IngressRoute.

### Pipeline Deploy Modes
`Pipeline.deployMode` is either `IMMEDIATE` (default) or `MANUAL`. With `MANUAL`, `PipelineInstanceScanJob` transitions the pipeline to `BUILD_SUCCEEDED` after the K8s Job completes but does **not** automatically deploy — a separate `deployPipeline()` call is required. With `IMMEDIATE`, it continues to `DEPLOYING → SUCCEEDED` inline.

### Sandbox Execution
The `domain/sandbox` aggregate plus `SandboxController` (`/api/sandbox`, `/openapi/sandbox`) run arbitrary commands inside isolated Kubernetes Jobs. Each execution selects a per-environment runtime image and supports configurable CPU/memory limits, timeout, and TTL after completion. Two response modes: non-streaming (poll) and streaming via SSE. Configured via `SandboxProperties` (`oops.sandbox.*`).

### Pod Filesystem Browser
`PodFileSystemController` at `/api/namespaces/{namespace}/applications/{name}/pods/{pod}/files` lists and downloads files inside a pod. Backed by `PodFileSystemProperties` (`oops.pod-filesystem.*`) — typically constrained to a base path.

## API Patterns

### Response Envelope
All REST endpoints return `Result<T>` — a record with `boolean success`, `String message`, `T data`. Use `Result.success(data)` and `Result.failure(message)`. The frontend `ApiResponse<T>` mirrors this exactly.

### URL Convention
All REST controllers are namespaced under `/api/namespaces/{namespace}/...`. Applications are keyed by `name` in URLs (not numeric id).

**Two parallel API surfaces:**
- `/api/**` — UI-facing, authenticated by `JwtAuthFilter` (Bearer JWT or `?token=` query param for WebSocket).
- `/openapi/**` — Machine/CLI-facing, authenticated by `OpenApiAuthFilter`. `SandboxController` is dual-mapped at `/api/sandbox` and `/openapi/sandbox`; `OpenApiDiscoveryController` serves discovery under `/openapi`. The repo-vendored deploy CLI (`skills/oops/scripts/oops.py`) targets this surface.

**Exceptions to namespacing:**
- `GET /api/health` — health check, no auth required (explicitly permitted in `SecurityConfig`)
- `GET /api/search/applications?keyword=&size=5` — cross-namespace application search for command palette (defaults to 5 results)
- `GET /api/nodes?env={envName}` — node list for a given environment
- `GET|POST /api/users`, `PUT|DELETE /api/users/{id}` — user management (ADMIN-only writes)
- `GET /api/users/me` — current user from JWT principal
- `/api/auth/external/**` — OAuth provider/callback endpoints and `ExternalAccount` management
- `GET /api/features` — feature flags (`feishu`, `ide`, `objectStorage`, `ideHost`, `ideHttps`)
- `POST /api/image-repositories/validations` — validate image registry credentials
- `POST /api/kubernetes/validations` — validate K8s connectivity
- `POST /api/kubernetes/namespaces` — create a K8s namespace
- `POST /api/index/pipelines`, `POST /api/index/applications` — cross-namespace queries

### Entity Identity
All entities extend `BaseDataObject` which auto-generates a 24-char NanoId via `@PrePersist` (using the `jnanoid` library). Complex collection fields (e.g. `List<EnvironmentConfig>`) are stored as JSON blobs via a custom `@AttributeConverter` — follow this pattern for new nested object collections.

### Pagination
`Page<T>` is a record `(long total, List<T> data, int size, int totalPages)`. `GET /api/namespaces/{namespace}/applications` returns `Page<ApplicationResponse>` with query params `keyword`, `page` (1-based), `size`. The frontend `Page<T>` type mirrors this exactly.

### JWT Claims
Token claims: `sub` = username, `userId` (custom), `role` (`ADMIN` or `USER`). `JwtAuthFilter` falls back to DB lookup by username if `userId` claim is absent (backward compat). `AuthUserPrincipal` is a record `(String userId, String username)` implementing `Principal`; Spring method security uses `ROLE_ADMIN` / `ROLE_USER`.

### Kubernetes Client
The `domain` layer holds no Fabric8 dependency — `Environment.KubernetesApiServer` is just a config holder (`url`, `token`). All Fabric8 `KubernetesClient` instances are constructed in `infrastructure/kubernetes/KubernetesClients.from(apiServer)`, called only by gateway implementations and per-task helpers. There is no shared client pool — clients are created per gateway call or per WebSocket connection and closed via try-with-resources or in `afterConnectionClosed`.

`application` and `interfaces` layers must NOT import `io.fabric8.*` or anything under `infrastructure.kubernetes.*`. Instead, they call K8s through `application/port/external` gateway interfaces (`ClusterNodeGateway`, `ApplicationRuntimeGateway`, `PipelineLogStreamGateway`, `TerminalSessionGateway`, etc.), each implemented by a `Kubernetes*Gateway` adapter in `infrastructure/kubernetes/`.

### Non-Bean Task Objects
`PipelineExecuteTask` is instantiated manually (not as a Spring bean) and uses `SpringContext.getBean()` to pull repositories from the application context. Follow this pattern for other manually-instantiated task objects.

## WebSocket Protocols

Three handlers registered in `WebSocketConfig` (all allow `setAllowedOrigins("*")`):

| Path | Purpose |
|---|---|
| `.../pods/{pod}/terminal` | Binary+text stdin, Fabric8 `exec` with TTY (`xterm-256color`) |
| `.../pods/{pod}/log` | Text lines, tail last 2000 lines |
| `.../pipelines/{pipelineId}/log` | JSON messages (see below) |

**Pipeline log message format:**
```json
{ "type": "steps", "data": ["clone","build","push","done"] }   // on connect
{ "type": "step", "data": "[containerName] line", "container": "containerName" }
{ "type": "error", "data": "..." }
```

All three handlers respond to text `"ping"` with `"pong"`. JWT is accepted as `?token=` query param for WebSocket connections (browsers cannot set custom headers on upgrade).

## Frontend Patterns

### Navigation
Sidebar navigation is defined in `web/lib/nav-config.ts` with four groups:
- **Cluster**: Nodes (`/nodes`)
- **Network**: Domains (`/networks/domains`)
- **App Management**: Apps (`/apps`), IDEs (`/ides`), Pipelines (`/pipelines`)
- **System Settings**: Users (`/settings/users`), Environments (`/settings/environments`), Namespaces (`/settings/namespaces`)

### Auth & Routing
JWT stored in cookie `auth_token` (SameSite=Lax, 7 days). The Next.js middleware is `web/proxy.ts` (not `middleware.ts`) — it reads the cookie and redirects unauthenticated requests to `/login`. Paths `/auth/feishu/callback` and `/api/auth/external` are exempt. `apiFetch` reads the cookie to attach `Authorization: Bearer` and hard-redirects to `/login` on HTTP 401.

Frontend also stores `userId`, `username`, and `role` in `localStorage` (keys `auth_user_id`, `auth_username`, `auth_role`). `isAdmin()` reads `auth_role` from localStorage. Login accepts username **or** email.

### Namespace State
`useNamespaceStore` (Zustand, persisted to `localStorage` key `oops-namespace`) stores the selected namespace. `NamespaceParamProvider` syncs this store bidirectionally with a `?namespace=` URL query parameter — URL takes priority, making namespace bookmarkable.

### Feature Flags
`useFeaturesStore` (not persisted) loads feature flags once on mount and exposes `{ feishu, ide, objectStorage, ideHost, ideHttps }`. Check this store before rendering IDE, Feishu-dependent, or ZIP-upload UI.

### Localization
Four locales: `zh-CN` (default), `en-US`, `zh-TW`, `ja-JP`. Stored in cookie `locale` (max-age 1 year) and `localStorage`. Use `web/locales/` for all user-facing strings. `t()` falls back to the key itself if a translation is missing.

### Command Palette
Triggered by pressing `/` (outside input/textarea). Two-stage: select command (Status, Deploy, IDEs, Pipeline, App), then search for an application via `/api/search/applications` with 150ms debounce. Most-recently-used app stored in Zustand persisted to `localStorage` key `oops:recent-app`. Backspace with empty input returns to command selection.

### Other Frontend State
- Sidebar open/closed persisted in cookie `sidebar_state` (`"true"`/`"false"`), read server-side in root layout.
- Theme via `next-themes` with `defaultTheme="system"`.
- `API_BASE_URL` is empty string in production (same-origin), `http://localhost:8080` in dev — set via `NEXT_PUBLIC_API_URL`.

## Database Migrations

OOPS uses Flyway to apply schema and data migrations automatically during application startup.

- MySQL migrations live in `src/main/resources/db/migration/`
- Migration files must be append-only and named like `V2__add_pipeline_index.sql`
- Existing databases without Flyway history are baselined at version `1`; new databases run `V1__baseline_schema.sql`
- Hibernate DDL generation is disabled with `spring.jpa.hibernate.ddl-auto=none`

## Configuration Notes

Default admin credentials: `admin` / `oops.admin.password` from `application.yml` (defaults to `admin123` if unset), created by `UserInitializer` on first startup if no admin exists.

MySQL and all other OOPS settings are provided in `application.yml`. Both compose stacks bind-mount `docker/application.yml` to `/app/config/application.yml`; the multi-service stack expects scenario A (bundled `mysql` service hostname), the local stack expects scenario B (external MySQL via `host.docker.internal` or any reachable host). There is no `.env` file in this project — `OOPS_*` / `SPRING_*` env vars are no longer used.

**All-in-one container** (`docker/all-in-one/`): Nginx (port 80) reverse-proxies `/api/` to Spring Boot (port 8080) and `/` to Next.js (port 3000). Runtime image is `node:20-slim` (not a JVM base image) — the JDK is copied from the Maven builder stage. `TZ=Asia/Shanghai` is set. The entrypoint monitors all three PIDs (Java, Node, Nginx); if any process dies it kills the others and exits non-zero.

**Multi-service stack** (`docker/docker-compose.yml`): backend uses `eclipse-temurin:25-jre-alpine`; frontend uses `node:20-slim`; nginx uses `nginx:1.27-alpine` with [docker/nginx.conf](docker/nginx.conf) bind-mounted to `/etc/nginx/conf.d/default.conf`. Upstreams use Docker DNS service names (`backend:8080`, `frontend:3000`).

**Config injection precedence**: `SPRING_CONFIG_LOCATION` env → `/app/config/application.yml` (compose mount point — Spring Boot's default external-config path when `WORKDIR=/app`) → classpath defaults. Note: the runtime `application.yml` lives in the project's top-level `./config/` directory, which is outside `src/main/resources/`, so it never ends up in the packaged JAR.

**Nginx**: `client_max_body_size 50m`. `proxy_read_timeout 1h` and `add_header X-Accel-Buffering no` on `/api/` for streaming/WebSocket. Both `nginx.conf` files share these settings.

## Testing

The project currently has minimal test coverage — only `OopsApplicationTests.java` (a smoke test) exists. No frontend tests. New backend tests go in `src/test/java/com/github/wellch4n/oops/`.

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

## Repo-Vendored Deploy CLI

`skills/oops/SKILL.md` ships a Python CLI (`skills/oops/scripts/oops.py`) that wraps the OOPS REST API for end-to-end deploys: create app → configure build → bind env → set service → set runtime → trigger pipeline → wait for rollout. It targets the `/openapi/**` surface and is the preferred entry point when an agent needs to drive OOPS programmatically — prefer it over hand-rolled curl chains. The skill enforces a per-step confirmation policy for write actions.

## Collaboration

List Claude as a co-author when committing.
