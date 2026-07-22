#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

APP_NAME="gost"
INSTALL_DIR="/etc/gost"
LOG_DIR="/var/log/gost"
LOG_FILE="${LOG_DIR}/gost.log"
SERVICE_FILE="/etc/systemd/system/gost.service"
LOGROTATE_FILE="/etc/logrotate.d/gost"
REPO_OWNER="w243420707"
REPO_NAME="flux-panel_rev"
RAW_BINARY_BASE_URL="${GOST_BINARY_BASE_URL:-https://raw.githubusercontent.com/${REPO_OWNER}/${REPO_NAME}/refs/heads/main/go-gost/releases}"

ACTION=""
SERVER_ADDR=""
SECRET=""
ASSUME_YES=0
ARCH=""
PKG_MANAGER=""

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
  menu        Interactive menu (default)

Options:
  -a, --addr ADDR    Panel/server address
  -s, --secret KEY   Node secret
  -y, --yes         Non-interactive yes for confirmations
  -h, --help        Show this help

Binary source:
  ${RAW_BINARY_BASE_URL}/gost-linux-\${ARCH}
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

  if [[ -f /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    log "Detected OS: ${PRETTY_NAME:-$ID}"
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

  case "$(uname -m)" in
    x86_64|amd64) ARCH="amd64" ;;
    aarch64|arm64) ARCH="arm64" ;;
    armv7l|armv7) ARCH="armv7" ;;
    armv6l|armv6) ARCH="armv6" ;;
    *)
      die "Unsupported architecture: $(uname -m)"
      ;;
  esac

  log "Detected architecture: ${ARCH}"
}

install_packages() {
  if command -v curl >/dev/null 2>&1 && command -v logrotate >/dev/null 2>&1 && command -v systemctl >/dev/null 2>&1; then
    return 0
  fi

  log "Installing required packages..."
  case "${PKG_MANAGER}" in
    apt-get)
      apt-get update
      apt-get install -y curl ca-certificates logrotate
      ;;
    dnf)
      dnf install -y curl ca-certificates logrotate
      ;;
    yum)
      yum install -y curl ca-certificates logrotate
      ;;
    apk)
      apk add --no-cache curl ca-certificates logrotate
      ;;
    pacman)
      pacman -Sy --noconfirm curl ca-certificates logrotate
      ;;
    zypper)
      zypper --non-interactive install curl ca-certificates logrotate
      ;;
  esac

  command -v systemctl >/dev/null 2>&1 || die "systemd is required for this installer."
}

binary_name() {
  printf 'gost-linux-%s' "${ARCH}"
}

binary_url() {
  if [[ -n "${GOST_BINARY_URL:-}" ]]; then
    printf '%s' "${GOST_BINARY_URL}"
    return 0
  fi
  printf '%s/%s' "${RAW_BINARY_BASE_URL}" "$(binary_name)"
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
  local url tmp_file
  url="$(binary_url)"
  tmp_file="$(mktemp)"

  log "Downloading node binary from: ${url}"
  curl -fsSL --retry 3 --retry-delay 2 "${url}" -o "${tmp_file}" || {
    rm -f "${tmp_file}"
    die "Download failed. Make sure the repository contains $(binary_name) under go-gost/releases/."
  }

  install -m 755 "${tmp_file}" "${INSTALL_DIR}/${APP_NAME}"
  rm -f "${tmp_file}"
  "${INSTALL_DIR}/${APP_NAME}" -V >/dev/null 2>&1 || die "Downloaded binary failed verification."
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

install_flow() {
  detect_os
  install_packages
  prompt_config

  if systemctl list-unit-files --type=service | grep -Fq "${APP_NAME}.service"; then
    stop_service
    disable_service
  fi

  setup_logging
  download_binary
  write_config
  write_service
  refresh_service

  log "Install complete."
  systemctl is-active --quiet "${APP_NAME}" && log "Service is running."
}

update_flow() {
  detect_os
  install_packages

  [[ -d "${INSTALL_DIR}" ]] || die "The node is not installed."
  [[ -x "${INSTALL_DIR}/${APP_NAME}" ]] || die "Binary not found in ${INSTALL_DIR}."

  if [[ -n "${SERVER_ADDR}" || -n "${SECRET}" ]]; then
    prompt_config
    write_config
  elif [[ ! -f "${INSTALL_DIR}/config.json" ]]; then
    prompt_config
    write_config
  fi

  setup_logging
  stop_service
  download_binary
  write_service
  refresh_service

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
}

logs_flow() {
  detect_os
  if [[ -f "${LOG_FILE}" ]]; then
    tail -n 200 -F "${LOG_FILE}"
  else
    journalctl -u "${APP_NAME}" -f --no-pager
  fi
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
0. Exit
===============================================
EOF
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      install|update|uninstall|status|logs|menu)
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
    menu)
      while true; do
        show_menu
        read -r -p "Choose [0-5]: " choice
        case "${choice}" in
          1) install_flow; break ;;
          2) update_flow; break ;;
          3) uninstall_flow; break ;;
          4) status_flow ;;
          5) logs_flow ;;
          0) exit 0 ;;
          *) echo "Invalid choice." ;;
        esac
      done
      ;;
  esac
}

main "$@"
