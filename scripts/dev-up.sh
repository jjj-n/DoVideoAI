#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

for command in docker curl java node ffmpeg tesseract; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "Missing required command: $command" >&2
    exit 1
  }
done

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env. Set SILICONFLOW_API_KEY and replace the example passwords, then run this script again."
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

for variable in \
  DB_PASSWORD MYSQL_ROOT_PASSWORD REDIS_PASSWORD MINIO_SECRET_KEY QDRANT_API_KEY SILICONFLOW_API_KEY; do
  value="${!variable:-}"
  if [[ -z "$value" || "$value" == change-* ]]; then
    echo "Set a non-example value for $variable in .env" >&2
    exit 1
  fi
done

if [[ ! -d mysql/data/mysql && "${DB_USERNAME:-}" != "${MYSQL_APP_USER:-dovideo}" ]]; then
  echo "DB_USERNAME and MYSQL_APP_USER must match for a fresh database." >&2
  exit 1
fi

java_version="$(java -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')"
java_major="${java_version%%.*}"
[[ "$java_major" == "1" ]] && java_major="$(cut -d. -f2 <<<"$java_version")"
(( java_major >= 21 )) || { echo "JDK 21+ is required; found $java_version" >&2; exit 1; }

minimum_node_version="22.22.1"
node_version="$(node --version)"
if [[ ! "$node_version" =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+)([-+].*)?$ ]]; then
  echo "Unable to parse Node.js version: $node_version" >&2
  exit 1
fi
node_major="${BASH_REMATCH[1]}"
node_minor="${BASH_REMATCH[2]}"
node_patch="${BASH_REMATCH[3]}"
node_suffix="${BASH_REMATCH[4]:-}"
if [[ "$node_suffix" == -* ]] \
  || (( node_major < 22 )) \
  || (( node_major == 22 && node_minor < 22 )) \
  || (( node_major == 22 && node_minor == 22 && node_patch < 1 )); then
  echo "Node.js $minimum_node_version or newer is required; found $node_version" >&2
  exit 1
fi

docker info >/dev/null
docker compose --env-file .env config --quiet
docker compose --env-file .env up --wait --wait-timeout 120

curl --fail --silent --show-error --retry 20 --retry-connrefused --retry-delay 1 \
  --header "api-key: ${QDRANT_API_KEY}" \
  http://127.0.0.1:6333/healthz >/dev/null
curl --fail --silent --show-error --retry 20 --retry-connrefused --retry-delay 1 \
  http://127.0.0.1:9000/minio/health/live >/dev/null

docker compose --env-file .env ps
echo
echo "Infrastructure is ready. Start the backend with:"
echo "  set -a; source .env; set +a; cd server && ./mvnw spring-boot:run"
