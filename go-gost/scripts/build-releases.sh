#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_DIR="${ROOT_DIR}/releases"
GO_BIN="${GO_BIN:-go}"
LDFLAGS="${LDFLAGS:--s -w}"

build_target() {
  local name="$1"
  local goos="$2"
  local goarch="$3"
  local goarm="${4:-}"

  echo "[INFO] Building ${name}"
  export GOOS="${goos}"
  export GOARCH="${goarch}"
  export CGO_ENABLED=0

  if [[ -n "${goarm}" ]]; then
    export GOARM="${goarm}"
  else
    unset GOARM || true
  fi

  (
    cd "${ROOT_DIR}"
    "${GO_BIN}" build -trimpath -ldflags="${LDFLAGS}" -o "${RELEASE_DIR}/${name}" .
  )
  chmod +x "${RELEASE_DIR}/${name}"
}

main() {
  mkdir -p "${RELEASE_DIR}"
  rm -f "${RELEASE_DIR}"/gost-linux-* "${RELEASE_DIR}/SHA256SUMS"

  build_target gost-linux-amd64 linux amd64
  build_target gost-linux-arm64 linux arm64
  build_target gost-linux-armv7 linux arm 7
  build_target gost-linux-armv6 linux arm 6

  if command -v sha256sum >/dev/null 2>&1; then
    (
      cd "${RELEASE_DIR}"
      sha256sum gost-linux-* | sed 's/ \*/  /' > SHA256SUMS
    )
    echo "[INFO] Wrote ${RELEASE_DIR}/SHA256SUMS"
  else
    echo "[WARN] sha256sum not found; skipped checksum manifest"
  fi
}

main "$@"
