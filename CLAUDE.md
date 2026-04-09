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

Pipeline logs are streamed via WebSocket (`PipelineLogWebSocketHandler`).

### Application Deployment
Applications deploy as StatefulSets with:
- Configurable replicas, CPU/memory limits per environment
- Service exposure (internal cluster DNS + optional external ingress)
- ConfigMap mounting for configuration files

### IDE Integration
Optional code-server integration creates IDE instances as StatefulSets with IngressRoute for access.

## Testing

Backend tests use Spring Boot Test. The project currently has minimal test coverage - the main test file is `OopsApplicationTests.java`.

To add new tests:
- Place in `src/test/java/com/github/wellch4n/oops/`
- Use `@SpringBootTest` for integration tests
- Mock Kubernetes client for unit tests

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
