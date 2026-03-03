#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER_NAME="halo-redirects-dev"

wait_for_halo() {
  local attempts=0

  until curl -sf http://localhost:8090/actuator/health/readiness >/dev/null; do
    attempts=$((attempts + 1))
    if [[ ${attempts} -ge 60 ]]; then
      echo "Halo did not become ready in time"
      docker compose -f "${ROOT_DIR}/docker-compose.yaml" ps || true
      docker logs --tail 200 "${CONTAINER_NAME}" || true
      exit 1
    fi
    sleep 2
  done
}

"${ROOT_DIR}/scripts/build-in-docker.sh"

mkdir -p "${ROOT_DIR}/.halo2/plugins"

PLUGIN_JAR="$(find "${ROOT_DIR}/build/libs" -maxdepth 1 -type f -name '*.jar' | head -n 1)"

if [[ -z "${PLUGIN_JAR}" ]]; then
  echo "No plugin jar found under build/libs"
  exit 1
fi

find "${ROOT_DIR}/.halo2/plugins" -maxdepth 1 -type f -name 'redirects-*.jar' -delete
cp "${PLUGIN_JAR}" "${ROOT_DIR}/.halo2/plugins/"

if [[ ! -f "${ROOT_DIR}/.halo2/db/halo-next.mv.db" ]]; then
  docker compose -f "${ROOT_DIR}/docker-compose.yaml" up -d
  wait_for_halo
  docker compose -f "${ROOT_DIR}/docker-compose.yaml" stop
fi

docker compose -f "${ROOT_DIR}/docker-compose.yaml" down >/dev/null 2>&1 || true

"${ROOT_DIR}/scripts/register-test-plugin.sh"

docker compose -f "${ROOT_DIR}/docker-compose.yaml" up -d
wait_for_halo

docker compose -f "${ROOT_DIR}/docker-compose.yaml" ps
docker logs --since 2m "${CONTAINER_NAME}" | grep -E 'Loading plugin redirects|Started run\\.halo\\.redirects\\.RedirectsPlugin|\\[redirects\\]' || true

echo "Halo test environment is ready at http://localhost:8090"
