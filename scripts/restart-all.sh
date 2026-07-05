#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() {
  printf '[restart-all] %s\n' "$1"
}

main() {
  exec /bin/bash "${ROOT_DIR}/scripts/appctl.sh" restart all
}

main "$@"
