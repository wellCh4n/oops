# OOPS
> Kubernetes Is All You Need

![icon.png](web/public/icon.png)

OOPS is a lightweight Kubernetes-based PaaS (Platform as a Service) that provides a web UI for deploying and managing containerized applications across multiple clusters.

## Features

### Application Management
- Deploy applications as StatefulSets with automatic service and ingress configuration
- Manage environment variables via ConfigMap injection
- Support for multiple replicas and resource configuration
- Real-time pod status monitoring

### Multi-cluster Support
- Manage multiple Kubernetes clusters (Environments) from a single interface
- Per-environment API server credentials and namespace isolation
- Deploy applications to any configured cluster

### CI/CD Pipelines
- Git-based build pipelines powered by Kubernetes Jobs
- Three-stage pipeline: **clone** → **build** → **push** (Kaniko image build)
- Real-time log streaming via WebSocket
- Pipeline history and status tracking

### Pod Operations
- Live pod log streaming
- In-browser terminal access (full TTY support via xterm.js)
- Pod lifecycle management

### IDE Integration (Optional)
- Browser-based code-server IDE instances as StatefulSets
- Persistent workspace volumes per developer
- Toggle via `oops.ide.enabled=true`

### Authentication
- Built-in username/password authentication with JWT
- Optional Feishu (Lark) OAuth integration
- Namespace-based resource isolation

### Ingress
- Traefik IngressRoute CRD support for automatic HTTPS routing
- Gracefully skips ingress setup if Traefik CRDs are absent

## Requirements

- Kubernetes cluster
- SQLite (default) or MySQL database
- Traefik (optional, for ingress/HTTPS)

## Quick Start

1. Copy and configure `src/main/resources/application.properties.example`
2. Build and run:

```bash
# Run backend
./mvnw spring-boot:run

# Run frontend (dev)
cd web && pnpm install && pnpm dev
```

Or build the full Docker image:

```bash
docker build -t oops .
```

Default admin credentials: `admin` / `admin123` (override via `ADMIN_PASSWORD` env)

## Snapshots

![app_deploy.png](snapshots/app_deploy.png)

![pod_status.png](snapshots/pod_status.png)

![pod_terminal.png](snapshots/pod_terminal.png)

![env_manager.png](snapshots/env_manager.png)
