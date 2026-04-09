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

## API Patterns

### Response Envelope
All REST endpoints return `Result<T>` — a record with `boolean success`, `String message`, `T data`. Use `Result.success(data)` and `Result.failure(message)`. The frontend `ApiResponse<T>` mirrors this exactly.

### URL Convention
All REST controllers are namespaced under `/api/namespaces/{namespace}/...`. Applications are keyed by `name` in URLs (not numeric id).

### Entity Identity
All entities extend `BaseDataObject` which auto-generates a 24-char NanoId via `@PrePersist` (using the `jnanoid` library). Complex collection fields (e.g. `List<EnvironmentConfig>`) are stored as JSON blobs via a custom `@AttributeConverter` — follow this pattern for new nested object collections.

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
JWT stored in cookie `auth_token` (SameSite=Lax, 7 days). `web/middleware.ts` (Next.js middleware) reads the cookie and redirects unauthenticated requests to `/login`. `apiFetch` reads the cookie to attach `Authorization: Bearer`.

### Namespace State
`useNamespaceStore` (Zustand, persisted to `localStorage` key `oops-namespace`) stores the selected namespace. `NamespaceParamProvider` syncs this store bidirectionally with a `?namespace=` URL query parameter — URL takes priority, making namespace bookmarkable.

### Feature Flags
`useFeaturesStore` (not persisted) loads feature flags once on mount and exposes `{ feishu, ide }` booleans. Check this store before rendering IDE or Feishu-dependent UI.

### Localization
Two locales: `zh` (default) and `en`. Stored in cookie `locale`. Use `web/locales/` for all user-facing strings.

## Configuration Notes

Default admin credentials: `admin` / password from env `ADMIN_PASSWORD` (defaults to `admin123`), created by `UserInitializer` on first startup if no admin exists.

MySQL template available at `docker/application-mysql.properties`. Switch by setting `SPRING_CONFIG_LOCATION`.

In production container, Nginx (port 80) reverse-proxies `/api/` to Spring Boot (port 8080) and `/` to Next.js (port 3000). Custom `application.properties` can be injected at `/app/config/application.properties`.

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
