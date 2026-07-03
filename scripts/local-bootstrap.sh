#!/usr/bin/env bash
# Generates example-local's bootstrap from a locally running bunting-admin stack.
#
# Reads the app's active signing key(s) straight from the admin's dockerized
# Postgres (the bootstrap API requires an OIDC session, so it is not scriptable),
# and points the endpoint at the admin's public MinIO bucket. 10.0.2.2 is the
# Android emulator's alias for the host's localhost.
#
# Usage: scripts/local-bootstrap.sh [app-identifier] [host] [output]
#   app-identifier  default: local-demo (create it in the admin dashboard first)
#   host            default: 10.0.2.2 (use your machine's IP for a physical device)
#   output          default: example-local/src/main/assets/bunting_config.json
set -euo pipefail
cd "$(dirname "$0")/.."

APP="${1:-local-demo}"
HOST="${2:-10.0.2.2}"
OUT="${3:-example-local/src/main/assets/bunting_config.json}"

if ! docker ps --format '{{.Names}}' | grep -q '^bunting-postgres$'; then
  echo "error: bunting-postgres is not running — start the admin stack first (make setup && make dev in bunting-admin)" >&2
  exit 1
fi

KEYS=$(docker exec bunting-postgres psql -U admin -d bunting_admin -t -A -c \
  "select coalesce(json_agg(json_build_object('kid', sk.kid, 'pem', sk.public_key)), '[]'::json)
   from signing_keys sk join apps a on a.id = sk.app_id
   where a.identifier = '$APP' and sk.is_active")

if [ -z "$KEYS" ] || [ "$KEYS" = "[]" ]; then
  echo "error: no active signing key for app '$APP' — create the app in the admin dashboard (http://localhost:3000) first" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"
cat > "$OUT" <<EOF
{
  "app_identifier": "$APP",
  "endpoint_url": "http://$HOST:9000/bunting-configs/$APP/config.json",
  "public_keys": $KEYS,
  "fetch_policy": {
    "min_interval_seconds": 0,
    "hard_ttl_days": 7
  }
}
EOF

echo "wrote $OUT (app: $APP, endpoint host: $HOST)"
