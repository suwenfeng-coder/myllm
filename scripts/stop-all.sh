#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCFORGE_DIR="/Users/suwenfeng/Documents/code/python/docforge"
SERVICES_FILE="${ROOT_DIR}/scripts/services.local.sh"
RUNTIME_DIR="${ROOT_DIR}/.runtime"
MYLLM_PID_FILE="${RUNTIME_DIR}/myllm.pid"
DOCFORGE_PID_FILE="${RUNTIME_DIR}/docforge.pid"

log() {
  printf '[stop-all] %s\n' "$1"
}

is_pid_running() {
  local pid="$1"
  [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1
}

is_http_ready() {
  local url="$1"
  curl -fsS --max-time 2 "${url}" >/dev/null 2>&1
}

is_port_listening() {
  local port="$1"
  lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1
}

stop_port_listener() {
  local port="$1"
  local label="$2"
  local pids
  pids="$(lsof -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' || true)"
  if [[ -z "${pids// }" ]]; then
    return 0
  fi
  log "Stopping ${label} on port ${port} (pid ${pids})..."
  # shellcheck disable=SC2086
  kill -TERM ${pids} >/dev/null 2>&1 || true
  sleep 2
  pids="$(lsof -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' || true)"
  if [[ -n "${pids// }" ]]; then
    log "Force stopping ${label} on port ${port}..."
    # shellcheck disable=SC2086
    kill -KILL ${pids} >/dev/null 2>&1 || true
    sleep 1
  fi
}

wait_until_service_down() {
  local url="$1"
  local port="$2"
  local retries="${3:-10}"
  local sleep_sec="${4:-1}"
  local i
  for i in $(seq 1 "${retries}"); do
    if ! is_http_ready "${url}" && ! is_port_listening "${port}"; then
      return 0
    fi
    sleep "${sleep_sec}"
  done
  return 1
}

stop_matching_processes() {
  local label="$1"
  shift
  local pattern
  for pattern in "$@"; do
    if pgrep -f "${pattern}" >/dev/null 2>&1; then
      log "Stopping ${label} (${pattern})..."
      pkill -TERM -f "${pattern}" >/dev/null 2>&1 || true
      sleep 1
    fi
  done
}

force_stop_matching_processes() {
  local label="$1"
  shift
  local pattern
  for pattern in "$@"; do
    if pgrep -f "${pattern}" >/dev/null 2>&1; then
      log "Force stopping ${label} (${pattern})..."
      pkill -KILL -f "${pattern}" >/dev/null 2>&1 || true
      sleep 1
    fi
  done
}

stop_pid_file_process() {
  local name="$1"
  local pid_file="$2"
  if [[ ! -f "${pid_file}" ]]; then
    return 1
  fi
  local pid
  pid="$(cat "${pid_file}" 2>/dev/null || true)"
  if is_pid_running "${pid}"; then
    log "Stopping ${name} launcher by pid ${pid}..."
    kill "${pid}" >/dev/null 2>&1 || true
    sleep 1
  fi
  rm -f "${pid_file}"
  return 0
}

load_custom_services() {
  if [[ -f "${SERVICES_FILE}" ]]; then
    # shellcheck disable=SC1090
    source "${SERVICES_FILE}"
    log "Loaded custom services from scripts/services.local.sh"
  fi
}

stop_myllm() {
  stop_pid_file_process "myllm" "${MYLLM_PID_FILE}" || true
  stop_matching_processes "myllm" \
    "maven.multiModuleProjectDirectory=${ROOT_DIR}.*spring-boot:run" \
    "${ROOT_DIR}/target/classes.*MyllmApplication"

  if is_http_ready "http://127.0.0.1:8080/api/chat/health" || is_port_listening 8080; then
    log "myllm still responding on 8080, retrying stop..."
    force_stop_matching_processes "myllm" \
      "maven.multiModuleProjectDirectory=${ROOT_DIR}" \
      "${ROOT_DIR}/target/classes"
    stop_port_listener 8080 "myllm"
  fi

  if wait_until_service_down "http://127.0.0.1:8080/api/chat/health" 8080 10 1; then
    log "myllm stopped"
  else
    log "myllm may still be running on 8080"
  fi
}

stop_docforge() {
  stop_pid_file_process "DocForge" "${DOCFORGE_PID_FILE}" || true
  stop_matching_processes "DocForge" \
    "${DOCFORGE_DIR}/.venv/bin/uvicorn" \
    "${DOCFORGE_DIR}.*uvicorn"

  if is_http_ready "http://127.0.0.1:8000/ready" || is_port_listening 8000; then
    log "DocForge still responding on 8000, retrying stop..."
    force_stop_matching_processes "DocForge" \
      "${DOCFORGE_DIR}/.venv/bin/uvicorn" \
      "${DOCFORGE_DIR}.*uvicorn"
    stop_port_listener 8000 "DocForge"
  fi

  if wait_until_service_down "http://127.0.0.1:8000/ready" 8000 10 1; then
    log "DocForge stopped"
  else
    log "DocForge may still be running on 8000"
  fi
}

stop_infra_containers() {
  log "Stopping infra containers if running..."
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    docker stop rag_hybrid minio-server myllm-neo4j >/dev/null 2>&1 || true
  else
    log "Docker is not ready, skip container stop"
  fi
}

print_summary() {
  local myllm_status docforge_status neo4j_status
  myllm_status="down"
  docforge_status="down"
  neo4j_status="down"

  curl -fsS --max-time 2 "http://127.0.0.1:8080/api/chat/health" >/dev/null 2>&1 && myllm_status="up"
  curl -fsS --max-time 2 "http://127.0.0.1:8000/ready" >/dev/null 2>&1 && docforge_status="up"
  curl -fsS --max-time 2 "http://127.0.0.1:7474" >/dev/null 2>&1 && neo4j_status="up"

  log "Service summary after stop:"
  printf '  - myllm:    %s\n' "${myllm_status}"
  printf '  - docforge: %s\n' "${docforge_status}"
  printf '  - neo4j:    %s\n' "${neo4j_status}"
}

main() {
  exec /bin/bash "${ROOT_DIR}/scripts/appctl.sh" stop all
}

main "$@"
