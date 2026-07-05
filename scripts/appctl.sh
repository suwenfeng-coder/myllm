#!/bin/bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCFORGE_DIR="/Users/suwenfeng/Documents/code/python/docforge"
RUNTIME_DIR="${ROOT_DIR}/.runtime"
SERVICES_FILE="${ROOT_DIR}/scripts/services.local.sh"
MYLLM_PID_FILE="${RUNTIME_DIR}/myllm.pid"
DOCFORGE_PID_FILE="${RUNTIME_DIR}/docforge.pid"

mkdir -p "${RUNTIME_DIR}"

log() {
  printf '[appctl] %s\n' "$1"
}

usage() {
  cat <<'EOF'
Usage:
  ./scripts/appctl.sh <start|stop|restart|status|logs> <service>

Services:
  myllm       Java Spring Boot 项目（别名: java, app）
  docforge    Python DocForge 项目（别名: python）
  postgres    rag_hybrid 容器（别名: pg, paradedb）
  minio       minio-server 容器
  neo4j       myllm-neo4j 容器
  all         全部服务

Examples:
  ./scripts/appctl.sh restart myllm
  ./scripts/appctl.sh restart python
  ./scripts/appctl.sh stop docforge
  ./scripts/appctl.sh start neo4j
  ./scripts/appctl.sh status all
  ./scripts/appctl.sh logs myllm
EOF
}

normalize_service() {
  case "${1:-}" in
    myllm|java|app) printf 'myllm' ;;
    docforge|python) printf 'docforge' ;;
    postgres|postgresql|pg|paradedb|rag_hybrid) printf 'postgres' ;;
    minio|minio-server) printf 'minio' ;;
    neo4j|myllm-neo4j) printf 'neo4j' ;;
    all) printf 'all' ;;
    *) return 1 ;;
  esac
}

# macOS 默认没有 timeout 命令，使用系统自带 Perl 的 alarm 为外部命令增加硬超时。
run_with_timeout() {
  local timeout_seconds="$1"
  shift
  /usr/bin/perl -e '$seconds = shift @ARGV; alarm $seconds; exec @ARGV; exit 127' \
    "${timeout_seconds}" "$@"
}

is_http_ready() {
  curl -fsS --max-time 2 "$1" >/dev/null 2>&1
}

is_port_listening() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

wait_http_ready() {
  local url="$1"
  local retries="${2:-30}"
  local sleep_seconds="${3:-1}"
  local i
  for i in $(seq 1 "${retries}"); do
    if is_http_ready "${url}"; then
      return 0
    fi
    sleep "${sleep_seconds}"
  done
  return 1
}

