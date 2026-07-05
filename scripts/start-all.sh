#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCFORGE_DIR="/Users/suwenfeng/Documents/code/python/docforge"
RUNTIME_DIR="${ROOT_DIR}/.runtime"
SERVICES_FILE="${ROOT_DIR}/scripts/services.local.sh"
MYLLM_PID_FILE="${RUNTIME_DIR}/myllm.pid"
DOCFORGE_PID_FILE="${RUNTIME_DIR}/docforge.pid"

mkdir -p "${RUNTIME_DIR}"

log() {
  printf '[start-all] %s\n' "$1"
}

load_custom_services() {
  if [[ -f "${SERVICES_FILE}" ]]; then
    # shellcheck disable=SC1090
    source "${SERVICES_FILE}"
    log "Loaded custom services from scripts/services.local.sh"
  fi
}

is_http_ready() {
  local url="$1"
  curl -fsS "${url}" >/dev/null 2>&1
}

wait_http_ready() {
  local url="$1"
  local retries="${2:-30}"
  local sleep_sec="${3:-1}"
  local i
  for i in $(seq 1 "${retries}"); do
    if is_http_ready "${url}"; then
      return 0
    fi
    sleep "${sleep_sec}"
  done
  return 1
}

is_pid_running() {
  local pid="$1"
  [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1
}

start_docker() {
  if docker info >/dev/null 2>&1; then
    log "Docker already ready"
    return
  fi
  log "Starting Docker Desktop..."
  open -a Docker >/dev/null 2>&1 || true

  local i
  for i in $(seq 1 60); do
    if docker info >/dev/null 2>&1; then
      log "Docker is ready"
      return
    fi
    sleep 2
  done
  log "Docker failed to start in time"
  exit 1
}

start_infra_containers() {
  log "Ensuring rag_hybrid database is up..."
  if docker inspect rag_hybrid >/dev/null 2>&1; then
    docker start rag_hybrid >/dev/null 2>&1 || true
  else
    (cd "${ROOT_DIR}" && docker compose -f docker-compose.paradedb.yml up -d rag-hybrid)
  fi

  if ! docker exec rag_hybrid pg_isready -U pgvector -d myllm >/dev/null 2>&1; then
    sleep 2
    docker exec rag_hybrid pg_isready -U pgvector -d myllm >/dev/null
  fi
  log "Database ready"

  log "Ensuring minio-server is up..."
  if docker inspect minio-server >/dev/null 2>&1; then
    docker start minio-server >/dev/null 2>&1 || true
  else
    log "Creating minio-server via docker compose..."
    (cd "${ROOT_DIR}" && docker compose -f docker-compose.paradedb.yml up -d minio)
  fi

  if wait_http_ready "http://127.0.0.1:9000/minio/health/live" 30 1; then
    log "MinIO API ready"
  else
    log "MinIO API is not reachable at 127.0.0.1:9000"
  fi

  log "Ensuring myllm-neo4j is up..."
  if docker inspect myllm-neo4j >/dev/null 2>&1; then
    docker start myllm-neo4j >/dev/null 2>&1 || true
    if wait_http_ready "http://127.0.0.1:7474" 60 1; then
      log "Neo4j ready"
    else
      log "Neo4j is not reachable at 127.0.0.1:7474"
    fi
  else
    log "Neo4j container not found; create myllm-neo4j before enabling graph features"
  fi
}

start_docforge() {
  if is_http_ready "http://127.0.0.1:8000/ready"; then
    log "DocForge already ready"
    return
  fi
  if [[ ! -d "${DOCFORGE_DIR}" || ! -d "${DOCFORGE_DIR}/.venv" ]]; then
    log "DocForge project or virtualenv not found: ${DOCFORGE_DIR}"
    return
  fi

  log "Starting DocForge service..."
  nohup bash -lc "cd \"${DOCFORGE_DIR}\" && source .venv/bin/activate && ./scripts/dev.sh" \
    >"${RUNTIME_DIR}/docforge.log" 2>&1 &
  echo "$!" >"${DOCFORGE_PID_FILE}"

  if wait_http_ready "http://127.0.0.1:8000/ready" 60 1; then
    log "DocForge ready"
  else
    log "DocForge did not become ready in time, check ${RUNTIME_DIR}/docforge.log"
  fi
}

start_myllm() {
  if is_http_ready "http://127.0.0.1:8080/api/chat/health"; then
    log "myllm already ready"
    return
  fi

  log "Starting myllm app..."
  nohup bash -lc "cd \"${ROOT_DIR}\" && mvn -q -DskipTests spring-boot:run" \
    >"${RUNTIME_DIR}/myllm.log" 2>&1 &
  local myllm_pid="$!"
  echo "${myllm_pid}" >"${MYLLM_PID_FILE}"

  if ! wait_http_ready "http://127.0.0.1:8080/api/chat/health" 90 1; then
    log "myllm did not become ready in time, check ${RUNTIME_DIR}/myllm.log"
    return 1
  fi
  # Smoke check to avoid false ready then immediate exit.
  sleep 2
  if ! is_pid_running "${myllm_pid}"; then
    log "myllm process exited unexpectedly, check ${RUNTIME_DIR}/myllm.log"
    return 1
  fi

  log "myllm ready"
  if wait_http_ready "http://127.0.0.1:8080/api/files/parse-capabilities" 20 1; then
    log "parse-capabilities endpoint ready"
  fi
}

print_summary() {
  log "Service summary:"
  printf '  - myllm:   %s\n' "$(curl -s http://127.0.0.1:8080/api/chat/health 2>/dev/null || echo unavailable)"
  printf '  - docforge: %s\n' "$(curl -s http://127.0.0.1:8000/ready 2>/dev/null || echo unavailable)"
  printf '  - minio:   %s\n' "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9001/login 2>/dev/null || echo unavailable)"
  printf '  - neo4j:   %s\n' "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:7474 2>/dev/null || echo unavailable)"
}

main() {
  exec /bin/bash "${ROOT_DIR}/scripts/appctl.sh" start all
}

main "$@"
