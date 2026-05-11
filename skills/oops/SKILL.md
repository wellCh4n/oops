---
name: oops
description: Deploy applications to Kubernetes via OOPS PaaS using the Python CLI script. Use when the user asks to deploy/release/ship an application to OOPS, create a new OOPS app, inspect a pipeline, configure an app's build/service/runtime/env-vars, or mentions oops, ZIP/Git deploys, helloworld deploys, namespace/environment/pipeline/configmap.
---

# OOPS — Deploy applications to Kubernetes via CLI

OOPS is a Kubernetes-based PaaS. This skill teaches the agent how to use the
repo-vendored Python script `skills/oops/scripts/oops.py` to drive the end-to-end
loop: create app → configure build → bind env → set service → set runtime →
trigger pipeline → wait for rollout.

## When to use

The user says things like "deploy X to oops", "create a new app on oops",
"show me the pipeline for web-test on oops", "ship this zip to oops" — use
this skill.

## When NOT to use

- The user is developing/debugging OOPS itself → this skill is for
  *operating* OOPS, not contributing to it. Point them at the OOPS repo's
  CLAUDE.md (https://github.com/wellCh4n/oops/blob/main/CLAUDE.md) instead.
- The user wants to operate the cluster directly with `kubectl` → do not go
  through OOPS.

## Prerequisites

### 1. Locate the script

The CLI is a single Python script (`skills/oops/scripts/oops.py`) with no third-party dependencies. Invoke from the repo root:

```bash
python skills/oops/scripts/oops.py <subcommand>
```

No compilation, no `pip install`, no build step needed.

### 2. Authentication

The script **only accepts a pre-issued access token** — no username/password
login. Tokens look like `sk-oops-xxxxxxxxxxxxxxxx` and are issued by the
user in the OOPS UI ("Reset Access Token").

Three ways to pass it (highest precedence first):

```bash
# A. Environment variables (recommended for one session)
export OOPS_ENDPOINT=http://localhost:8080
export OOPS_TOKEN=sk-oops-...

# B. Per-invocation flags
python skills/oops/scripts/oops.py --endpoint http://localhost:8080 --token sk-oops-... ns ls

# C. Persist to ~/.oops/config.toml
python skills/oops/scripts/oops.py auth set --endpoint http://localhost:8080 --token sk-oops-...
python skills/oops/scripts/oops.py auth status   # verify
```

**If the user has not provided a token, stop and ask** — do not guess an
endpoint or invent a token.

## End-to-end deploy workflow

Below is the smallest verified loop. Every step matters; skipping any one
breaks the deploy in subtle ways.

### Step 1 — Discover namespace, environment, domain

```bash
python skills/oops/scripts/oops.py ns ls       # list namespaces
python skills/oops/scripts/oops.py env ls      # list environments (redacted — no K8s token leaked)
python skills/oops/scripts/oops.py domain ls   # list managed domains for service exposure
```

Note down the target namespace (e.g. `default`) and environment (e.g.
`dev`) — every later command needs them.

### Step 2 — Create the application

```bash
python skills/oops/scripts/oops.py app create -n <namespace> <app-name> --description "<one-line summary>"
```

- `<app-name>` must follow Kubernetes naming (lowercase alphanum + dashes).
- If the app already exists the command errors out. **Use `python skills/oops/scripts/oops.py app get -n <ns> <app-name>` first to check.**

### Step 3 — Configure build (most common pitfall)

#### ZIP source (most common)

```bash
python skills/oops/scripts/oops.py app build set -n <ns> <app> \
    --source zip \
    --dockerfile-type user \
    --dockerfile-content "$(cat path/to/Dockerfile)"
```

**Hard constraint**: `--dockerfile-type user` **requires**
`--dockerfile-content` (inline text). `--dockerfile-path` alone is not
enough. The Dockerfile content is injected into the build env on every
deploy, so the ZIP itself does **not** need to contain a Dockerfile.

#### Git source

```bash
python skills/oops/scripts/oops.py app build set -n <ns> <app> \
    --source git \
    --repository "https://github.com/owner/repo.git" \
    --build-image "maven:3.9-eclipse-temurin-21-alpine" \
    --dockerfile-type builtin \
    --dockerfile-path "Dockerfile"
```

`builtin` reads the real Dockerfile from the repository.

### Step 4 — Bind to environments

```bash
python skills/oops/scripts/oops.py app env set -n <ns> <app> --env <env-name>
# multi-env: --env dev --env prod
```

Without an env binding, deploy fails.

### Step 5 — Configure service exposure

```bash
python skills/oops/scripts/oops.py app service set -n <ns> <app> \
    --port <container-port> \
    --env-host <env>=<host>[:https]
# example: --env-host dev=hello.example.com:https
```

The host should be a subdomain under a managed domain from `python skills/oops/scripts/oops.py domain ls`.
For example if `example.com` is managed, set the host to `hello.example.com`.

### Step 6 — Configure runtime (**easy to forget; replicas default to 0**)

```bash
python skills/oops/scripts/oops.py app runtime set -n <ns> <app> \
    --env "<env>=cpu:1/2,mem:64/128,replicas:1"
```

Strict format: `cpu:<request>/<limit>,mem:<request>/<limit>,replicas:<n>`.
CPU is in cores, no unit suffix (e.g. `0.1`, `0.5`, `1`, `2`). Memory is a plain number in Mi, no suffix (e.g. `64`, `128`) — OOPS appends `Mi` automatically.
**Skip this and replicas stays at 0 — the Pod never starts.** This is the
most common cause of "deploy succeeded but I can't reach the app".

### Step 7 — Trigger the deploy

#### ZIP mode

```bash
# Package the source first (enter the dir, then zip its contents)
cd path/to/source && zip -r /tmp/<app>.zip .

# One shot: upload + trigger + wait for rollout
python skills/oops/scripts/oops.py deploy zip -n <ns> <app> --env <env> --file /tmp/<app>.zip --wait
```

`--wait` polls pipeline status until `SUCCEEDED` / `FAILED` / `ERROR`.
**Without `--wait` the command returns immediately after triggering** and
does not surface the build result.

#### Git mode

```bash
python skills/oops/scripts/oops.py deploy git -n <ns> <app> --env <env> --branch main --wait
```

Uses the repository configured in step 3; `--branch` is optional.

### Step 8 — Verify

```bash
python skills/oops/scripts/oops.py pipeline ls -n <ns> <app>   # recent runs
python skills/oops/scripts/oops.py app get -n <ns> <app>       # app summary
```

## Read-only inspection commands

```bash
python skills/oops/scripts/oops.py app build get -n <ns> <app>                # build config
python skills/oops/scripts/oops.py app service get -n <ns> <app>              # service config
python skills/oops/scripts/oops.py app runtime get -n <ns> <app>              # runtime spec
python skills/oops/scripts/oops.py app env ls -n <ns> <app>                   # env bindings
python skills/oops/scripts/oops.py app config get -n <ns> <app> --env <env>   # ConfigMap entries
python skills/oops/scripts/oops.py pipeline get -n <ns> <app> <pipeline-id>   # single pipeline detail
```

## Output format

All commands default to human-readable tables. For programmatic parsing
pass `--json`:

```bash
python skills/oops/scripts/oops.py --json app ls -n default | jq '.[].name'
```

`--json` is a **global flag** and must appear before the subcommand.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `401 Unauthorized` | Token wrong or unset | Run `auth status`; re-run `auth set` |
| `405 Method Not Allowed` | Tried to delete a resource | `/openapi` intentionally blocks DELETE. Ask the user to delete in the UI. |
| `Dockerfile content is required when type is USER` | Missing `--dockerfile-content` | See the hard constraint in step 3. |
| `Application is being deployed` | Previous deploy still in flight | Wait; check `pipeline ls`; or `pipeline stop`. |
| Pipeline `SUCCEEDED` but Pod count is 0 | Runtime spec never set (replicas=0) | Run step 6, then **re-deploy**. |
| External request times out but `kubectl exec … wget localhost` works | Ingress / DNS issue, outside OOPS | Check DNS / Traefik — not in scope here. |
| Second deploy reports `ERROR` but the Pod is healthy | Known OOPS edge case in rollout-state judgment | Check `kubectl get pod`; if `Running` you're fine. |

## Hard boundaries

- **Do not try to delete applications via CLI** — `/openapi` deliberately
  blocks DELETE. Let the user delete in the UI.
- **Do not pick namespace / env / host on behalf of the user** — list them
  with `ns ls` / `env ls` / `domain ls`, and ask if the user didn't say.
- **Pre-deploy checklist**: build ✓ env ✓ service ✓ runtime ✓ — miss any
  one and the deploy either fails or the app is unreachable.
- **When zipping the source, `cd` into the source dir first, then `zip -r
  /tmp/foo.zip .`** — don't include the outer directory or the build
  container won't find the Dockerfile.
