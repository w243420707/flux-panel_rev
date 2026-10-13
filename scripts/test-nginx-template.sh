#!/usr/bin/env bash
# Static regression check for the generated Nginx upstream connection policy.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_SCRIPT="${1:-${ROOT_DIR}/deploy.sh}"

close_count="$(grep -Fc "'' close;" "${DEPLOY_SCRIPT}" || true)"
empty_count="$(grep -Fc "'' '';" "${DEPLOY_SCRIPT}" || true)"

if [[ "${close_count}" -ne 0 ]]; then
  printf 'FAIL: generated Nginx template still forces Connection: close (%s occurrence(s))\n' "${close_count}" >&2
  exit 1
fi

if [[ "${empty_count}" -ne 2 ]]; then
  printf 'FAIL: expected two keepalive map entries, found %s\n' "${empty_count}" >&2
  exit 1
fi

printf 'PASS: HTTP upstreams leave Connection empty while WebSocket upgrades remain supported\n'
