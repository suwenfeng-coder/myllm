#!/usr/bin/env bash
# 环境与服务探测（H0/H8 make doctor）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ok=0
warn=0

check_cmd() {
  local name="$1"
  shift
  if command -v "$1" >/dev/null 2>&1; then
    printf '  [OK]   %s: %s\n' "$name" "$(command -v "$1")"
    ok=$((ok + 1))
  else
    printf '  [MISS] %s not found\n' "$name"
    warn=$((warn + 1))
  fi
}

check_port() {
  local label="$1"
  local port="$2"
  if nc -z 127.0.0.1 "$port" 2>/dev/null; then
    printf '  [OK]   %s listening on :%s\n' "$label" "$port"
    ok=$((ok + 1))
  else
    printf '  [DOWN] %s :%s\n' "$label" "$port"
    warn=$((warn + 1))
  fi
}

echo "=== myllm doctor ==="
echo ""
echo "-- Commands --"
check_cmd Java java
check_cmd Maven mvn
check_cmd Docker docker
check_cmd Bash bash

if command -v java >/dev/null 2>&1; then
  printf '         Java version: %s\n' "$(java -version 2>&1 | head -1)"
fi
if command -v mvn >/dev/null 2>&1; then
  printf '         Maven version: %s\n' "$(mvn -version 2>/dev/null | head -1)"
fi

echo ""
echo "-- Services (optional) --"
check_port "myllm" 8080
check_port "PostgreSQL rag_hybrid" 5434
check_port "MinIO" 9000
check_port "DocForge" 8000
check_port "Neo4j Bolt" 7687
check_port "Ollama" 11434

echo ""
echo "-- Docs --"
for f in AGENTS.md ARCHITECTURE.md HarnessEngineering改造方案.md docs/design-docs/ADR-001-harness-boundaries.md; do
  if [[ -f "$f" ]]; then
    printf '  [OK]   %s\n' "$f"
  else
    printf '  [MISS] %s\n' "$f"
    warn=$((warn + 1))
  fi
done

echo ""
if [[ "$warn" -eq 0 ]]; then
  echo "Summary: all checks passed ($ok ok)"
  exit 0
else
  echo "Summary: $ok ok, $warn issues (missing tools/services are OK for offline dev)"
  exit 0
fi
