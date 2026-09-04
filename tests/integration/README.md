# Integration suite

Black-box tests against the OOPS HTTP, SSE and WebSocket API. They import nothing from
the backend and know nothing about its language, so the same suite is the
acceptance criteria for the current Java service, a Go or Rust rewrite, or a
GraalVM native build.

That portability is the point. The Kubernetes layer — roughly 5.7k lines across
15 gateways — carries almost no unit tests today because it needs a live API
server, and that is exactly the layer any rewrite gets wrong. These tests pin the
observable behaviour while a correct implementation still exists to check against.

## Two tiers

| Tier | Needs | Covers |
|---|---|---|
| contract | the compose middleware | the wire contract, users and authorisation, domains, application configuration round trips, environment management, object storage presigning, git branch listing |
| `cluster` | the above plus k3s | build and deploy pipelines, pipeline operations, both registry credential paths, git clone with and without credentials, ingress routes and basic auth, deletion in the cluster, sandboxes, pod filesystem, pipeline step and log event streams, pod log and terminal sockets |

Organised by scenario rather than by endpoint: what a rewrite has to preserve is
behaviour, and every defect this suite found needed a full journey to surface.
Coverage is then enforced from the other direction — `test_zz_coverage.py`
compares the routes the run actually called against a table extracted from the
controllers by `scripts/extract_routes.py`, and fails naming any endpoint that no
scenario reached. Add a controller and the suite goes red until a scenario
exercises it.

The contract tier runs anywhere and is fast. It catches the quiet regressions — a
timestamp that grew a zone suffix, a page that became 0-based, an error that
started arriving as an HTTP 500 instead of `success: false`.

## Running

One command, from a clean checkout:

```bash
cd tests/integration
./run.sh                          # everything, then tears it all down
./run.sh --module ingress         # one module
./run.sh --module deploy,ingress  # a few
./run.sh --list                   # what the modules are
./run.sh --contract               # every module, minus the tests that need a cluster
./run.sh --keep                   # leave the stack up to poke at a failure
```

It creates the virtualenv, starts MySQL, RustFS, Gitea and two registries, builds
the backend if the jar is missing, runs it in a container, starts a k3s cluster,
registers it as an environment, runs the suite, writes a report, and removes all
of it again — including when you interrupt it with Ctrl-C.

Nothing is installed on the machine. Docker is the only prerequisite: every part
of the stack is a compose service, the middleware listens on non-standard ports
bound to 127.0.0.1, and the kubeconfig is written to `scripts/.k3s/` rather than
merged into `~/.kube/config`.

### Modules

A module is one test file, named after what it covers, and nothing declares the
list: `test_deploy.py` *is* the `deploy` module and its first docstring line is
its description, so adding a file adds a module with no registry to keep in step.

Naming modules that contain no cluster test also skips starting k3s, which turns
a three-minute setup into a twenty-second one:

```
$ ./run.sh --module domain,user
#6 cluster
#6 SKIPPED nothing selected needs one
```

Anything after `--` goes straight to pytest, for the cases a module is too coarse
for: `./run.sh --module ingress -- -k basic_auth -x`.

The backend runs in a container rather than on the host on purpose. A presigned
URL is signed for one hostname and redeemed by both the backend and the pipeline
pods, so they have to resolve it the same way. With everything in Docker they
share the network and use service names; on the host it would take a machine's
LAN address, which is absent offline, changes on a VPN, and differs on every CI
runner.

## Watching a run

The run prints as numbered steps, the way `docker build` does, and makes the
same split between what is worth keeping and what is only worth watching.

Headings, results and failures are **committed**: they stay on screen for the
rest of the run. Log output is **transient**: the last few lines of it sit in a
small region that redraws in place and erases itself the moment the step ends.
So a forty-minute run leaves a page you can read rather than forty minutes of
container plumbing, and you can still watch the plumbing while it happens.

```
#2 middleware — mysql, rustfs, gitea, two registries
#2  Container oops-integration-mysql Healthy      <- these scroll in place,
#2  Container oops-integration-gitea Waiting         then disappear
#2 DONE 22s

#7 [10/20] ingress — Exposing an application on a host: IngressRoutes, TLS and basic auth.
#7 5 tests
#7   OK     92.1s  an http host gets one route on the web entrypoint
#7 > an https host gets a redirect and a serving route     <- so do these two,
#7   . waiting for the deploy to finish (45s of 900s)         when the test ends
#7 DONE 342.0s  5 passed
```

A test that sits waiting on a deploy says what it is waiting for while it waits,
which is the difference between slow and hung. That comes from `wait_until`, so
it covers every deploy, rollout and readiness wait without a test asking for it.

With no terminal to redraw — a pipe, a CI log, `TERM=dumb` — nothing is erased
and every line is simply printed, the same fallback `docker build` makes. So
`./run.sh > run.log` keeps the whole transcript.

pytest's own output is in none of that. It goes to `reports/<timestamp>/`, along
with everything else, announced when the run starts and kept afterwards:

```bash
tail -f tests/integration/reports/*/pytest.log      # the test output
tail -f tests/integration/reports/*/backend.log     # what OOPS is doing
docker logs -f oops-integration-backend             # the same, live
```

