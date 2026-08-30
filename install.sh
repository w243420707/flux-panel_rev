#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

APP_NAME="gost"
INSTALL_DIR="/etc/gost"
LOG_DIR="/var/log/gost"
LOG_FILE="${LOG_DIR}/gost.log"
SERVICE_FILE="/etc/systemd/system/gost.service"
LOGROTATE_FILE="/etc/logrotate.d/gost"
BINARY_SOURCE_FILE="${INSTALL_DIR}/source.conf"
DEFAULT_BINARY_BASE_URL="${GOST_DEFAULT_BINARY_BASE_URL:-https://raw.githubusercontent.com/w243420707/flux-panel-node-assets/refs/heads/main/releases}"
BINARY_BASE_URL="${GOST_BINARY_BASE_URL:-}"
BINARY_FALLBACK_BASE_URL="${GOST_BINARY_FALLBACK_BASE_URL:-}"
BINARY_SOURCE_MODE="${GOST_BINARY_SOURCE_MODE:-}"
SWAP_FILE="${GOST_SWAP_FILE:-/swapfile}"
SWAP_SIZE_MB="${GOST_SWAP_SIZE_MB:-}"
SWAP_MIN_FREE_MB="${GOST_SWAP_MIN_FREE_MB:-1024}"
SWAPPINESS="${GOST_SWAPPINESS:-10}"
SWAPPINESS_FILE="/etc/sysctl.d/99-flux-panel-swap.conf"

ACTION=""
SERVER_ADDR=""
SECRET=""
ASSUME_YES=0
ARCH=""
PKG_MANAGER=""
OS_ID=""
OS_VERSION=""
OS_PRETTY=""
KERNEL_RELEASE=""
INIT_SYSTEM=""
VIRT_TYPE=""

log() { printf '[INFO] %s\n' "$*"; }
warn() { printf '[WARN] %s\n' "$*"; }
err() { printf '[ERR ] %s\n' "$*" >&2; }
die() { err "$*"; exit 1; }

usage() {
  cat <<EOF
${APP_NAME} node installer

Usage:
  sudo bash install.sh [action] [options]

Actions:
  install     Install or reinstall the node
  update      Download the latest binary and restart the service
  uninstall   Stop and remove the node
  status      Show service status
  logs        Follow service logs
  swap        Configure or check swap
  menu        Interactive menu (default)

Options:
  -a, --addr ADDR    Panel/server address
  -s, --secret KEY   Node secret
  -b, --binary-base-url URL
                     Primary URL serving node releases
  -f, --fallback-binary-base-url URL
                     Fallback URL serving node releases
  -m, --source-mode MODE
                     Asset source mode: github or local
  -y, --yes         Non-interactive yes for confirmations
  -h, --help        Show this help

Binary source:
  ${BINARY_BASE_URL:-not configured}/gost-linux-\${ARCH}

Supported Linux architectures:
  amd64, arm64, armv7, armv6

Swap defaults:
  Existing swap is preserved. When no swap is active, the installer creates
  2 GB, 4 GB, or 8 GB according to system memory and keeps at least 1 GB free.
  Set GOST_SWAP_SIZE_MB to override the automatic target size.
EOF
}

ensure_root() {
  if [[ "${EUID}" -eq 0 ]]; then
    return 0
  fi
  if command -v sudo >/dev/null 2>&1; then
    exec sudo -E bash "$0" "$@"
  fi
  die "Please run this script as root."
}

confirm() {
  local prompt="$1"
  if [[ "${ASSUME_YES}" -eq 1 ]]; then
    return 0
  fi
  read -r -p "${prompt} [y/N]: " answer
  case "${answer}" in
    y|Y|yes|YES) return 0 ;;
    *) return 1 ;;
  esac
}