wait_port_down() {
  local port="$1"
  local retries="${2:-15}"
  local i
  for i in $(seq 1 "${retries}"); do
    if ! is_port_listening "${port}"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

is_pid_running() {
  local pid="${1:-}"
  [[ "${pid}" =~ ^[0-9]+$ ]] && kill -0 "${pid}" >/dev/null 2>&1
}

read_pid_file() {
  local pid_file="$1"
  if [[ -f "${pid_file}" ]]; then
    sed -n '1p' "${pid_file}" 2>/dev/null || true
  fi
}

clear_pid_file() {
  local pid_file="$1"
  if [[ -f "${pid_file}" ]]; then
    : >"${pid_file}"
  fi
}

stop_pid_file_process() {
  local name="$1"
  local pid_file="$2"
  local expected_command_fragment="$3"
  local pid
  pid="$(read_pid_file "${pid_file}")"
  if ! is_pid_running "${pid}"; then
    clear_pid_file "${pid_file}"
    return 0
  fi

  local process_command
  process_command="$(ps -p "${pid}" -o command= 2>/dev/null || true)"
  if [[ -n "${expected_command_fragment}" && "${process_command}" != *"${expected_command_fragment}"* ]]; then
    log "Ignoring stale ${name} pid file: pid ${pid} belongs to another process"
    clear_pid_file "${pid_file}"
    return 0
  fi

  log "Stopping ${name} launcher (pid ${pid})..."
  kill -TERM "${pid}" >/dev/null 2>&1 || true
  local i
  for i in $(seq 1 10); do
    if ! is_pid_running "${pid}"; then
      clear_pid_file "${pid_file}"
      return 0
    fi
    sleep 1
  done
  log "${name} launcher did not stop in 10s; sending KILL"
  kill -KILL "${pid}" >/dev/null 2>&1 || true
  clear_pid_file "${pid_file}"
}

stop_port_processes() {
  local name="$1"
  local port="$2"
  local pids
  pids="$(lsof -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' || true)"
  if [[ -z "${pids// }" ]]; then
    return 0
  fi

  log "Stopping ${name} listener on port ${port} (pid ${pids})..."
  # shellcheck disable=SC2086
  kill -TERM ${pids} >/dev/null 2>&1 || true
  if wait_port_down "${port}" 10; then
    return 0
  fi

  pids="$(lsof -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' || true)"
  if [[ -n "${pids// }" ]]; then
    log "${name} listener did not stop in 10s; sending KILL"
    # shellcheck disable=SC2086
    kill -KILL ${pids} >/dev/null 2>&1 || true
    wait_port_down "${port}" 5 || true
  fi
}

docker_ready() {
  run_with_timeout 8 docker info >/dev/null 2>&1
}

ensure_docker() {
  if docker_ready; then
    return 0
  fi
  log "Docker is not ready; starting Docker Desktop..."
  open -a Docker >/dev/null 2>&1 || true
  local i
  for i in $(seq 1 30); do
    if docker_ready; then
      log "Docker ready"
      return 0
    fi
    if (( i % 5 == 0 )); then
      log "Waiting for Docker (${i}/30)..."
    fi
    sleep 2
  done
  log "Docker did not become ready"
  return 1
}

container_exists() {
  run_with_timeout 8 docker inspect "$1" >/dev/null 2>&1
}

container_running() {
  local state
  state="$(run_with_timeout 8 docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || true)"
  [[ "${state}" == "true" ]]
}

start_container() {
  local service_name="$1"
  local container_name="$2"
  local ready_url="${3:-}"

  ensure_docker || return 1
  if ! container_exists "${container_name}"; then
    log "${service_name} container does not exist: ${container_name}"
    return 1
  fi
  if container_running "${container_name}"; then
    log "${service_name} already running"
  else
    log "Starting ${service_name} (${container_name})..."
    if ! run_with_timeout 30 docker start "${container_name}" >/dev/null; then
      log "Failed or timed out starting ${container_name}"
      return 1
    fi
  fi

  if [[ -n "${ready_url}" ]]; then
    if wait_http_ready "${ready_url}" 60 1; then
      log "${service_name} ready"
    else
      log "${service_name} started but health endpoint is not ready: ${ready_url}"
      return 1
    fi
  fi
}

stop_container() {
  local service_name="$1"
  local container_name="$2"

  if ! docker_ready; then
    log "Docker unavailable; cannot stop ${service_name}"
    return 1
  fi
  if ! container_exists "${container_name}"; then
    log "${service_name} container does not exist: ${container_name}"
    return 0
  fi
  if ! container_running "${container_name}"; then
    log "${service_name} already stopped"
    return 0
  fi

  log "Stopping ${service_name} (${container_name}, grace 10s)..."
  if run_with_timeout 20 docker stop -t 10 "${container_name}" >/dev/null; then
    log "${service_name} stopped"
  else
    log "Stopping ${service_name} failed or timed out"
    return 1
  fi
}

load_secrets() {
  if [[ -f "${ROOT_DIR}/scripts/secrets.local.sh" ]]; then
    # shellcheck disable=SC1090
    source "${ROOT_DIR}/scripts/secrets.local.sh"
  fi
}

start_myllm() {
  load_secrets
  if is_http_ready "http://127.0.0.1:8080/api/chat/health"; then
    log "myllm already ready"
    return 0
  fi
  if is_port_listening 8080; then
    log "Port 8080 is occupied, but myllm health check failed; refusing to start"
    return 1
  fi

  log "Starting myllm Java application..."
  nohup /bin/bash -lc "cd \"${ROOT_DIR}\" && exec mvn -q -DskipTests spring-boot:run" \
    >"${RUNTIME_DIR}/myllm.log" 2>&1 &
  local pid=$!
  printf '%s\n' "${pid}" >"${MYLLM_PID_FILE}"

  if wait_http_ready "http://127.0.0.1:8080/api/chat/health" 90 1; then
    log "myllm ready (pid ${pid})"
    return 0
  fi
  log "myllm failed to become ready; inspect ${RUNTIME_DIR}/myllm.log"
  tail -n 30 "${RUNTIME_DIR}/myllm.log" 2>/dev/null || true
  return 1
}

stop_myllm() {
  stop_pid_file_process "myllm" "${MYLLM_PID_FILE}" "${ROOT_DIR}"
  stop_port_processes "myllm" 8080
  if is_port_listening 8080; then
    log "myllm may still be running on port 8080"
    return 1
  fi
  log "myllm stopped"
}

start_docforge() {
  if is_http_ready "http://127.0.0.1:8000/ready"; then
    log "DocForge already ready"
    return 0
  fi
  if is_port_listening 8000; then
    log "Port 8000 is occupied, but DocForge health check failed; refusing to start"
    return 1
  fi
  if [[ ! -d "${DOCFORGE_DIR}" || ! -d "${DOCFORGE_DIR}/.venv" ]]; then
    log "DocForge project or virtualenv not found: ${DOCFORGE_DIR}"
    return 1
  fi

  log "Starting DocForge Python application..."
  nohup /bin/bash -lc "cd \"${DOCFORGE_DIR}\" && source .venv/bin/activate && exec ./scripts/dev.sh" \
    >"${RUNTIME_DIR}/docforge.log" 2>&1 &
  local pid=$!
  printf '%s\n' "${pid}" >"${DOCFORGE_PID_FILE}"

  if wait_http_ready "http://127.0.0.1:8000/ready" 90 1; then
    log "DocForge ready (pid ${pid})"
    return 0
  fi
  log "DocForge failed to become ready; inspect ${RUNTIME_DIR}/docforge.log"
  tail -n 30 "${RUNTIME_DIR}/docforge.log" 2>/dev/null || true
  return 1
}

stop_docforge() {
  stop_pid_file_process "DocForge" "${DOCFORGE_PID_FILE}" "${DOCFORGE_DIR}"
  stop_port_processes "DocForge" 8000
  if is_port_listening 8000; then
    log "DocForge may still be running on port 8000"
    return 1
  fi
  log "DocForge stopped"
}

start_postgres() {
  ensure_docker || return 1
  if ! container_exists rag_hybrid; then
    log "Creating rag_hybrid from docker-compose.paradedb.yml..."
    if ! run_with_timeout 120 docker compose -f "${ROOT_DIR}/docker-compose.paradedb.yml" up -d rag-hybrid; then
      log "Failed or timed out creating rag_hybrid"
      return 1
    fi
  else
    start_container "PostgreSQL/ParadeDB" rag_hybrid || return 1
  fi
  local i
  for i in $(seq 1 30); do
    if run_with_timeout 5 docker exec rag_hybrid pg_isready -U pgvector -d myllm >/dev/null 2>&1; then
      log "PostgreSQL/ParadeDB ready"
      return 0
    fi
    sleep 1
  done
  log "PostgreSQL/ParadeDB did not become ready"
  return 1
}

start_minio() {
  ensure_docker || return 1
  if ! container_exists minio-server; then
    log "Creating minio-server from docker-compose.paradedb.yml..."
    if ! run_with_timeout 120 docker compose -f "${ROOT_DIR}/docker-compose.paradedb.yml" up -d minio; then
      log "Failed or timed out creating minio-server"
      return 1
    fi
  else
    start_container "MinIO" minio-server "http://127.0.0.1:9000/minio/health/live"
  fi
}

start_neo4j() {
  start_container "Neo4j" myllm-neo4j "http://127.0.0.1:7474"
}

start_service() {
  case "$1" in
    myllm) start_myllm ;;
    docforge) start_docforge ;;
    postgres) start_postgres ;;
    minio) start_minio ;;
    neo4j) start_neo4j ;;
    all)
      start_postgres || return 1
      start_minio || return 1
      start_neo4j || return 1
      start_docforge || return 1
      start_myllm || return 1
      load_custom_services
      if declare -f start_custom_services >/dev/null 2>&1; then
        log "Starting custom services..."
        start_custom_services
      fi
      ;;
  esac
}