The backend log is followed from the moment the backend answers, rather than
collected at teardown, so it survives the container — including the case that
needs it most, where the container dies mid-run and every remaining test fails
on a refused connection. `--keep` leaves the stack up so you can also reach the
API and the cluster.

`run.sh` writes a self-contained HTML report and a JUnit XML to `reports/`,
symlinked as `reports/latest.html` and `reports/latest.xml`, then prints a
summary. It exits non-zero when anything failed, so it drops straight into CI.

```
====================================================================
                       OOPS integration report
====================================================================
  passed   15
  total    15   in 1.0s
====================================================================
PASSED — all 15 tests
```

To drive pytest directly instead:

```bash
pytest -m "not cluster"
pytest --environment integration
```

Configuration comes from flags or environment variables:

| Variable | Flag | Default |
|---|---|---|
| `OOPS_ENDPOINT` | `--endpoint` | `http://localhost:8080` |
| `OOPS_NAMESPACE` | `--namespace` | `integration` |
| `OOPS_ENVIRONMENT` | `--environment` | the only registered environment, if there is exactly one |
| `OOPS_USERNAME` / `OOPS_PASSWORD` | — | `admin` / `admin123` |
| `OOPS_TEST_REPOSITORY` | — | a small public repo with a root Dockerfile |
| `OOPS_TEST_DEPLOY_TIMEOUT` | — | `900` seconds |

Cluster tests skip rather than fail when no environment can be resolved, so the
contract tier still runs on a bare stack.

## The cluster

k3s, as a compose service behind the `cluster` profile, so the whole stack is one
`docker compose` file and one network. `scripts/cluster-provision.sh` then does
the rest: waits for the API server, creates a cluster-admin service account and a
long-lived token, and waits for the Traefik CRDs to register.

```bash
docker compose -f docker-compose.yml --profile cluster up -d --wait k3s
eval "$(./scripts/cluster-provision.sh | grep '^  export' | sed 's/^  //')"
python scripts/register_environment.py --name integration
pytest --environment integration
```

Nothing lands on the host. The kubeconfig is written to `scripts/.k3s/` rather
than merged into `~/.kube/config`, so your real cluster contexts are untouched,
and everything else lives in Docker.

k3s rather than kind for one reason that matters here: it ships Traefik, so the
`traefik.io` CRDs exist and `IngressRouteProcessor` actually runs. Under kind
every deploy took the "no host configured, skipping ingress route creation"
branch, and the entire ingress path — TLS, the HTTP→HTTPS redirect middleware,
basic auth — was never executed once. That is also where the pruning defect the
`ingress` module now covers had been hiding.

The provisioning script injects the node's IPv4 into CoreDNS's `NodeHosts`.
Docker Desktop synthesises an IPv6 answer for container names, which pods then
prefer, and a build pod that resolves the registry over IPv6 fails with a bare
`EOF` that says nothing about address families.

### Two registries, on purpose

The stack runs both an anonymous registry and one behind htpasswd, because
the two configurations take completely different paths through OOPS. With
credentials, `syncImagePullSecret` writes a `dockerhub` secret, buildah mounts it
to authenticate its push, the pull secret processor copies it into the
application namespace, and the kubelet pulls with it — four steps that can each
fail alone. Without credentials, no secret is written and every consumer has to
tolerate its absence. Testing only the anonymous half would have left the entire
credential chain unexercised.

`registry:2` with htpasswd rather than Harbor: the same authentication protocol,
without several gigabytes of Postgres, Redis and portal for project and robot
account semantics OOPS does not use.

### Field shapes that are easy to get wrong

Two asymmetries the suite pins down, both found by running against the real
service:

- **Memory is unit-less, CPU is not.** `cpuRequest` / `cpuLimit` take full
  Kubernetes quantity strings (`500m`), while `memoryRequest` / `memoryLimit`
  take a bare number of MiB — the gateway appends `Mi` itself. Sending `256Mi`
  produces `256MiMi` and the API server rejects the StatefulSet with
  `unable to parse quantity's suffix`.
- **A missing application is not an error.** `GET .../applications/{name}` for a
  name that does not exist answers HTTP 200 with `success: true` and `data:
  null`, not a 404 and not `success: false`.

### The registry credentials are set on purpose

`register_environment.py` defaults the registry username and password to `oops`
even though the anonymous registry ignores them, so the default environment
exercises the credentialed path: `syncImagePullSecret()` writes the secret,
buildah mounts it, and the pull secret processor copies it into the application
namespace. The credential-free path is then reached deliberately, by
`test_registry.py` registering its own environment with both fields blank.

That asymmetry used to be fatal rather than merely untested — see the first
bullet under *Everything else*.

## What the suite found

Written against a working implementation, so anything it turned up is a real
defect rather than a porting mistake.

### Cluster takeover from any user account

`GET /api/environments` carries no role restriction and returns
`kubernetesApiServer.token` in clear text. That is the cluster-admin service
account the environment was registered with, so any account that can log in to
OOPS — a plain `USER` included — can read it and then drive the Kubernetes API
server directly as cluster-admin, entirely outside OOPS and outside its audit
trail.