detect_os() {
  [[ "$(uname -s)" == "Linux" ]] || die "This installer supports Linux only."
  KERNEL_RELEASE="$(uname -r)"

  if [[ -f /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    OS_ID="${ID:-unknown}"
    OS_VERSION="${VERSION_ID:-unknown}"
    OS_PRETTY="${PRETTY_NAME:-$ID $VERSION_ID}"
  fi

  if command -v apt-get >/dev/null 2>&1; then
    PKG_MANAGER="apt-get"
  elif command -v dnf >/dev/null 2>&1; then
    PKG_MANAGER="dnf"
  elif command -v yum >/dev/null 2>&1; then
    PKG_MANAGER="yum"
  elif command -v apk >/dev/null 2>&1; then
    PKG_MANAGER="apk"
  elif command -v pacman >/dev/null 2>&1; then
    PKG_MANAGER="pacman"
  elif command -v zypper >/dev/null 2>&1; then
    PKG_MANAGER="zypper"
  else
    PKG_MANAGER=""
  fi

  [[ -n "${PKG_MANAGER}" ]] || die "No supported package manager found."

  if [[ -d /run/systemd/system ]]; then
    INIT_SYSTEM="systemd"
  elif command -v rc-service >/dev/null 2>&1; then
    INIT_SYSTEM="openrc"
  else
    INIT_SYSTEM="unknown"
  fi

  case "$(uname -m)" in
    x86_64|amd64|x64) ARCH="amd64" ;;
    aarch64|arm64) ARCH="arm64" ;;
    armv8l|armv7l|armv7|armv7hl|armhf) ARCH="armv7" ;;
    armv6l|armv6|armel) ARCH="armv6" ;;
    *)
      die "Unsupported architecture: $(uname -m). Supported: amd64, arm64, armv7, armv6."
      ;;
  esac

  if command -v systemd-detect-virt >/dev/null 2>&1; then
    VIRT_TYPE="$(systemd-detect-virt 2>/dev/null || true)"
    [[ -n "${VIRT_TYPE}" && "${VIRT_TYPE}" != "none" ]] || VIRT_TYPE="bare-metal"
  else
    VIRT_TYPE="unknown"
  fi

  log "Detected OS: ${OS_PRETTY:-unknown}"
  log "Detected kernel: ${KERNEL_RELEASE}"
  log "Detected init system: ${INIT_SYSTEM}"
  log "Detected package manager: ${PKG_MANAGER}"
  log "Detected environment: ${VIRT_TYPE}"
  log "Detected architecture: ${ARCH} (from $(uname -m))"
}

install_packages() {
  if command -v curl >/dev/null 2>&1 \
    && command -v logrotate >/dev/null 2>&1 \
    && command -v mkswap >/dev/null 2>&1 \
    && command -v swapon >/dev/null 2>&1 \
    && command -v sysctl >/dev/null 2>&1 \
    && [[ "${INIT_SYSTEM}" == "systemd" ]]; then
    return 0
  fi

  log "Installing required packages..."
  case "${PKG_MANAGER}" in
    apt-get)
      apt-get update
      apt-get install -y curl ca-certificates logrotate util-linux procps
      ;;
    dnf)
      dnf install -y curl ca-certificates logrotate util-linux procps-ng
      ;;
    yum)
      yum install -y curl ca-certificates logrotate util-linux procps-ng
      ;;
    apk)
      apk add --no-cache curl ca-certificates logrotate util-linux procps
      ;;
    pacman)
      pacman -Sy --noconfirm curl ca-certificates logrotate util-linux procps-ng
      ;;
    zypper)
      zypper --non-interactive install curl ca-certificates logrotate util-linux procps
      ;;
  esac

  [[ "${INIT_SYSTEM}" == "systemd" ]] || die "systemd is required for this installer. Detected init system: ${INIT_SYSTEM}."
}

is_unsigned_integer() {
  [[ "${1:-}" =~ ^[0-9]+$ ]]
}

recommended_swap_mb() {
  local memory_mb

  if [[ -n "${SWAP_SIZE_MB}" ]]; then
    if ! is_unsigned_integer "${SWAP_SIZE_MB}" || [[ "${SWAP_SIZE_MB}" -lt 512 ]]; then
      warn "Ignoring invalid GOST_SWAP_SIZE_MB=${SWAP_SIZE_MB}; using automatic sizing." >&2
    else
      printf '%s' "${SWAP_SIZE_MB}"
      return 0
    fi
  fi

  memory_mb="$(awk '/^MemTotal:/ { print int($2 / 1024); exit }' /proc/meminfo 2>/dev/null || true)"
  if ! is_unsigned_integer "${memory_mb}" || [[ "${memory_mb}" -le 0 ]]; then
    memory_mb=2048
  fi

  if [[ "${memory_mb}" -le 1024 ]]; then
    printf '2048'
  elif [[ "${memory_mb}" -le 4096 ]]; then
    printf '4096'
  else
    printf '8192'
  fi
}

