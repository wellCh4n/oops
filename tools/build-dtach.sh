#!/usr/bin/env bash
#
# Builds the static dtach binaries that OOPS injects into containers to make pod
# terminals survive a dropped WebSocket (see the "Resumable Terminals" section of
# CLAUDE.md).
#
# The binaries are committed under src/main/resources/bin/ so that a plain `mvn
# package` needs no C toolchain. Re-run this only when bumping DTACH_REF, then
# commit the refreshed binaries together with dtach.sha256.
#
# Nothing prebuilt is downloaded: each binary is compiled from the pinned upstream
# tag inside an Alpine container of the matching platform, so musl static linking
# happens natively rather than through a cross toolchain.
#
# Requires Docker with binfmt/QEMU emulation for the non-native platform.
#
set -euo pipefail

DTACH_REPO="https://github.com/crigler/dtach"
DTACH_REF="v0.9"
BUILDER_IMAGE="alpine:3.20"

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
output_dir="${repo_root}/src/main/resources/bin"
mkdir -p "${output_dir}"

for arch in amd64 arm64; do
  echo "==> building dtach ${DTACH_REF} for linux/${arch}"
  docker run --rm --platform "linux/${arch}" \
    -v "${output_dir}:/out" \
    -e DTACH_REPO="${DTACH_REPO}" \
    -e DTACH_REF="${DTACH_REF}" \
    -e ARCH="${arch}" \
    "${BUILDER_IMAGE}" sh -euc '
      apk add --no-cache build-base git >/dev/null
      git clone --depth 1 --branch "${DTACH_REF}" "${DTACH_REPO}" /src >/dev/null 2>&1
      cd /src
      # configure is checked in upstream, so no autotools regeneration is needed.
      # LDFLAGS carries -static as well: CFLAGS alone only affects compilation and
      # would silently leave a dynamically linked binary behind.
      ./configure CFLAGS="-O2 -static" LDFLAGS="-static" >/dev/null
      make >/dev/null
      strip dtach
      # A dynamically linked binary would be useless inside an arbitrary image, so
      # fail loudly rather than shipping one: a static ELF carries no INTERP segment.
      if readelf -l dtach | grep -q INTERP; then
        echo "dtach is not statically linked" >&2
        exit 1
      fi
      cp dtach "/out/dtach-linux-${ARCH}"
    '
done

cd "${output_dir}"
sha256sum dtach-linux-amd64 dtach-linux-arm64 > dtach.sha256
echo "==> done"
cat dtach.sha256
ls -l dtach-linux-amd64 dtach-linux-arm64
