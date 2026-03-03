#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_NAME="redirects"
PLUGIN_VERSION="0.1.3"
PLUGIN_JAR="redirects-${PLUGIN_VERSION}.jar"
HALO_IMAGE="halohub/halo:2.22.14"
DB_FILE="${ROOT_DIR}/.halo2/db/halo-next.mv.db"
CREATION_TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
HALO_DB_USER="${HALO_DB_USER:-admin}"
HALO_DB_PASSWORD="${HALO_DB_PASSWORD:-123456}"

if [[ ! -f "${DB_FILE}" ]]; then
  echo "Halo database not found at ${DB_FILE}"
  echo "Start Halo once before registering the plugin resource."
  exit 1
fi

JSON_FILE="$(mktemp)"
trap 'rm -f "${JSON_FILE}"' EXIT

cat > "${JSON_FILE}" <<JSON
{
  "spec": {
    "displayName": "Redirects",
    "version": "${PLUGIN_VERSION}",
    "author": {
      "name": "Linus",
      "website": "https://github.com/Leezj9671"
    },
    "logo": "logo.svg",
    "pluginDependencies": {},
    "homepage": "https://github.com/Leezj9671/halo-redirects",
    "repo": "https://github.com/Leezj9671/halo-redirects",
    "issues": "https://github.com/Leezj9671/halo-redirects/issues",
    "description": "管理 Halo 博客的重定向规则，支持 301 和 302。",
    "license": [
      {
        "name": "GPL-3.0",
        "url": "https://www.gnu.org/licenses/gpl-3.0.html"
      }
    ],
    "requires": ">=2.19.3",
    "enabled": true,
    "settingName": "redirects-settings",
    "configMapName": "redirects-config"
  },
  "apiVersion": "plugin.halo.run/v1alpha1",
  "kind": "Plugin",
  "metadata": {
    "finalizers": [
      "plugin-protection"
    ],
    "name": "redirects",
    "annotations": {
      "plugin.halo.run/plugin-path": "${PLUGIN_JAR}"
    },
    "version": 1,
    "creationTimestamp": "${CREATION_TIMESTAMP}"
  }
}
JSON

docker run --rm \
  --entrypoint sh \
  -v "${ROOT_DIR}/.halo2:/data" \
  -v "${JSON_FILE}:/work/plugin.json:ro" \
  "${HALO_IMAGE}" \
  -lc "java -cp /application/BOOT-INF/lib/h2-2.3.232.jar org.h2.tools.Shell \
    -url jdbc:h2:file:/data/db/halo-next \
    -user ${HALO_DB_USER} \
    -password ${HALO_DB_PASSWORD} \
    -sql \"MERGE INTO PUBLIC.EXTENSIONS KEY(NAME) VALUES ('/registry/plugin.halo.run/plugins/${PLUGIN_NAME}', STRINGTOUTF8(FILE_READ('/work/plugin.json')), 0);\""

echo "Registered plugin resource /registry/plugin.halo.run/plugins/${PLUGIN_NAME}"