current_swap_mb() {
  awk '/^SwapTotal:/ { print int($2 / 1024); exit }' /proc/meminfo 2>/dev/null || printf '0'
}

configure_swappiness() {
  local value="${SWAPPINESS}" temp_file

  if ! is_unsigned_integer "${value}" || [[ "${value}" -gt 100 ]]; then
    warn "Invalid GOST_SWAPPINESS=${value}; using 10."
    value=10
  fi
  SWAPPINESS="${value}"

  if [[ -d /etc/sysctl.d ]]; then
    temp_file="${SWAPPINESS_FILE}.tmp.$$"
    if printf 'vm.swappiness = %s\n' "${value}" > "${temp_file}"; then
      chmod 644 "${temp_file}"
      if ! mv -f "${temp_file}" "${SWAPPINESS_FILE}"; then
        rm -f "${temp_file}"
        warn "Could not persist vm.swappiness."
      fi
    else
      rm -f "${temp_file}"
      warn "Could not persist vm.swappiness."
    fi
  fi

  if command -v sysctl >/dev/null 2>&1; then
    sysctl -w "vm.swappiness=${value}" >/dev/null 2>&1 || warn "Could not apply vm.swappiness=${value}."
  elif [[ -w /proc/sys/vm/swappiness ]]; then
    printf '%s' "${value}" > /proc/sys/vm/swappiness || warn "Could not apply vm.swappiness=${value}."
  fi
}

is_swap_active() {
  local target="$1"
  awk -v target="${target}" 'NR > 1 && $1 == target { found = 1 } END { exit(found ? 0 : 1) }' /proc/swaps 2>/dev/null
}

ensure_fstab_swap_entry() {
  local target="$1"

  if [[ ! -f /etc/fstab ]] && ! touch /etc/fstab; then
    warn "Could not create /etc/fstab; swap is active but may not survive a reboot."
    return 0
  fi
  if awk -v target="${target}" '$1 == target { found = 1 } END { exit(found ? 0 : 1) }' /etc/fstab; then
    return 0
  fi

  printf '%s none swap sw 0 0\n' "${target}" >> /etc/fstab || warn "Could not persist ${target} in /etc/fstab."
}

