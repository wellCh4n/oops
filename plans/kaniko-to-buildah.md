# Migrate Pipeline Build from Kaniko to Buildah

## Status: ⚠️ Needs E2E Testing

> **Merged?** Not yet — on branch `feat/buildah-migration`, ready for testing.

## Summary

Replace Kaniko (`gcr.io/kaniko-project/executor`) with Buildah (`quay.io/buildah/stable:v1.43.1`) as the image build/push init container in pipeline Kubernetes Jobs.

## Changes Made (2026-04-28)

### Configuration
- [x] `PipelineImageConfig.java` — removed `kanikoRegistryMap`, added `registryMirrors` field
- [x] `DeploymentConfig.java` — removed dead `Push` inner class (unused Kaniko default)
- [x] `application.properties.example` — updated `oops.pipeline.image.push` to Buildah image, added `oops.pipeline.image.registry-mirrors`
- [x] `application.properties` (local, not committed — in .gitignore)

### Container / Volume / Task
- [x] `PushContainer.java` — replaced Kaniko `args` with Buildah `command` (shell script), added registry mirror support via `registries.conf` TOML generation, added `REGISTRY_AUTH_FILE` env
- [x] `SecretVolume.java` — renamed `kaniko-secret` → `registry-secret`, mount path `/kaniko/.docker` → `/var/buildah/.docker`
- [x] `PipelineExecuteTask.java` — passes `registryMirrors` to PushContainer

### Documentation
- [x] `README.md`, `docs/README.zh.md`, `CLAUDE.md` — updated Kaniko references to Buildah

### Static Verification
- [x] `./mvnw compile` — BUILD SUCCESS (173 source files)
- [x] `./mvnw test` — 5 passed, 0 failures, 0 errors

## TODO — Must Test Before Merging

- [ ] **E2E pipeline test with GIT source**: Trigger a real deployment, verify the K8s Job creates with:
  - `image: quay.io/buildah/stable:v1.43.1`
  - `command: ["sh", "-c", "buildah bud --storage-driver=vfs ..."]`
  - `REGISTRY_AUTH_FILE` env var present
  - `registry-secret` volume mount at `/var/buildah/.docker`
  - No `KANIKO_REGISTRY_MAP` env var
- [ ] **Verify build completes**: Check pipeline status transitions to `BUILD_SUCCEEDED` / `SUCCEEDED`
- [ ] **Verify image pushed**: Confirm the built image exists in the target image registry
- [ ] **E2E pipeline test with ZIP source**: Same checks for ZIP-upload builds
- [ ] **Verify custom Dockerfile flow**: Test with `DockerFileType.USER` to ensure the user-provided Dockerfile is picked up correctly
- [ ] **Verify log streaming**: Connect to pipeline WebSocket and ensure Buildah output streams correctly

## Known Issues / Open Questions

### 1. VFS Storage Driver Performance
Buildah uses `--storage-driver=vfs` which is slower than overlay/overlay2. For large builds, this may be noticeably slower than Kaniko. If performance is a problem, we could:
- Add `fuse-overlayfs` to the container (requires `--device /dev/fuse` or privileged)
- Create a custom Buildah image with overlay-fuse baked in

### 2. Registry Mirror / Proxy — IMPLEMENTED
The `oops.pipeline.image.registry-mirrors` property replaces Kaniko's `kanikoRegistryMap`. Format is the same: comma-separated `source=mirror` pairs (e.g. `index.docker.io=docker.m.daocloud.io`). PushContainer parses this and generates a `registries.conf` TOML file inline via heredoc, then passes it to Buildah via `--registries-conf /tmp/registries.conf`.

### 3. TLS Verify Disabled
`--tls-verify=false` is hardcoded for both `bud` and `push`. This works for private registries without trusted certificates but means public TLS is also not verified. Consider making this configurable per environment.

### 4. Buildah Image Availability
`quay.io/buildah/stable:v1.43.1` may need to be mirrored in air-gapped or Chinese network environments. The image is ~300MB.

### 5. In-Flight Pipeline Jobs
Any pipeline Jobs that were created with the old Kaniko spec and are still running during deployment will continue to work (Jobs are immutable). New pipelines will use Buildah.


## Rollback Plan

Revert the commit and redeploy:
```bash
git revert <commit-hash>
```
The old Kaniko image is still available. No database migrations are needed — the pipeline data model is unchanged.
