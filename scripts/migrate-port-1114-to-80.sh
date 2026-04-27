#!/bin/bash
# Migrate OOPS applications from port 1114 to 80 (interactive, per-app)
# Handles both:
#   - New format: IngressRoute name = {app}-http-* / {app}-https-*, with label oops.type=APPLICATION
#   - Old format: IngressRoute name = {app}, no label
# StatefulSet containerPort is NOT changed

set -e

NAMESPACE="${NAMESPACE:-}"

if [ -z "$NAMESPACE" ]; then
    echo "Usage: NAMESPACE=<namespace> ./1114-80.sh"
    echo "  NAMESPACE: K8s namespace to migrate (required)"
    exit 1
fi

echo "=== Migrating namespace: $NAMESPACE ==="
echo ""

# ============================================================
# Find apps that need migration (Service or IngressRoute on port 1114)
# ============================================================
declare -A APP_IRS  # app_name -> space-separated list of IngressRoutes with port 1114
declare -A APP_SVC_PORT  # app_name -> Service port

# Get ALL IngressRoutes (no label filter, to catch old ones too)
IRS=$(kubectl get ingressroutes.traefik.io -n "$NAMESPACE" -o jsonpath='{.items[*].metadata.name}' 2>/dev/null)

for ir in $IRS; do
    ir_port=$(kubectl get ingressroutes.traefik.io "$ir" -n "$NAMESPACE" -o jsonpath='{.spec.routes[0].services[0].port}' 2>/dev/null)
    [ "$ir_port" != "1114" ] && continue

    # Get the Service name referenced by this IngressRoute
    svc_name=$(kubectl get ingressroutes.traefik.io "$ir" -n "$NAMESPACE" -o jsonpath='{.spec.routes[0].services[0].name}' 2>/dev/null)
    [ -z "$svc_name" ] && continue

    # Use the referenced Service name as the app name
    APP_IRS["$svc_name"]="${APP_IRS[$svc_name]} $ir"
done

# Get Service ports for each app
for app_name in "${!APP_IRS[@]}"; do
    svc_port=$(kubectl get service "$app_name" -n "$NAMESPACE" -o jsonpath='{.spec.ports[0].port}' 2>/dev/null)
    APP_SVC_PORT["$app_name"]="${svc_port:-not_found}"
done

# Build app list
APP_LIST=("${!APP_IRS[@]}")
if [ ${#APP_LIST[@]} -eq 0 ]; then
    echo "No applications found with IngressRoute port 1114."
    echo ""
    echo "=== IDE: Informational ==="
    IDE_SS_COUNT=$(kubectl get statefulset -n "$NAMESPACE" -l oops.type=IDE -o jsonpath='{.items[*].metadata.name}' 2>/dev/null | wc -w | tr -d ' ')
    echo "IDE StatefulSets: $IDE_SS_COUNT (skipping, delete manually)"
    echo "Nothing to migrate. Exiting."
    exit 0
fi

echo "=== Applications needing migration ==="
for app in "${APP_LIST[@]}"; do
    irs="${APP_IRS[$app]}"
    svc_port="${APP_SVC_PORT[$app]}"
    echo "  - $app (Service port: ${svc_port}, IngressRoutes:${irs})"
done
echo ""
echo "Found ${#APP_LIST[@]} application(s)."
echo ""

# ============================================================
# IDE info
# ============================================================
echo "=== IDE: Informational ==="
IDE_SS_COUNT=$(kubectl get statefulset -n "$NAMESPACE" -l oops.type=IDE -o jsonpath='{.items[*].metadata.name}' 2>/dev/null | wc -w | tr -d ' ')
echo "IDE StatefulSets: $IDE_SS_COUNT (skipping, delete manually)"
echo ""

# ============================================================
# Interactive migration (per app)
# ============================================================
echo "=== Starting interactive migration (per application) ==="
echo ""

for app in "${APP_LIST[@]}"; do
    svc_port="${APP_SVC_PORT[$app]}"
    irs="${APP_IRS[$app]}"

    echo "----------------------------------------"
    echo "Application: $app"
    echo "  Service port:      ${svc_port}"
    echo "  IngressRoutes:     ${irs}"
    echo -n "Patch Service + all IngressRoutes to 80? [y/n/q]: "
    read -r answer
    case $answer in
        q|Q) echo "Aborted."; exit 1 ;;
        y|Y)
            # Patch Service
            if [ "$svc_port" = "1114" ]; then
                echo "  Patching Service $app port 1114 -> 80 ..."
                kubectl patch service "$app" -n "$NAMESPACE" \
                    --type='json' \
                    -p='[{"op":"replace","path":"/spec/ports/0/port","value":80}]'
                echo "  Service patched."
            else
                echo "  Service port is $svc_port, skipping."
            fi

            # Patch each IngressRoute for this app
            for ir in $irs; do
                echo "  Patching IngressRoute $ir port 1114 -> 80 ..."
                kubectl patch ingressroutes.traefik.io "$ir" -n "$NAMESPACE" \
                    --type='json' \
                    -p='[{"op":"replace","path":"/spec/routes/0/services/0/port","value":80}]'
                echo "  IngressRoute $ir patched."
            done
            echo "  Done: $app"
            ;;
        *) echo "  Skipped: $app" ;;
    esac
done

echo ""
echo "=== All done ==="