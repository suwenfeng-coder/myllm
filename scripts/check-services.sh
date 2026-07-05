#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec /bin/bash "${ROOT_DIR}/scripts/appctl.sh" status all

ok() { printf '%-16s %s\n' "$1" "$2"; }

check_http() {
  local name="$1"
  local url="$2"
  local out
  out="$(curl -s "${url}" 2>/dev/null || true)"
  if [[ -n "${out}" ]]; then
    ok "${name}" "UP  ${out}"
  else
    ok "${name}" "DOWN"
  fi
}

check_http_code() {
  local name="$1"
  local url="$2"
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' "${url}" 2>/dev/null || true)"
  if [[ "${code}" == "200" ]]; then
    ok "${name}" "UP  HTTP 200"
  else
    ok "${name}" "DOWN (HTTP ${code:-n/a})"
  fi
}

echo "Service status:"
check_http "myllm" "http://127.0.0.1:8080/api/chat/health"
check_http "docforge" "http://127.0.0.1:8000/ready"
check_http "parse-engines" "http://127.0.0.1:8000/v1/engines"
check_http_code "minio-console" "http://127.0.0.1:9001/login"
check_http_code "neo4j-browser" "http://127.0.0.1:7474"

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  if docker inspect rag_hybrid >/dev/null 2>&1 \
      && docker inspect -f '{{.State.Running}}' rag_hybrid 2>/dev/null | grep -qx 'true'; then
    ok "rag_hybrid" "UP"
  else
    ok "rag_hybrid" "DOWN"
  fi
  if docker inspect minio-server >/dev/null 2>&1 \
      && docker inspect -f '{{.State.Running}}' minio-server 2>/dev/null | grep -qx 'true'; then
    ok "minio-server" "UP"
  else
    ok "minio-server" "DOWN"
  fi
  if docker inspect myllm-neo4j >/dev/null 2>&1 \
      && docker inspect -f '{{.State.Running}}' myllm-neo4j 2>/dev/null | grep -qx 'true'; then
    ok "myllm-neo4j" "UP"
  else
    ok "myllm-neo4j" "DOWN"
  fi
else
  ok "docker" "DOWN"
fi