stop_service() {
  case "$1" in
    myllm) stop_myllm ;;
    docforge) stop_docforge ;;
    postgres) stop_container "PostgreSQL/ParadeDB" rag_hybrid ;;
    minio) stop_container "MinIO" minio-server ;;
    neo4j) stop_container "Neo4j" myllm-neo4j ;;
    all)
      load_custom_services
      if declare -f stop_custom_services >/dev/null 2>&1; then
        log "Stopping custom services..."
        stop_custom_services
      fi
      stop_myllm || true
      stop_docforge || true
      stop_container "Neo4j" myllm-neo4j || true
      stop_container "MinIO" minio-server || true
      stop_container "PostgreSQL/ParadeDB" rag_hybrid || true
      ;;
  esac
}

restart_service() {
  log "Restarting $1..."
  stop_service "$1" || return 1
  start_service "$1"
}

print_http_status() {
  local name="$1"
  local url="$2"
  if is_http_ready "${url}"; then
    printf '%-18s %s\n' "${name}" "UP"
  else
    printf '%-18s %s\n' "${name}" "DOWN"
  fi
}

print_container_status() {
  local name="$1"
  local container="$2"
  if ! docker_ready; then
    printf '%-18s %s\n' "${name}" "DOCKER_DOWN"
  elif container_running "${container}"; then
    printf '%-18s %s\n' "${name}" "UP"
  elif container_exists "${container}"; then
    printf '%-18s %s\n' "${name}" "DOWN"
  else
    printf '%-18s %s\n' "${name}" "NOT_FOUND"
  fi
}