ensure_swap() {
  local target_mb existing_mb needed_mb available_mb create_mb reserve_mb
  local swap_path swap_dir command_name

  case "${VIRT_TYPE}" in
    openvz|lxc|lxc-libvirt|docker|podman|systemd-nspawn|wsl)
      warn "Swap is managed by the host in ${VIRT_TYPE}; skipped swap-file creation."
      return 0
      ;;
  esac

  configure_swappiness

  for command_name in awk dd df mkswap swapon; do
    if ! command -v "${command_name}" >/dev/null 2>&1; then
      warn "Cannot configure swap because ${command_name} is unavailable."
      return 0
    fi
  done

  target_mb="$(recommended_swap_mb)"
  existing_mb="$(current_swap_mb)"
  if ! is_unsigned_integer "${existing_mb}"; then
    existing_mb=0
  fi

  if [[ "${existing_mb}" -ge "${target_mb}" ]]; then
    log "Swap is already available: ${existing_mb} MB (target ${target_mb} MB)."
    return 0
  fi

  if [[ "${existing_mb}" -gt 0 ]]; then
    log "Existing swap is preserved: ${existing_mb} MB."
  fi

  needed_mb=$((target_mb - existing_mb))
  swap_path="${SWAP_FILE}"

  if [[ -e "${swap_path}" ]]; then
    if ! is_swap_active "${swap_path}" && swapon "${swap_path}" >/dev/null 2>&1; then
      ensure_fstab_swap_entry "${swap_path}"
      existing_mb="$(current_swap_mb)"
      if [[ "${existing_mb}" -ge "${target_mb}" ]]; then
        log "Activated existing swap file ${swap_path}: ${existing_mb} MB total."
        return 0
      fi
      needed_mb=$((target_mb - existing_mb))
    fi
    swap_path="${SWAP_FILE}.flux"
  fi

  if [[ -e "${swap_path}" ]]; then
    if is_swap_active "${swap_path}"; then
      warn "Managed swap file ${swap_path} is active but total swap is below the target; it will not be resized while in use."
    else
      warn "Path ${swap_path} already exists and is not active swap; it will not be overwritten."
    fi
    return 0
  fi

  swap_dir="$(dirname "${swap_path}")"
  available_mb="$(df -Pm "${swap_dir}" 2>/dev/null | awk 'NR == 2 { print $4; exit }')"
  if ! is_unsigned_integer "${available_mb}"; then
    warn "Could not determine free disk space; skipped swap-file creation."
    return 0
  fi

  reserve_mb="${SWAP_MIN_FREE_MB}"
  if ! is_unsigned_integer "${reserve_mb}" || [[ "${reserve_mb}" -lt 512 ]]; then
    reserve_mb=1024
  fi
  if [[ "${available_mb}" -le $((reserve_mb + 512)) ]]; then
    warn "Only ${available_mb} MB disk space is free; skipped swap-file creation."
    return 0
  fi

  create_mb="${needed_mb}"
  if [[ "${create_mb}" -gt $((available_mb - reserve_mb)) ]]; then
    create_mb=$((((available_mb - reserve_mb) / 256) * 256))
    warn "Disk space is limited; reducing new swap from ${needed_mb} MB to ${create_mb} MB."
  fi
  if [[ "${create_mb}" -lt 512 ]]; then
    warn "Less than 512 MB can be allocated safely; skipped swap-file creation."
    return 0
  fi

  log "Creating ${create_mb} MB swap file at ${swap_path}..."
  if ! dd if=/dev/zero of="${swap_path}" bs=1M count="${create_mb}" status=none 2>/dev/null; then
    rm -f "${swap_path}"
    if ! dd if=/dev/zero of="${swap_path}" bs=1M count="${create_mb}" >/dev/null 2>&1; then
      rm -f "${swap_path}"
      warn "Swap-file allocation failed; node installation will continue without changing swap."
      return 0
    fi
  fi

  chmod 600 "${swap_path}"
  if ! mkswap "${swap_path}" >/dev/null 2>&1 || ! swapon "${swap_path}" >/dev/null 2>&1; then
    rm -f "${swap_path}"
    warn "This VPS does not allow the generated swap file; node installation will continue without it."
    return 0
  fi

  ensure_fstab_swap_entry "${swap_path}"
  existing_mb="$(current_swap_mb)"
  log "Swap enabled: ${existing_mb} MB total, vm.swappiness=${SWAPPINESS}."
}

binary_name() {
  printf 'gost-linux-%s' "${ARCH}"
}

binary_url() {
  if [[ -n "${GOST_BINARY_URL:-}" ]]; then
    printf '%s' "${GOST_BINARY_URL}"
    return 0
  fi
  [[ -n "${BINARY_BASE_URL}" ]] || die "No node binary source configured. Use -b/--binary-base-url or run the installer from the panel."
  printf '%s/%s' "${BINARY_BASE_URL%/}" "$(binary_name)"
}

binary_url_for_base() {
  local base_url="${1:-}"
  if [[ -n "${GOST_BINARY_URL:-}" ]]; then
    printf '%s' "${GOST_BINARY_URL}"
    return 0
  fi
  [[ -n "${base_url}" ]] || return 1
  printf '%s/%s' "${base_url%/}" "$(binary_name)"
}

trim_whitespace() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "${value}"
}

normalize_base_url() {
  local source
  source="$(trim_whitespace "${1:-}")"
  if [[ -z "${source}" ]]; then
    printf ''
    return 0
  fi

  case "${source}" in
    http://*|https://*)
      printf '%s' "${source%/}"
      ;;
    ws://*)
      printf 'http://%s' "${source#ws://}"
      ;;
    wss://*)
      printf 'https://%s' "${source#wss://}"
      ;;
    *)
      printf 'https://%s' "${source#/}"
      ;;
  esac
}

