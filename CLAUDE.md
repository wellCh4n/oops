# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OOPS is a Kubernetes-based PaaS (Platform as a Service) for deploying applications. It provides a web UI for managing applications, environments, pipelines (CI/CD builds), and IDE instances backed by code-server.

## Architecture

### Backend (Spring Boot)

**Java version:** 21  
**Spring Boot:** 3.5.3

Key components:

- **Controllers** (`src/main/java/.../controller/`): REST API and WebSocket handlers
- **Services** (`src/main/java/.../service/`): Business logic layer
- **Data/Entities** (`src/main/java/.../data/`): JPA entities and repositories
- **Pipeline System** (`task/`, `container/`, `pod/`): Kubernetes Job-based build pipeline using Kaniko
- **Multi-host support**: Environment entity stores K8s API server credentials, allowing management of multiple clusters

Key technologies:
- Fabric8 Kubernetes client for K8s operations
- SQLite (default) or MySQL for persistence
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
# Run the application (requires application.properties)
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

```bash
# Full application build (multi-stage Dockerfile)
docker build -t oops .
```

## Configuration

Copy `src/main/resources/application.properties.example` to `application.properties` and configure:

- `spring.datasource.url`: SQLite (default) or MySQL connection
- `oops.jwt.secret`: JWT signing key (min 32 chars)
- `oops.pipeline.image.*`: Clone and Kaniko images for builds
- `oops.ingress.cert-resolver`: Traefik certificate resolver name
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
1. **clone**: Clones source code from git
2. **build** (optional): Runs custom build commands
3. **push**: Builds and pushes Docker image using Kaniko

Pipeline logs are streamed via WebSocket (`PipelineLogWebSocketHandler`). A `@Scheduled(fixedRate=5000)` job (`PipelineInstanceScanJob`) polls K8s for job completion and drives pipeline state. Pipeline state transitions use optimistic locking: `PipelineRepository.updateStatusIfMatch()` does a conditional UPDATE and returns row count (0 = lost the race).

### Application Deployment
Applications deploy as **StatefulSets** (not Deployments) with fixed internal service port `1114`. Ingress uses **Traefik IngressRoute CRDs** (not standard Kubernetes Ingress) — the code checks for CRD existence and skips gracefully if absent. ConfigMap named after the application is injected via `envFrom: configMapRef`.

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

### Application Ownership
`Application` has an `owner` field (User NanoId). `createApplication` stamps the caller's `userId` automatically. `ApplicationResponse` includes both `owner` (ID) and `ownerName` (resolved via `UserService.getUsernameMapByIds()`).

### Pipeline Deploy Modes
`Pipeline.deployMode` is either `IMMEDIATE` (default) or `MANUAL`. With `MANUAL`, `PipelineInstanceScanJob` transitions the pipeline to `BUILD_SUCCEEDED` after the K8s Job completes but does **not** automatically deploy — a separate `deployPipeline()` call is required. With `IMMEDIATE`, it continues to `DEPLOYING → SUCCEEDED` inline.

## API Patterns

### Response Envelope
All REST endpoints return `Result<T>` — a record with `boolean success`, `String message`, `T data`. Use `Result.success(data)` and `Result.failure(message)`. The frontend `ApiResponse<T>` mirrors this exactly.

### URL Convention
All REST controllers are namespaced under `/api/namespaces/{namespace}/...`. Applications are keyed by `name` in URLs (not numeric id).

**Exceptions to namespacing:**
- `GET /api/health` — health check, no auth required (explicitly permitted in `SecurityConfig`)
- `GET /api/search/applications?keyword=&size=5` — cross-namespace application search for command palette (defaults to 5 results)
- `GET /api/nodes?env={envName}` — node list for a given environment
- `GET|POST /api/users`, `PUT|DELETE /api/users/{id}` — user management (ADMIN-only writes)
- `GET /api/users/me` — current user from JWT principal
- `/api/auth/external/**` — OAuth provider/callback endpoints

### Entity Identity
All entities extend `BaseDataObject` which auto-generates a 24-char NanoId via `@PrePersist` (using the `jnanoid` library). Complex collection fields (e.g. `List<EnvironmentConfig>`) are stored as JSON blobs via a custom `@AttributeConverter` — follow this pattern for new nested object collections.

### Pagination
`Page<T>` is a record `(long total, List<T> data, int size, int totalPages)`. `GET /api/namespaces/{namespace}/applications` returns `Page<ApplicationResponse>` with query params `keyword`, `page` (1-based), `size`. The frontend `Page<T>` type mirrors this exactly.

