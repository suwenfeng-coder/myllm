#!/usr/bin/env bash
# 检查文档中的相对 Markdown 链接目标是否存在（H8 子集）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

missing=0

check_file() {
  if [[ -f "$1" ]]; then
    printf '  [OK]   %s\n' "$1"
  else
    printf '  [MISS] %s\n' "$1"
    missing=$((missing + 1))
  fi
}

echo "=== doc link check ==="
check_file "AGENTS.md"
check_file "ARCHITECTURE.md"
check_file "docs/index.md"
check_file "docs/design-docs/ADR-001-harness-boundaries.md"
check_file "docs/exec-plans/active/H1-harness-domain-and-persistence.md"
check_file "HarnessEngineering改造方案.md"
check_file "evals/manifest.yaml"
check_file "evals/rag-retrieval.jsonl"
check_file "src/main/resources/db/harness_schema.sql"

if [[ "$missing" -gt 0 ]]; then
  echo "Failed: $missing missing file(s)"
  exit 1
fi
echo "All doc links OK"
exit 0