derive_binary_base_url() {
  local source
  source="$(normalize_base_url "${1:-}")"
  [[ -n "${source}" ]] || return 1
  printf '%s/node/releases' "${source%/}"
}

load_binary_source() {
  if [[ -n "${GOST_BINARY_BASE_URL:-}" || -n "${GOST_BINARY_FALLBACK_BASE_URL:-}" || -n "${GOST_BINARY_SOURCE_MODE:-}" ]]; then
    BINARY_BASE_URL="${GOST_BINARY_BASE_URL:-${BINARY_BASE_URL}}"
    BINARY_FALLBACK_BASE_URL="${GOST_BINARY_FALLBACK_BASE_URL:-${BINARY_FALLBACK_BASE_URL}}"
    BINARY_SOURCE_MODE="${GOST_BINARY_SOURCE_MODE:-${BINARY_SOURCE_MODE}}"
  elif [[ -z "${BINARY_BASE_URL}" && -f "${BINARY_SOURCE_FILE}" ]]; then
    # shellcheck disable=SC1090
    . "${BINARY_SOURCE_FILE}"
  fi
  BINARY_BASE_URL="$(normalize_base_url "${BINARY_BASE_URL}")"
}

read_config_value() {
  local key="$1" file="$2"
  [[ -f "${file}" ]] || return 1
  sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "${file}" | head -n 1
}

resolve_binary_source() {
  if [[ -n "${GOST_BINARY_URL:-}" ]]; then
    return 0
  fi

  if [[ -z "${BINARY_BASE_URL}" ]]; then
    load_binary_source
  fi

  if [[ -z "${BINARY_SOURCE_MODE}" ]]; then
    BINARY_SOURCE_MODE="github"
  fi
  case "${BINARY_SOURCE_MODE}" in
    github|local) ;;
    *) die "Invalid asset source mode: ${BINARY_SOURCE_MODE}. Use github or local." ;;
  esac

  if [[ -z "${BINARY_BASE_URL}" && -n "${SERVER_ADDR:-}" ]]; then
    BINARY_BASE_URL="$(derive_binary_base_url "${SERVER_ADDR}")"
  fi

  if [[ -z "${BINARY_BASE_URL}" ]]; then
    local saved_addr
    saved_addr="$(read_config_value "addr" "${INSTALL_DIR}/config.json" 2>/dev/null || true)"
    if [[ -n "${saved_addr}" ]]; then
      BINARY_BASE_URL="$(derive_binary_base_url "${saved_addr}")"
    fi
  fi

  # Older installations saved the panel URL. Promote them to the public asset
  # repository while keeping the panel URL as a fallback for this update.
  if [[ "${BINARY_SOURCE_MODE}" != "local" && -n "${BINARY_BASE_URL}" && -z "${BINARY_FALLBACK_BASE_URL}" && "${BINARY_BASE_URL}" == */node/releases ]]; then
    BINARY_FALLBACK_BASE_URL="${BINARY_BASE_URL}"
    BINARY_BASE_URL="${DEFAULT_BINARY_BASE_URL}"
  fi

  if [[ -z "${BINARY_BASE_URL}" ]]; then
    BINARY_BASE_URL="${DEFAULT_BINARY_BASE_URL}"
  fi
  BINARY_BASE_URL="$(normalize_base_url "${BINARY_BASE_URL}")"
  BINARY_FALLBACK_BASE_URL="$(normalize_base_url "${BINARY_FALLBACK_BASE_URL}")"
  write_binary_source
}

write_binary_source() {
  [[ -n "${BINARY_BASE_URL}" ]] || return 0
  mkdir -p "${INSTALL_DIR}"
  {
    printf 'BINARY_BASE_URL=%q\n' "${BINARY_BASE_URL%/}"
    printf 'BINARY_FALLBACK_BASE_URL=%q\n' "${BINARY_FALLBACK_BASE_URL%/}"
    printf 'BINARY_SOURCE_MODE=%q\n' "${BINARY_SOURCE_MODE:-github}"
  } > "${BINARY_SOURCE_FILE}"
  chmod 600 "${BINARY_SOURCE_FILE}"
}

