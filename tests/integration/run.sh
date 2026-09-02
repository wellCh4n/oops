#!/usr/bin/env bash
# The only command you need to run the integration suite.
#
# Brings up MySQL, RustFS, Gitea and two registries, builds and starts the
# backend, starts a k3s cluster, registers it as an environment, runs the
# scenarios, writes a report, and tears all of it down again — including when
# you interrupt it. Nothing is installed on the host, and no existing
# kubeconfig, database or registry is touched.
#
# The run prints as numbered steps, like `docker build --progress=plain`: each
# step says what it is doing while it does it, then how long it took.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS="${HERE}/scripts"
REPO="$(cd "${HERE}/../.." && pwd)"
REPORTS="${HERE}/reports"
STAMP="$(date +%Y%m%d-%H%M%S)"
# Beside the report rather than in a temp directory, and never deleted: the
# backend log is the first thing you want after a failure, and often after a
# pass too.
LOGS="${REPORTS}/${STAMP}"

BOLD=$'\033[1m'; DIM=$'\033[2m'; GREEN=$'\033[32m'; RED=$'\033[31m'
YELLOW=$'\033[33m'; RESET=$'\033[0m'

# -- modules ------------------------------------------------------------------
#
# A module is one test file, named after what it covers. Nothing declares the
# list: `test_deploy.py` *is* the `deploy` module, and its first docstring line
# is its description, so adding a file adds a module with no registry to keep in
# step. The `zz_` on the coverage file only exists to sort it last.

module_names() {
  local file name
  for file in "${HERE}"/test_*.py; do
    name="$(basename "$file" .py)"; name="${name#test_}"
    printf '%s\n' "${name#zz_}"
  done
}

module_file() {
  local candidate
  for candidate in "${HERE}/test_${1}.py" "${HERE}/test_zz_${1}.py"; do
    if [ -f "$candidate" ]; then printf '%s' "$candidate"; return 0; fi
  done
  return 1
}

list_modules() {
  printf "%smodules%s — all of them run unless you name some with --module\n\n" \
    "$BOLD" "$RESET"
  local file name summary
  for file in "${HERE}"/test_*.py; do
    name="$(basename "$file" .py)"; name="${name#test_}"; name="${name#zz_}"
    summary="$(sed -n '1s/^"""//p' "$file")"
    printf "  %-20s %s%s%s\n" "$name" "$DIM" "$summary" "$RESET"
  done
}

usage() {
  cat <<'TEXT'
usage: ./run.sh [options] [-- pytest arguments]

  --module NAME[,NAME]   run only these modules (repeatable); default is all.
                         Naming only modules that need no cluster skips
                         starting one, which saves a couple of minutes.
  --list                 list the modules and what each covers
  --contract             every module, but only the tests that need no cluster
  --keep                 leave the stack running afterwards
  --                     everything after this goes straight to pytest
                         (e.g. -- -k basic_auth -x)

  ./run.sh                          the whole suite
  ./run.sh --module ingress         iterate on one module
  ./run.sh --module deploy,ingress  a couple of related ones
  ./run.sh --list                   what is there to run
TEXT
}

CONTRACT_ONLY=0
KEEP=0
MODULES=()
PYTEST_EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --module)
      [ $# -ge 2 ] || { printf "%s needs a module name\n" "$1" >&2; exit 2; }
      IFS=, read -r -a chunk <<<"$2"
      MODULES+=("${chunk[@]}")
      shift ;;
    --module=*) IFS=, read -r -a chunk <<<"${1#--module=}"; MODULES+=("${chunk[@]}") ;;
    --list) list_modules; exit 0 ;;
    --contract) CONTRACT_ONLY=1 ;;
    --keep) KEEP=1 ;;
    --) shift; PYTEST_EXTRA=("$@"); break ;;
    -h|--help) usage; exit 0 ;;
    *) printf "unknown option: %s\n\n" "$1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

# Resolve the names now, so a typo costs a second rather than the five minutes
# it takes to stand the stack up.
SELECTED=()
for name in ${MODULES[@]+"${MODULES[@]}"}; do
  if file="$(module_file "$name")"; then
    SELECTED+=("$file")
  else
    printf "%sno module named %s%s\n\n" "$RED" "$name" "$RESET" >&2
    list_modules >&2
    exit 2
  fi
done
# The coverage module judges what the rest of the run reached, so it goes last
# whenever it was asked for alongside others.
if [ ${#SELECTED[@]} -gt 1 ]; then
  ORDERED=(); COVERAGE=()
  for file in "${SELECTED[@]}"; do
    case "$file" in *test_zz_*) COVERAGE+=("$file") ;; *) ORDERED+=("$file") ;; esac
  done
  SELECTED=("${ORDERED[@]+"${ORDERED[@]}"}" "${COVERAGE[@]+"${COVERAGE[@]}"}")
