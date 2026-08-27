# OOPS Go Backend (migration in progress)

A Go rewrite of the OOPS backend, migrated with the **strangler pattern**: this
process serves the same `/api` surface with wire-compatible responses, shares
the same MySQL schema (Flyway migrations stay owned by the Java side until
cut-over), reads the same `config/application.yml`, and signs/verifies the same
HS256 JWTs — so nginx can shift routes over one at a time while the Java
backend keeps serving everything not yet ported, and the Next.js frontend
never changes.

## Stack

- **Gin** for HTTP (route groups for `/api` auth + admin tiers; `/openapi` later)
- `database/sql` + go-sql-driver (hand-written SQL, no ORM)
- **client-go** for Kubernetes (the `internal/k8s` gateway mirrors
  `infrastructure/kubernetes`, including Fabric8's kubeconfig fallback when an
  Environment has no API server configured)
- stdlib AES-256-GCM in `internal/crypto`, byte-compatible with
  `EncryptionUtils` (key = SHA-256(secret), payload = base64(iv‖ciphertext))

## Layout

Idiomatic Go layering rather than a copy of the Java four-layer DDD tree —
package-per-concern under `internal/`, with the ports idea kept (handlers call
`store`/`k8s` through their exported APIs; as the K8s surface grows the domain
model splits into its own package):

```
cmd/oops/          entrypoint (flags: -config, -listen)
internal/config/   shared application.yml loader, JDBC→DSN translation
internal/httpapi/  Gin server: envelopes, JWT middleware, handlers  (≈ interfaces/)
internal/store/    MySQL adapter, LocalDateTime + NanoId helpers    (≈ persistence)
internal/k8s/      client-go cluster gateway                        (≈ infrastructure/kubernetes)
internal/crypto/   AES-GCM codec for environment secrets
```

## Run

```bash
go build -o oops-go ./cmd/oops
./oops-go -config ../config/application.yml -listen :8081
```

The repo's `.claude/launch.json` has a `web-go` configuration that starts the
frontend dev server pointed at this backend.

## Verified compatibility (live, against the running Java backend)

- JWT: identical claim set (`sub`, `userId`, `role`); tokens signed by either
  backend are accepted by the other.
- Passwords: BCrypt hashes written by Java verify unchanged.
- Envelopes: `Result{success,message,data}` (including `"message":null` on
  success) and `Page{total,data,size,totalPages}`.
- Timestamps: rendered as Java `LocalDateTime` (`2026-06-24T17:44:28.724532`),
  byte-identical to the Jackson output.
- `/api/nodes` returns byte-identical output to the Java gateway against a
  real cluster (allocatable quantities, Ki→MB formatting, first-InternalIP).
- The full Next.js frontend runs against this backend: login, apps list
  (owners, collaborators, source types), users, namespaces, environments,
  domains, and cluster nodes pages all render with every request returning 200.

## Ported endpoints

| Endpoint | Notes |
|---|---|
| `GET /api/health` | |
| `POST /api/auth/login` | username or email + BCrypt, same failure messages |
| `GET /api/users/me` | excludes the password hash (the Java side currently leaks it) |
| `GET /api/users/page` | admin user listing; never exposes other users' access tokens |
| `GET /api/features` | flags read from the shared yml |
| `GET /api/namespaces` | list |
| `POST/PUT /api/namespaces` | admin-only create/update with resource-name validation |
| `GET /api/namespaces/{ns}/applications` | paged list with owner/collaborator names + sourceType; ordering simplified to `created_time DESC` until pipeline tables are fully ported |
| `GET /api/namespaces/{ns}/applications/active-deployments` | in-flight pipelines (RUNNING/DEPLOYING/ROLLING_OUT), `all` spans namespaces |
| `GET /api/search/applications` | command-palette search |
| `GET /api/environments` | secrets redacted until the write path ports over |
| `GET /api/domains` | tolerates a pre-V21 schema (Java owns migrations) |
| `GET /api/nodes?env=` | client-go; decrypts the environment token via `internal/crypto` |

## Roadmap (per migration plan)

1. ✅ Vertical slice: config/auth/users/features/application list — frontend
   verified in the browser end to end
2. ✅ (started) K8s read path: nodes; next application status/resources,
   metrics history (Prometheus `query_range` over the API-server proxy)
3. Write paths: application/environment CRUD (needs `crypto.Encrypt` wiring),
   deploy processor chain, pipeline Job orchestration, optimistic-lock state
   machine
4. WebSockets: pod terminal/log, pipeline log, sandbox terminal
5. `/openapi` surface, schedulers, alerts, Feishu (verify
   `larksuite/oapi-sdk-go` WS event long connection), sandbox, IDE, object
   storage — then retire the Java backend

## Measurements (same machine, 2026-08-27)

| | Java (full backend) | Go (this slice) |
|---|---|---|
| Physical footprint | 528 MB (peak 708 MB) | 14 MB |