prompt_config() {
  if [[ -z "${SERVER_ADDR}" ]]; then
    read -r -p "Panel/server address: " SERVER_ADDR
  fi
  if [[ -z "${SECRET}" ]]; then
    read -r -p "Node secret: " SECRET
  fi
  [[ -n "${SERVER_ADDR}" && -n "${SECRET}" ]] || die "Address and secret are required."
}

stop_service() {
  systemctl stop "${APP_NAME}" >/dev/null 2>&1 || true
}

disable_service() {
  systemctl disable "${APP_NAME}" >/dev/null 2>&1 || true
}

write_config() {
  mkdir -p "${INSTALL_DIR}"
  cat > "${INSTALL_DIR}/config.json" <<EOF
{
  "addr": "${SERVER_ADDR}",
  "secret": "${SECRET}"
}
EOF
  chmod 600 "${INSTALL_DIR}/config.json"

  if [[ ! -f "${INSTALL_DIR}/gost.json" ]]; then
    printf '{}\n' > "${INSTALL_DIR}/gost.json"
    chmod 600 "${INSTALL_DIR}/gost.json"
  fi
}

download_binary() {
  mkdir -p "${INSTALL_DIR}"
  local primary_url fallback_url tmp_file selected_base
  tmp_file="$(mktemp)"

  primary_url="$(binary_url_for_base "${BINARY_BASE_URL}")"
  fallback_url="$(binary_url_for_base "${BINARY_FALLBACK_BASE_URL}")"

  log "Downloading node binary from: ${primary_url}"
  if download_url_with_ipv4_fallback "${primary_url}" "${tmp_file}"; then
    selected_base="${BINARY_BASE_URL}"
  elif [[ -n "${BINARY_FALLBACK_BASE_URL}" && "${BINARY_FALLBACK_BASE_URL}" != "${BINARY_BASE_URL}" ]]; then
    warn "Primary node asset source failed; trying fallback: ${fallback_url}"
    rm -f "${tmp_file}"
    tmp_file="$(mktemp)"
    download_url_with_ipv4_fallback "${fallback_url}" "${tmp_file}" || {
      rm -f "${tmp_file}"
      die "Download failed from both node asset sources."
    }
    selected_base="${BINARY_FALLBACK_BASE_URL}"
  else
    rm -f "${tmp_file}"
    die "Download failed. Make sure the node asset repository or panel /node/releases/ is reachable."
  fi

  chmod 755 "${tmp_file}"
  verify_binary_checksum "${tmp_file}" "$(binary_name)" "${selected_base}" || {
    rm -f "${tmp_file}"
    die "Checksum verification failed for $(binary_name)."
  }
  verify_binary "${tmp_file}" || {
    rm -f "${tmp_file}"
    die "Downloaded node binary failed validation."
  }
  install -m 755 "${tmp_file}" "${INSTALL_DIR}/${APP_NAME}"
  rm -f "${tmp_file}"
}

verify_binary_checksum() {
  local bin="$1" manifest_name="${2:-$(basename "$1")}" base_url="${3:-${BINARY_BASE_URL}}"
  local manifest_url manifest_file expected actual

  if [[ -n "${GOST_BINARY_URL:-}" ]]; then
    warn "Custom binary URL detected; skipped repository checksum verification."
    return 0
  fi

  if ! command -v sha256sum >/dev/null 2>&1; then
    warn "Cannot find sha256sum; skipped checksum verification."
    return 0
  fi

  [[ -n "${base_url}" ]] || return 1
  manifest_url="${base_url%/}/SHA256SUMS"
  manifest_file="$(mktemp)"
  if ! download_url_with_ipv4_fallback "${manifest_url}" "${manifest_file}"; then
    rm -f "${manifest_file}"
    warn "Checksum manifest not found; skipped checksum verification."
    return 0
  fi

  expected="$(awk -v name="${manifest_name}" '$2 == name || $2 == "*" name {print $1; exit}' "${manifest_file}")"
  rm -f "${manifest_file}"

  if [[ -z "${expected}" ]]; then
    warn "Checksum manifest does not include ${manifest_name}; skipped checksum verification."
    return 0
  fi

  actual="$(sha256sum "${bin}" | awk '{print $1}')"
  [[ "${actual}" == "${expected}" ]] || return 1
  log "Binary checksum verification passed."
}