fi

# Starting k3s and provisioning it costs a couple of minutes, so skip it when
# nothing selected has a cluster test in it. Anything passed straight to pytest
# is opaque here, so that case keeps the cluster.
needs_cluster() {
  [ "$CONTRACT_ONLY" -eq 1 ] && return 1
  [ ${#SELECTED[@]} -eq 0 ] && return 0
  [ ${#PYTEST_EXTRA[@]} -gt 0 ] && return 0
  grep -l 'mark\.cluster' "${SELECTED[@]}" >/dev/null 2>&1
}

mkdir -p "$REPORTS" "$LOGS"

# -- steps --------------------------------------------------------------------

STEP=0
STEP_STARTED=0

step_begin() {
  STEP=$((STEP + 1)); STEP_STARTED=$SECONDS
  printf "%s#%d%s %s%s%s\n" "$DIM" "$STEP" "$RESET" "$BOLD" "$1" "$RESET"
}
step_done() {
  printf "%s#%d%s %sDONE %ss%s\n" \
    "$DIM" "$STEP" "$RESET" "$GREEN" "$((SECONDS - STEP_STARTED))" "$RESET"
}
step_skipped() {
  printf "%s#%d%s %sSKIPPED%s %s%s%s\n" \
    "$DIM" "$STEP" "$RESET" "$YELLOW" "$RESET" "$DIM" "$1" "$RESET"
}
step_cached() { printf "%s#%d%s %sCACHED%s\n" "$DIM" "$STEP" "$RESET" "$DIM" "$RESET"; }
step_failed() {
  printf "%s#%d ERROR %ss — %s%s\n" \
    "$RED" "$STEP" "$((SECONDS - STEP_STARTED))" "$1" "$RESET" >&2
}

# Runs a command as the current step. Its output goes to the step's log in full
# and onto the screen as a few scrolling lines that erase themselves when the
# step ends — a docker build's RUN, where the heading and the DONE stay and the
# output does not. The status travels as a sentinel line because a pipe does not
# carry one; the renderer exits with it, so this returns what the command
# returned rather than what the pipe did.
step_run() {
  local logfile="$1"; shift
  { "$@" 2>&1; echo "__step_status__ $?"; } \
    | python3 "${SCRIPTS}/render_step.py" --prefix "#${STEP}" --log "$logfile"
}

BACKEND_CONTAINER=oops-integration-backend
BACKEND_LOG_PID=""
CLEANED=0

cleanup() {
  # Runs on success, on failure and on Ctrl-C, so a run never leaves a cluster,
  # a database or a stray JVM behind unless --keep was asked for.
  local status=$?
  [ "$CLEANED" -eq 1 ] && return $status
  CLEANED=1
  [ -n "$BACKEND_LOG_PID" ] && kill "$BACKEND_LOG_PID" 2>/dev/null

  if [ "$KEEP" -eq 1 ]; then
    printf "\n%s--keep: the stack is still up.%s\n" "$DIM" "$RESET"
    printf "%s  backend: docker logs %s · logs in %s%s\n" \
      "$DIM" "$BACKEND_CONTAINER" "$LOGS" "$RESET"
    printf "%s  tear down with: docker compose -f %s/docker-compose.yml --profile cluster --profile backend down -v%s\n" \
      "$DIM" "$HERE" "$RESET"
    return $status
  fi

  printf "\n%scleaning up · logs in %s%s\n" "$DIM" "$LOGS" "$RESET"
  docker rm -f "$BACKEND_CONTAINER" >/dev/null 2>&1
  # Every profile, or the cluster and the backend outlive a teardown that looks
  # like it removed everything.
  docker compose -f "${HERE}/docker-compose.yml" \
    --profile cluster --profile backend down -v --remove-orphans \
    >>"${LOGS}/teardown.log" 2>&1
  # Best effort: a leftover file the cluster wrote as root is worth a mention,
  # not a failed run on top of whatever already failed.
  rm -rf "${HERE}/scripts/.registry-auth" "${HERE}/scripts/.k3s" 2>/dev/null || \
    printf "%s  could not remove %s/scripts/.k3s%s\n" "$DIM" "$HERE" "$RESET"
  return $status
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Announced up front, not just at the end, so a second terminal can follow along
# while a long run is still going.
printf "%slogs: %s%s\n" "$DIM" "$LOGS" "$RESET"
if [ ${#SELECTED[@]} -gt 0 ]; then
  printf "%smodules: %s%s\n" "$DIM" "${MODULES[*]}" "$RESET"
fi
echo

# -- python -------------------------------------------------------------------

step_begin "python environment"
if [ -d "${HERE}/.venv" ]; then
  step_cached
else
  step_run "${LOGS}/setup.log" python3 -m venv "${HERE}/.venv" || {
    step_failed "could not create the virtualenv"; exit 1; }
  step_run "${LOGS}/setup.log" \
    "${HERE}/.venv/bin/pip" install -r "${HERE}/requirements.txt" || {
      step_failed "could not install requirements"; exit 1; }
  step_done
fi
# shellcheck disable=SC1091
. "${HERE}/.venv/bin/activate"

# -- middleware ---------------------------------------------------------------

# Presigned URLs are signed for a container name that every server-side party
# resolves. Only the test process is outside Docker, so it rewrites the address
# and keeps the Host header.
export OOPS_OBJECT_STORAGE_HOST_OVERRIDE="127.0.0.1:19000"

COMPOSE=(docker compose -f "${HERE}/docker-compose.yml")

step_begin "middleware — mysql, rustfs, gitea, two registries"
mkdir -p "${HERE}/scripts/.registry-auth"
step_run "${LOGS}/middleware.log" \
  "${COMPOSE[@]}" up -d --wait mysql rustfs gitea registry registry-auth || {
    step_failed "middleware failed to start; see ${LOGS}/middleware.log"; exit 1; }
step_run "${LOGS}/middleware.log" "${COMPOSE[@]}" up rustfs-init
step_done

step_begin "git fixtures"
# Seeding needs `docker exec`, because Gitea has no API for creating its first
# admin — the one part of the stack that cannot be a compose service.
export OOPS_GITEA_USER="${OOPS_GITEA_USER:-oops}"
export OOPS_GITEA_PASSWORD="${OOPS_GITEA_PASSWORD:-oops-git-secret}"
export OOPS_GITEA_URL="http://gitea:3000"
export OOPS_GITEA_PROBE_URL="http://127.0.0.1:13000"
step_run "${LOGS}/middleware.log" \
  python "${SCRIPTS}/seed_gitea.py" --base "$OOPS_GITEA_PROBE_URL" || {
    step_failed "could not seed the git fixtures; see ${LOGS}/middleware.log"
    exit 1; }
step_done

# -- backend ------------------------------------------------------------------

# The backend image is built from the Go sources every run: a stale binary is
# the one thing this suite must never test. BuildKit's layer cache makes an
# unchanged tree a few seconds.
build_backend_image() {
  "${COMPOSE[@]}" --profile backend build backend
}

step_begin "backend image"
step_run "${LOGS}/build.log" build_backend_image || {
  step_failed "the backend image did not build; see ${LOGS}/build.log"; exit 1; }
step_done

# An IANA name, not the %Z abbreviation: "CST" is ambiguous and the JVM ignores
# it, leaving the container on UTC. Timestamps are a naive local wall clock in
# this product, so a container in the wrong zone shifts every stored value.
HOST_TIMEZONE="$(readlink /etc/localtime 2>/dev/null | sed 's|.*/zoneinfo/||')"
HOST_TIMEZONE="${HOST_TIMEZONE:-${TZ:-UTC}}"

await_backend() {
  # Polled from here rather than by a container healthcheck, so the same code
  # path also covers a backend running somewhere else entirely.
  local attempt
  for attempt in $(seq 1 90); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' \
          -X POST http://localhost:8080/api/auth/login \
          -H 'Content-Type: application/json' \
          -d '{"username":"admin","password":"admin123"}' 2>/dev/null)" = "200" ]; then
      echo "answering on :8080, and the default admin can log in"
      return 0
    fi
    docker ps -q -f "name=^${BACKEND_CONTAINER}$" | grep -q . || {
      echo "the container is gone — see the log below"; return 1; }
    # Migrations take a moment on a fresh database. Saying so beats a
    # silent minute.
    [ $((attempt % 10)) -eq 0 ] && echo "still starting ($((attempt * 2))s)"
    sleep 2
  done
  echo "no answer on :8080 after 180s"
  return 1
}

step_begin "backend"
# Defined in docker-compose.yml behind a profile, so the image, mounts and
# dependency on a healthy MySQL all live in one declarative place.
step_run "${LOGS}/backend-start.log" \
  env OOPS_TZ="$HOST_TIMEZONE" \
  "${COMPOSE[@]}" --profile backend up -d backend || {
    step_failed "the backend container did not start"; exit 1; }
step_run "${LOGS}/backend-start.log" await_backend || {
  step_failed "the backend never became ready"
  docker logs "$BACKEND_CONTAINER" >"${LOGS}/backend.log" 2>&1 || true
  tail -30 "${LOGS}/backend.log" >&2
  exit 1; }

# Follow the log from here rather than collecting it at teardown. A container
# that dies mid-run takes its logs with it, and that is exactly the run whose
# logs you need — one full run ended with every test failing on a refused
# connection and nothing left to say why.
docker logs -f "$BACKEND_CONTAINER" >"${LOGS}/backend.log" 2>&1 &
BACKEND_LOG_PID=$!
step_done

# -- cluster ------------------------------------------------------------------

PYTEST_ARGS=(-p no:cacheprovider -p "no:randomly"
             -p "pytest_steps"
             --tb=short
             --html="${REPORTS}/report-${STAMP}.html" --self-contained-html
             --junitxml="${REPORTS}/junit-${STAMP}.xml")
export PYTHONPATH="${SCRIPTS}${PYTHONPATH:+:${PYTHONPATH}}"

step_begin "cluster"
if needs_cluster; then
  # Create the kubeconfig directory before Docker does. Docker creates a missing
  # bind-mount source as root, and on Linux that is a directory this user cannot
  # write — so rewriting the kubeconfig's address fails (sed -i renames a temp
  # file *in* the directory) and so does the teardown that removes it. macOS
  # never showed it, because Docker Desktop maps ownership to the host user.
  mkdir -p "${HERE}/scripts/.k3s"
  step_run "${LOGS}/cluster.log" "${COMPOSE[@]}" --profile cluster up -d --wait k3s || {
    step_failed "the cluster did not come up; see ${LOGS}/cluster.log"; exit 1; }
  step_done

  step_begin "cluster provisioning"
  provisioned="${LOGS}/provision.log"
  step_run "$provisioned" bash "${SCRIPTS}/cluster-provision.sh" || {
    step_failed "could not provision the cluster; see $provisioned"; exit 1; }
  # The script reports what it made as shell exports; adopt them.
  while IFS= read -r line; do
    eval "${line#  }"
  done < <(grep '^  export ' "$provisioned")
  step_done

  step_begin "environment registration"
  step_run "${LOGS}/register.log" \
    python "${SCRIPTS}/register_environment.py" --name integration || {
      step_failed "could not register the environment; see ${LOGS}/register.log"
      exit 1; }
  step_done

  PYTEST_ARGS+=(--environment integration)
else
  if [ "$CONTRACT_ONLY" -eq 1 ]; then
    step_skipped "--contract: only the tests that need no cluster"
    PYTEST_ARGS+=(-m "not cluster")
  else
    step_skipped "nothing selected needs one"
  fi
fi

# -- run ----------------------------------------------------------------------

# The modules number themselves on from here, so they read as steps of the same
# run rather than as a separate thing that happened afterwards.
export OOPS_STEP_OFFSET="$STEP"
# pytest's own stdout and stderr belong in the log, where -v gives one line per
# test and the FAILURES section survives intact. The live view needs a channel
# that was not redirected with them, so hand it this one.
exec 3>&1
export OOPS_LIVE_FD=3

echo
(cd "$HERE" && python -m pytest "${PYTEST_ARGS[@]}" \
   ${SELECTED[@]+"${SELECTED[@]}"} ${PYTEST_EXTRA[@]+"${PYTEST_EXTRA[@]}"}) \
  >"${LOGS}/pytest.log" 2>&1
STATUS=$?
exec 3>&-

ln -sf "report-${STAMP}.html" "${REPORTS}/latest.html"
ln -sf "junit-${STAMP}.xml" "${REPORTS}/latest.xml"

echo
python "${SCRIPTS}/summarise.py" "${REPORTS}/junit-${STAMP}.xml"
SUMMARY=$?

if [ "$SUMMARY" -ne 0 ] || [ "$STATUS" -ne 0 ]; then
  echo
  # Only the failures, not the whole log.
  sed -n '/=\{5,\} FAILURES/,/=\{5,\} \(warnings\|short test summary\)/p' \
    "${LOGS}/pytest.log" | head -60
  printf "%sfull output: %s%s\n" "$DIM" "${REPORTS}/report-${STAMP}.html" "$RESET"
  printf "%slogs:        %s%s\n" "$DIM" "$LOGS" "$RESET"
  exit 1
fi

printf "%sreport: %s%s\n" "$DIM" "${REPORTS}/report-${STAMP}.html" "$RESET"
printf "%slogs:   %s%s\n" "$DIM" "$LOGS" "$RESET"
exit 0
