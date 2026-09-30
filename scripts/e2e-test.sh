#!/usr/bin/env bash
# E2E check of the redirects plugin against a throwaway Halo container.
# Usage: e2e.sh <halo-image> <plugin-jar> [port]
set -uo pipefail

IMAGE=$1
JAR=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
PORT=${3:-18090}
NAME=halo-redirects-e2e-$PORT
BASE=http://localhost:$PORT
AUTH=admin:Admin12345678
CAPI=$BASE/apis/api.console.halo.run/v1alpha1

pass=0; fail=0
curl() { command curl -m 20 "$@"; }
check() { # name expected actual
  if [[ "$2" == "$3" ]]; then echo "  PASS $1 ($3)"; pass=$((pass+1)); else echo "  FAIL $1 expected=$2 actual=$3"; fail=$((fail+1)); fi
}
status() { curl -s -o /dev/null -w "%{http_code} %{redirect_url}" "$BASE$1"; }
wait_ready() {
  for _ in $(seq 1 90); do curl -sf "$BASE/actuator/health/readiness" >/dev/null && return 0; sleep 2; done
  echo "Halo not ready"; docker logs --tail 50 "$NAME"; return 1
}
put_config() { # json body for basic group
  local code
  code=$(curl -s -u "$AUTH" -X PUT -H 'Content-Type: application/json' \
    "$CAPI/plugins/redirects/json-config" -d "{\"basic\": $1}" -o /dev/null -w "%{http_code}")
  if [[ "$code" == 404 ]]; then # Halo < 2.20: edit the ConfigMap directly
    curl -s -u "$AUTH" "$BASE/api/v1alpha1/configmaps/redirects-config" \
      | python3 -c 'import json,sys; c=json.load(sys.stdin); c.setdefault("data",{})["basic"]=sys.argv[1]; print(json.dumps(c))' "$1" \
      | curl -s -u "$AUTH" -X PUT -H 'Content-Type: application/json' -d @- \
        "$BASE/api/v1alpha1/configmaps/redirects-config" -o /dev/null -w "%{http_code}" \
      | sed 's/^200$/204/'
  else
    echo "$code"
  fi
}

docker rm -f "$NAME" >/dev/null 2>&1
docker run -d --name "$NAME" -p "$PORT:8090" \
  -e HALO_SECURITY_BASICAUTH_DISABLED=false \
  -e HALO_EXTERNALURL="$BASE" \
  "$IMAGE" >/dev/null
echo "== $IMAGE + $(basename "$JAR")"
wait_ready || exit 1

jar=$(mktemp)
csrf=$(curl -s -c "$jar" "$BASE/system/setup" \
  | grep -oE 'name="_csrf"[^>]*value="[^"]+"|value="[^"]+"[^>]*name="_csrf"' | grep -oE 'value="[^"]+"' | cut -d'"' -f2)
curl -s -b "$jar" -o /dev/null -X POST "$BASE/system/setup" --data-urlencode "_csrf=$csrf" \
  --data-urlencode siteTitle=e2e --data-urlencode username=admin --data-urlencode password=Admin12345678 \
  --data-urlencode email=admin@example.com --data-urlencode language=zh-CN --data-urlencode "externalUrl=$BASE"
rm -f "$jar"
# Halo < 2.20 has no /system/setup form; fall back to the JSON initializer
curl -s -o /dev/null -X POST -H 'Content-Type: application/json' "$CAPI/system/initialize" \
  -d '{"username":"admin","password":"Admin12345678","email":"admin@example.com","siteTitle":"e2e"}'

code=$(curl -s -u "$AUTH" -o /dev/null -w "%{http_code}" "$CAPI/plugins")
check "console api auth" 200 "$code"

code=$(curl -s -u "$AUTH" -o /dev/null -w "%{http_code}" -F "file=@$JAR" "$CAPI/plugins/install")
check "plugin install" 200 "$code"
curl -s -u "$AUTH" -o /dev/null -X PUT -H 'Content-Type: application/json' \
  "$CAPI/plugins/redirects/plugin-state" -d '{"enable":true}'

phase=""
for _ in $(seq 1 30); do
  phase=$(curl -s -u "$AUTH" "$BASE/apis/plugin.halo.run/v1alpha1/plugins/redirects" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",{}).get("phase",""))' 2>/dev/null)
  [[ "$phase" == STARTED || "$phase" == FAILED ]] && break; sleep 2
done
check "plugin phase" STARTED "$phase"

# 1) config update event -> rules must apply without restart
code=$(put_config '{"enabled":true,"preserveQueryString":true,"bulkRules":"/bulk-old -> /bulk-new -> 302","rules":[{"fromPath":"/old-post","toPath":"/new-post","statusCode":301,"matchType":"EXACT"},{"fromPath":"/docs","toPath":"/knowledge","statusCode":301,"matchType":"DIRECTORY"}]}')
check "put config" 204 "$code"
sleep 3
check "exact 301" "301 $BASE/new-post" "$(status /old-post)"
check "query preserved" "301 $BASE/new-post?utm=1" "$(status '/old-post?utm=1')"
check "directory 301" "301 $BASE/knowledge/a/b" "$(status /docs/a/b)"
check "bulk 302" "302 $BASE/bulk-new" "$(status /bulk-old)"
check "console api not redirected" 200 "$(curl -s -u "$AUTH" -o /dev/null -w "%{http_code}" "$CAPI/plugins")"

# 2) console endpoints (use SettingFetcher)
check "GET plugin settings endpoint" 200 \
  "$(curl -s -u "$AUTH" -o /dev/null -w "%{http_code}" "$BASE/apis/console.api.redirects.halo.run/v1alpha1/plugins/redirects/settings")"
check "GET export csv" 200 \
  "$(curl -s -u "$AUTH" -o /dev/null -w "%{http_code}" "$BASE/apis/console.api.redirects.halo.run/v1alpha1/plugins/redirects/rules/export?format=csv")"

# 3) disable via config -> no redirect
put_config '{"enabled":false,"preserveQueryString":true,"rules":[{"fromPath":"/old-post","toPath":"/new-post","statusCode":301,"matchType":"EXACT"}]}' >/dev/null
sleep 3
check "disabled => no redirect" 404 "$(status /old-post | cut -d' ' -f1)"

# 4) restart -> rules loaded on startup
put_config '{"enabled":true,"preserveQueryString":false,"rules":[{"fromPath":"/after-restart","toPath":"/ok","statusCode":301,"matchType":"EXACT"}]}' >/dev/null
docker restart "$NAME" >/dev/null; wait_ready
for _ in $(seq 1 20); do [[ "$(status /after-restart)" == 301* ]] && break; sleep 2; done
check "rules loaded after restart" "301 $BASE/ok" "$(status /after-restart)"

echo "  -- plugin log lines:"
docker logs "$NAME" 2>&1 | grep -E "\[redirects\]|Unable to start plugin 'redirects|IncompatibleClassChange|UnrecognizedProperty" | cut -c1-220 | sed 's/^/     /' | tail -12
echo "RESULT: $pass passed, $fail failed"
[[ "${KEEP:-}" == 1 ]] || docker rm -f "$NAME" >/dev/null
[[ $fail -eq 0 ]]