download_url() {
  local url="$1" destination="$2" force_ipv4="${3:-0}"
  local -a curl_args=(
    --fail
    --silent
    --show-error
    --location
    --retry 3
    --retry-delay 2
    --connect-timeout 10
    --max-time 180
  )
  if [[ "${force_ipv4}" == "1" ]]; then
    curl_args+=(--ipv4)
  fi
  curl "${curl_args[@]}" "${url}" -o "${destination}"
}

download_url_with_ipv4_fallback() {
  local url="$1" destination="$2"
  if download_url "${url}" "${destination}" 0; then
    return 0
  fi

  warn "Default network path failed; retrying over IPv4: ${url}"
  download_url "${url}" "${destination}" 1
}

verify_binary() {
  local bin="$1" size header machine expected_machine

  [[ -s "${bin}" ]] || die "Downloaded binary is empty."
  [[ -x "${bin}" ]] || die "Downloaded binary is not executable."

  size="$(wc -c < "${bin}" | tr -d '[:space:]')"
  if [[ "${size}" -lt 1048576 ]]; then
    die "Downloaded file is too small to be a valid node binary."
  fi

  if ! command -v od >/dev/null 2>&1; then
    warn "Cannot find od; skipped ELF architecture verification."
    return 0
  fi

  header="$(od -An -tx1 -N20 "${bin}" | tr -d '[:space:]')"
  [[ "${header:0:8}" == "7f454c46" ]] || die "Downloaded file is not a Linux ELF binary."

  case "${ARCH}" in
    amd64) expected_machine="3e00" ;;
    arm64) expected_machine="b700" ;;
    armv6|armv7) expected_machine="2800" ;;
    *) expected_machine="" ;;
  esac

  machine="${header:36:4}"
  if [[ -n "${expected_machine}" && "${machine}" != "${expected_machine}" ]]; then
    die "Downloaded binary architecture mismatch. Expected ${ARCH}, got ELF machine 0x${machine}."
  fi

  log "Binary verification passed."
}

setup_logging() {
  mkdir -p "${LOG_DIR}"
  touch "${LOG_FILE}"
  chmod 755 "${LOG_DIR}"
  chmod 640 "${LOG_FILE}"

  cat > "${LOGROTATE_FILE}" <<EOF
${LOG_FILE} {
    size 50M
    rotate 0
    missingok
    notifempty
    copytruncate
}
EOF
}

write_service() {
  cat > "${SERVICE_FILE}" <<EOF
[Unit]
Description=Flux Panel Gost Node
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=${INSTALL_DIR}
ExecStart=${INSTALL_DIR}/${APP_NAME}
Restart=on-failure
RestartSec=5
StandardOutput=append:${LOG_FILE}
StandardError=append:${LOG_FILE}

[Install]
WantedBy=multi-user.target
EOF
}

refresh_service() {
  systemctl daemon-reload
  systemctl enable --now "${APP_NAME}"
}

ensure_service_running() {
  sleep 2
  if systemctl is-active --quiet "${APP_NAME}"; then
    log "Service is running."
    return 0
  fi

  err "Service failed to start. Showing diagnostics:"
  systemctl --no-pager --full status "${APP_NAME}" || true
  if [[ -f "${LOG_FILE}" ]]; then
    tail -n 80 "${LOG_FILE}" || true
  fi
  exit 1
}

install_flow() {
  detect_os
  install_packages
  ensure_swap
  prompt_config
  resolve_binary_source

  if systemctl list-unit-files --type=service | grep -Fq "${APP_NAME}.service"; then
    stop_service
    disable_service
  fi

  setup_logging
  write_config
  download_binary
  write_service
  refresh_service
  ensure_service_running

  log "Install complete."
}