print_container_status_with_docker_ready() {
  local name="$1"
  local container="$2"
  if container_running "${container}"; then
    printf '%-18s %s\n' "${name}" "UP"
  elif container_exists "${container}"; then
    printf '%-18s %s\n' "${name}" "DOWN"
  else
    printf '%-18s %s\n' "${name}" "NOT_FOUND"
  fi
}

status_service() {
  case "$1" in
    myllm) print_http_status "myllm" "http://127.0.0.1:8080/api/chat/health" ;;
    docforge) print_http_status "docforge" "http://127.0.0.1:8000/ready" ;;
    postgres) print_container_status "postgres/rag_hybrid" rag_hybrid ;;
    minio) print_container_status "minio" minio-server ;;
    neo4j) print_container_status "neo4j" myllm-neo4j ;;
    all)
      print_http_status "myllm" "http://127.0.0.1:8080/api/chat/health"
      print_http_status "docforge" "http://127.0.0.1:8000/ready"
      if docker_ready; then
        print_container_status_with_docker_ready "postgres/rag_hybrid" rag_hybrid
        print_container_status_with_docker_ready "minio" minio-server
        print_container_status_with_docker_ready "neo4j" myllm-neo4j
      else
        printf '%-18s %s\n' "postgres/rag_hybrid" "DOCKER_DOWN"
        printf '%-18s %s\n' "minio" "DOCKER_DOWN"
        printf '%-18s %s\n' "neo4j" "DOCKER_DOWN"
      fi
      ;;
  esac
}

logs_service() {
  case "$1" in
    myllm) tail -n 120 "${RUNTIME_DIR}/myllm.log" 2>/dev/null || log "No myllm log" ;;
    docforge) tail -n 120 "${RUNTIME_DIR}/docforge.log" 2>/dev/null || log "No DocForge log" ;;
    postgres) run_with_timeout 10 docker logs --tail 120 rag_hybrid ;;
    minio) run_with_timeout 10 docker logs --tail 120 minio-server ;;
    neo4j) run_with_timeout 10 docker logs --tail 120 myllm-neo4j ;;
    all)
      log "logs requires a single service, not all"
      return 1
      ;;
  esac
}

load_custom_services() {
  if [[ -f "${SERVICES_FILE}" ]]; then
    # shellcheck disable=SC1090
    source "${SERVICES_FILE}"
  fi
}

main() {
  local action="${1:-}"
  local requested_service="${2:-}"
  if [[ -z "${action}" || -z "${requested_service}" ]]; then
    usage
    return 2
  fi

  local service
  if ! service="$(normalize_service "${requested_service}")"; then
    log "Unknown service: ${requested_service}"
    usage
    return 2
  fi

  case "${action}" in
    start) start_service "${service}" ;;
    stop) stop_service "${service}" ;;
    restart) restart_service "${service}" ;;
    status) status_service "${service}" ;;
    logs) logs_service "${service}" ;;
    *)
      log "Unknown action: ${action}"
      usage
      return 2
      ;;
  esac
}

main "$@"