Encrypting the column at rest does not help, because the API decrypts it on the
way out. The fix is for `EnvironmentDto` to omit the secret fields, the way the
editor already treats a blank value as "keep what is stored". Pinned by
`test_cluster_credentials_are_returned_in_clear_text_to_any_user`.

### Arbitrary container execution from any user account

`oops.sandbox.images` reads as an allowlist and is described as one, but
`SandboxRuntimeService` only has `list()` — it populates the picker and nothing
on the execution path ever compares the requested image against it. Any
authenticated user can post an arbitrary image to `/api/sandbox/executions` and
have it run as a Job in the cluster. Pinned in `test_sandbox.py`, written to
fail if enforcement is added.

### Privilege escalation from any user account

`GET /api/users` is annotated `@PreAuthorize("isAuthenticated()")` and serialises
the `User` aggregate as it stands, so the response carries every account's
`password` (the BCrypt hash) and `accessToken`. That token is exactly what
`OpenApiAuthFilter` authenticates `/openapi/**` with, via
`findByAccessToken`.

So any account with `USER` can read the administrator's access token and then act
as the administrator across the whole machine surface. The password hashes leak
alongside it, for offline cracking. `GET /api/users/page` and `GET /api/users/me`
serialise the same aggregate.

The fix is a response DTO that omits both fields. Pinned by a test in
`test_user.py` that fails once the fields stop being returned, so the fix is
noticed here rather than being mistaken for a regression.

### Everything else

- **Builds wedge against a credential-free registry.** `syncImagePullSecret`
  skips creating the `dockerhub` secret when the registry username or password is
  blank, but the build job mounted it as a non-optional volume, so every build
  stopped at `Init:0/2` with `MountVolume.SetUp failed`. Nothing in the UI
  explained why. Fixed by marking the volume optional in `SecretVolume`, matching
  the `git-secret` volume fifteen lines below it and the pull secret processor,
  which already tolerate the secret's absence. Covered by
  `test_build_starts_when_the_registry_needs_no_credentials`.

- **Clearing an application's host left it answering on that host.**
  `IngressRouteProcessor` returned as soon as it found no hosts configured, and
  the pruning that removes routes, the redirect middleware and the basic auth
  secrets sat below that return — so releasing a hostname in the UI took it out
  of the config and left Traefik serving it, credentials and all, until someone
  deleted the application. Fixed by making the empty-host case log and fall
  through instead of returning, and by moving the CRD check above it. Covered by
  `test_removing_the_host_removes_its_routes`.

- **Saving a runtime spec for an undeployed application logged a 404 every
  time.** `applyRuntimeSpec` patched the StatefulSet unconditionally, so
  configuring an application before its first deploy — the normal order — warned
  `Failed to apply runtime spec` with a Kubernetes 404 behind it. Twenty-three
  such warnings per suite run, all of them meaningless, which is how a log stops
  being read. Fixed by returning early when there is no StatefulSet yet; the
  deploy applies the spec when it creates one.

- **Deleting an application does clean up its Service, but not visibly.**
  `deleteWorkload` removes only the StatefulSet, which reads like a leak until
  you find the owner reference `ServiceProcessor` stamps on the Service —
  Kubernetes collects it when its owner goes. Not a defect, but an invisible
  mechanism a rewrite can drop while still passing every deploy test, so
  `test_the_service_goes_with_the_statefulset` asserts it.

- **Application names are globally unique, not per namespace.** Every URL is
  namespaced, but the schema has `UNIQUE KEY uk_application_name (name)` on the
  name alone, so two namespaces cannot both hold an application called `web`.
  Pinned by `test_application_names_are_globally_unique_not_per_namespace`, again
  written to fail if the key is widened.

- **Git credentials are shared across a work namespace.** They are synced into
  the work namespace as the `git-credential` secret, and every build pod there
  mounts it regardless of which environment triggered the build. An environment
  with no git credentials of its own therefore clones private repositories using
  whatever another environment last synced. Worth knowing before two teams share
  a work namespace, because the environment list implies an isolation that is not
  there. Pinned by `test_git_credentials_are_shared_across_a_work_namespace`.

- **Deleting an application does nothing in the cluster unless it is bound to an
  environment.** `deleteApplication` iterates the `application_environment` rows
  to decide what to clean up, but a deploy does not require that binding — so an
  unbound application can be deployed, then deleted from the database while its
  StatefulSet keeps running. The suite avoids this by binding explicitly in
  `configure_for_build`, which is also what the UI does.

The last two are recorded rather than fixed. Both are behaviour changes a user
would notice, so they belong in a decision, not in a test-driven drive-by.

## Writing more

Tests speak only through `OopsClient`. When you add an endpoint, add a helper
there rather than building URLs inline, so a path change lands in one place.

Two rules keep the suite honest as a rewrite target:

- **Assert on the contract, not the implementation.** Pod log content depends on
  the image; that lines arrive individually does not. Prefer the second.
- **Say what a failure means.** Every assertion carries a message explaining the
  consequence, because the person reading it may be debugging a reimplementation
  they did not write.
