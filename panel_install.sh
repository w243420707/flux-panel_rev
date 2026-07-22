#!/usr/bin/env bash
set -Eeuo pipefail

RAW_DEPLOY_URL="https://raw.githubusercontent.com/w243420707/flux-panel_rev/refs/heads/main/deploy.sh"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_DEPLOY="${SCRIPT_DIR}/deploy.sh"

echo "panel_install.sh is deprecated. Switching to deploy.sh..."

if [[ -x "${LOCAL_DEPLOY}" ]]; then
  exec "${LOCAL_DEPLOY}" "$@"
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

curl -fsSL "${RAW_DEPLOY_URL}" -o "${tmp_dir}/deploy.sh"
chmod +x "${tmp_dir}/deploy.sh"
exec "${tmp_dir}/deploy.sh" "$@"