update_flow() {
  detect_os
  install_packages
  ensure_swap

  [[ -d "${INSTALL_DIR}" ]] || die "The node is not installed."
  [[ -x "${INSTALL_DIR}/${APP_NAME}" ]] || die "Binary not found in ${INSTALL_DIR}."

  if [[ -n "${SERVER_ADDR}" || -n "${SECRET}" ]]; then
    prompt_config
    write_config
  elif [[ ! -f "${INSTALL_DIR}/config.json" ]]; then
    prompt_config
    write_config
  fi

  resolve_binary_source

  setup_logging
  stop_service
  download_binary
  write_service
  refresh_service
  ensure_service_running

  log "Update complete."
}

uninstall_flow() {
  detect_os
  install_packages

  if ! confirm "Remove the node, its config, logs, and service file?"; then
    log "Cancelled."
    return 0
  fi

  stop_service
  disable_service
  rm -f "${SERVICE_FILE}"
  rm -f "${LOGROTATE_FILE}"
  rm -rf "${INSTALL_DIR}"
  rm -rf "${LOG_DIR}"
  systemctl daemon-reload

  log "Uninstall complete."
}

status_flow() {
  detect_os
  if [[ -f "${SERVICE_FILE}" ]]; then
    systemctl --no-pager --full status "${APP_NAME}" || true
  else
    warn "Service file not found."
  fi

  if [[ -r /proc/swaps ]]; then
    printf '\nActive swap:\n'
    cat /proc/swaps
  fi
}

logs_flow() {
  detect_os
  if [[ -f "${LOG_FILE}" ]]; then
    tail -n 200 -F "${LOG_FILE}"
  else
    journalctl -u "${APP_NAME}" -f --no-pager
  fi
}

swap_flow() {
  detect_os
  install_packages
  ensure_swap

  printf '\nActive swap:\n'
  cat /proc/swaps 2>/dev/null || true
}

show_menu() {
  cat <<EOF
===============================================
              ${APP_NAME} manager
===============================================
1. Install / reinstall
2. Update
3. Uninstall
4. Status
5. Logs
6. Configure / check swap
0. Exit
===============================================
EOF
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      install|update|uninstall|status|logs|swap|menu)
        ACTION="$1"
        shift
        ;;
      -a|--addr)
        [[ $# -ge 2 ]] || die "$1 requires a value."
        SERVER_ADDR="${2:-}"
        shift 2
        ;;
      -s|--secret)
        [[ $# -ge 2 ]] || die "$1 requires a value."
        SECRET="${2:-}"
        shift 2
        ;;
      -b|--binary-base-url)
        [[ $# -ge 2 ]] || die "$1 requires a value."
        BINARY_BASE_URL="${2:-}"
        shift 2
        ;;
      -f|--fallback-binary-base-url)
        [[ $# -ge 2 ]] || die "$1 requires a value."
        BINARY_FALLBACK_BASE_URL="${2:-}"
        shift 2
        ;;
      -m|--source-mode)
        [[ $# -ge 2 ]] || die "$1 requires a value."
        BINARY_SOURCE_MODE="${2:-}"
        shift 2
        ;;
      -y|--yes)
        ASSUME_YES=1
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        die "Unknown argument: $1"
        ;;
    esac
  done

  if [[ -z "${ACTION}" && ( -n "${SERVER_ADDR}" || -n "${SECRET}" ) ]]; then
    ACTION="install"
  fi

  ACTION="${ACTION:-menu}"
}

main() {
  ensure_root "$@"
  parse_args "$@"

  case "${ACTION}" in
    install) install_flow ;;
    update) update_flow ;;
    uninstall) uninstall_flow ;;
    status) status_flow ;;
    logs) logs_flow ;;
    swap) swap_flow ;;
    menu)
      while true; do
        show_menu
        read -r -p "Choose [0-6]: " choice
        case "${choice}" in
          1) install_flow; break ;;
          2) update_flow; break ;;
          3) uninstall_flow; break ;;
          4) status_flow ;;
          5) logs_flow ;;
          6) swap_flow ;;
          0) exit 0 ;;
          *) echo "Invalid choice." ;;
        esac
      done
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
