#!/usr/bin/env bash
# Give the cluster what OOPS needs to be pointed at it: a work namespace, a
# service account with a long-lived token, and the two addresses of the API
# server.
#
# The cluster itself is a compose service, so nothing here creates or destroys
# it. This only fills in the parts an environment registration needs.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_NAMESPACE="${OOPS_WORK_NAMESPACE:-oops-work}"
SERVICE_ACCOUNT="oops"
KUBECONFIG_PATH="${HERE}/.k3s/kubeconfig.yaml"
API_PORT="${OOPS_K3S_PORT:-16443}"

if [ ! -f "$KUBECONFIG_PATH" ]; then
  echo "no kubeconfig at ${KUBECONFIG_PATH}; is the k3s service up?" >&2
  exit 1
fi

# k3s writes the kubeconfig for its own loopback. The suite reaches the API on
# the published port instead, so point the copy it uses at that.
export KUBECONFIG="$KUBECONFIG_PATH"
if grep -q "127.0.0.1:6443" "$KUBECONFIG_PATH"; then
  sed -i.bak "s|127.0.0.1:6443|127.0.0.1:${API_PORT}|" "$KUBECONFIG_PATH"
  rm -f "${KUBECONFIG_PATH}.bak"
fi

# k3s brings its pieces up in stages — the API server first, then CoreDNS and
# Traefik through bundled manifests — so each thing this script needs has to be
# waited for rather than assumed.
wait_for() {
  local description="$1" attempts="$2"; shift 2
  printf '%s' "waiting for ${description}"
  for _ in $(seq 1 "$attempts"); do
    if "$@" >/dev/null 2>&1; then echo " ok"; return 0; fi
    printf '.'
    sleep 2
  done
  echo " timed out"
  return 1
}

wait_for "the API server" 60 kubectl get --raw /readyz || {
  echo "the API server never became ready" >&2; exit 1; }

# Installed by a bundled manifest a moment after the API server answers, so it
# is absent if you look too early.
wait_for "CoreDNS" 60 kubectl get configmap coredns -n kube-system || {
  echo "CoreDNS never appeared" >&2; exit 1; }

# Teach the cluster's DNS how to find the middleware, by IPv4 address.
#
# Without this, a pod resolving `registry` goes CoreDNS -> the k3s container's
# resolver -> Docker Desktop's DNS, which answers with a synthesised IPv6
# address (fdfe:dcba:9876::/64). The compose network is IPv4-only and so is the
# pod network, so the push then dies as `pinging container registry: EOF` with
# nothing to suggest the address family is the problem.
#
# Addresses are read live rather than pinned, so the compose network can hand
# out whatever it likes.
NODE_HOSTS="$(kubectl get configmap coredns -n kube-system \
  -o jsonpath='{.data.NodeHosts}' 2>/dev/null || true)"
for service in registry registry-auth gitea rustfs; do
  address="$(docker inspect "oops-acceptance-${service}" \
    --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' 2>/dev/null || true)"
  [ -z "$address" ] && continue
  NODE_HOSTS="$(printf '%s\n' "$NODE_HOSTS" | grep -v " ${service}\$" || true)"
  NODE_HOSTS="$(printf '%s\n%s %s' "$NODE_HOSTS" "$address" "$service")"
done
kubectl patch configmap coredns -n kube-system --type merge \
  -p "$(python3 -c 'import json,sys; print(json.dumps({"data": {"NodeHosts": sys.stdin.read()}}))' <<<"$NODE_HOSTS")" \
  >/dev/null
# The hosts plugin reloads every 15s, so give it a moment before anything
# depends on the new names.
sleep 16

kubectl create namespace "$WORK_NAMESPACE" --dry-run=client -o yaml \
  | kubectl apply -f - >/dev/null

# cluster-admin, because acceptance runs create namespaces, exec into pods and
# reach Prometheus through the API server proxy. A narrower role here just turns
# into confusing 403s halfway through a suite.
kubectl create serviceaccount "$SERVICE_ACCOUNT" -n "$WORK_NAMESPACE" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl create clusterrolebinding "${SERVICE_ACCOUNT}-admin" \
  --clusterrole=cluster-admin \
  --serviceaccount="${WORK_NAMESPACE}:${SERVICE_ACCOUNT}" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# Since 1.24 a service account gets no token secret of its own, and
# `kubectl create token` mints a short-lived one. Ask for a bound secret so the
# token outlives a long run.
kubectl apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: ${SERVICE_ACCOUNT}-token
  namespace: ${WORK_NAMESPACE}
  annotations:
    kubernetes.io/service-account.name: ${SERVICE_ACCOUNT}
type: kubernetes.io/service-account-token
EOF

token_populated() {
  [ -n "$(kubectl get secret "${SERVICE_ACCOUNT}-token" -n "$WORK_NAMESPACE" \
    -o jsonpath='{.data.token}' 2>/dev/null)" ]
}
wait_for "the service account token" 30 token_populated || {
  echo "the service account token was never populated" >&2; exit 1; }
TOKEN="$(kubectl get secret "${SERVICE_ACCOUNT}-token" -n "$WORK_NAMESPACE" \
  -o jsonpath='{.data.token}')"
TOKEN="$(printf '%s' "$TOKEN" | base64 --decode)"

# Traefik arrives via a helm job, later still. The ingress scenarios need its
# CRDs; everything else can proceed without them.
if wait_for "the Traefik CRDs" 90 kubectl get crd ingressroutes.traefik.io; then
  TRAEFIK=yes
else
  TRAEFIK=no
fi

cat <<EOF

cluster ready (traefik CRDs: ${TRAEFIK}).

  export OOPS_CLUSTER_API_SERVER="https://k3s:6443"
  export OOPS_CLUSTER_TOKEN="${TOKEN}"
  export OOPS_WORK_NAMESPACE="${WORK_NAMESPACE}"
  export OOPS_REGISTRY="registry:5000"
  export OOPS_AUTH_REGISTRY="registry-auth:5000"
  export OOPS_AUTH_REGISTRY_USER="${OOPS_AUTH_REGISTRY_USER:-oops}"
  export OOPS_AUTH_REGISTRY_PASSWORD="${OOPS_AUTH_REGISTRY_PASSWORD:-oops-secret}"
  export OOPS_TRAEFIK_AVAILABLE="${TRAEFIK}"
  export KUBECONFIG="${KUBECONFIG_PATH}"
EOF