### JWT Claims
Token claims: `sub` = username, `userId` (custom), `role` (`ADMIN` or `USER`). `JwtAuthFilter` falls back to DB lookup by username if `userId` claim is absent (backward compat). `AuthUserPrincipal` is a record `(String userId, String username)` implementing `Principal`; Spring method security uses `ROLE_ADMIN` / `ROLE_USER`.

### Kubernetes Client
A new `KubernetesClient` is created per WebSocket connection / service call via `environment.getKubernetesApiServer().fabric8Client()`. There is no shared client pool. Clients are closed in `afterConnectionClosed`.

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

### Auth & Routing
JWT stored in cookie `auth_token` (SameSite=Lax, 7 days). The Next.js middleware is `web/proxy.ts` (not `middleware.ts`) — it reads the cookie and redirects unauthenticated requests to `/login`. Paths `/auth/feishu/callback` and `/api/auth/external` are exempt. `apiFetch` reads the cookie to attach `Authorization: Bearer` and hard-redirects to `/login` on HTTP 401.

Frontend also stores `userId`, `username`, and `role` in `localStorage` (keys `auth_user_id`, `auth_username`, `auth_role`). `isAdmin()` reads `auth_role` from localStorage. Login accepts username **or** email.

### Namespace State
`useNamespaceStore` (Zustand, persisted to `localStorage` key `oops-namespace`) stores the selected namespace. `NamespaceParamProvider` syncs this store bidirectionally with a `?namespace=` URL query parameter — URL takes priority, making namespace bookmarkable.

### Feature Flags
`useFeaturesStore` (not persisted) loads feature flags once on mount and exposes `{ feishu, ide }` booleans. Check this store before rendering IDE or Feishu-dependent UI.

### Localization
Two locales: `zh` (default) and `en`. Stored in cookie `locale` (max-age 1 year) and `localStorage`. Use `web/locales/` for all user-facing strings. `t()` falls back to the key itself if a translation is missing.

### Command Palette
Triggered by pressing `/` (outside input/textarea). Two-stage: select command (Status, Deploy, IDEs, Pipeline, App), then search for an application via `/api/search/applications` with 150ms debounce. Most-recently-used app stored in Zustand persisted to `localStorage` key `oops:recent-app`. Backspace with empty input returns to command selection.

### Other Frontend State
- Sidebar open/closed persisted in cookie `sidebar_state` (`"true"`/`"false"`), read server-side in root layout.
- Theme via `next-themes` with `defaultTheme="system"`.
- `API_BASE_URL` is empty string in production (same-origin), `http://localhost:8080` in dev — set via `NEXT_PUBLIC_API_URL`.

## Configuration Notes

Default admin credentials: `admin` / password from env `ADMIN_PASSWORD` (defaults to `admin123`), created by `UserInitializer` on first startup if no admin exists.

MySQL template available at `docker/application-mysql.properties`. Switch by setting `SPRING_CONFIG_LOCATION`.

In production container, Nginx (port 80) reverse-proxies `/api/` to Spring Boot (port 8080) and `/` to Next.js (port 3000). Custom `application.properties` can be injected at `/app/config/application.properties`.

**Docker runtime image**: `node:20-slim` (not a JVM base image) — the JDK is copied from the Maven builder stage. `TZ=Asia/Shanghai` is set. The entrypoint monitors all three PIDs (Java, Node, Nginx); if any process dies it kills the others and exits non-zero.

**Config injection precedence**: `SPRING_CONFIG_LOCATION` env → `/app/config/application.properties` → `/app/application.properties` → built-in defaults.

**Nginx**: `client_max_body_size 50m`. `proxy_read_timeout 1h` and `add_header X-Accel-Buffering no` on `/api/` for streaming/WebSocket.

## Testing

The project currently has minimal test coverage — only `OopsApplicationTests.java` (a smoke test) exists. No frontend tests. New backend tests go in `src/test/java/com/github/wellch4n/oops/`.

## Code Style

### Backend
- Lombok for boilerplate reduction
- Constructor injection for dependencies
- Virtual threads for concurrent tasks (`Thread.ofVirtual()`, `Thread.startVirtualThread()`)

### Frontend
- ESLint config in `web/eslint.config.mjs`
- TypeScript strict mode enabled
- Tailwind CSS for styling
- Component imports use `@/components/` path alias
- Try shadcn/ui components first. Use the auto-install feature: `npx shadcn add <component>`
