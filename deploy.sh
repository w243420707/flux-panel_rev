#!/usr/bin/env bash
set -Eeuo pipefail

APP_NAME="flux-panel_rev"
APP_SLUG="flux-panel-rev"
REPO_URL="${REPO_URL:-${PANEL_UPDATE_REPOSITORY:-https://github.com/w243420707/flux-panel_rev.git}}"
DEPLOY_REF="${DEPLOY_REF:-${BRANCH:-main}}"
APP_DIR="${APP_DIR:-/opt/${APP_NAME}}"
COMPOSE_FILE="docker-compose.deploy.yml"
ENV_FILE="${APP_DIR}/.env"
UPDATE_STATE_DIR="${UPDATE_STATE_DIR:-${APP_DIR}/update-state}"
RAW_DEPLOY_URL="${RAW_DEPLOY_URL:-https://raw.githubusercontent.com/w243420707/flux-panel_rev/refs/heads/main/deploy.sh}"
SELF_HEAL_FAILURE_THRESHOLD="${SELF_HEAL_FAILURE_THRESHOLD:-3}"
SELF_HEAL_REBOOT_COOLDOWN="${SELF_HEAL_REBOOT_COOLDOWN:-1800}"

ACTION=""
DOMAIN=""
EMAIL=""
FRONTEND_PORT=""
BACKEND_PORT=""
PHPMYADMIN_PORT=""
DEPLOY_REF_ARG=""
ASSUME_YES=0
SKIP_SSL=0
WITH_PHPMYADMIN=0
PURGE_DATA=0

OS_ID="unknown"
OS_VERSION="unknown"
OS_CODENAME="unknown"
OS_PRETTY="unknown"
PKG_MANAGER=""
ARCH_RAW="$(uname -m)"
ARCH="unknown"
COMPOSE_ARCH="unknown"
MYSQL_IMAGE="mysql:5.7"
PREVIOUS_COMMIT=""
CURRENT_COMMIT=""
BUILD_SERVICES=()
DEPLOY_LOCK_FD=""
SELF_HEAL_STATE_FILE=""
SELF_HEAL_FAILURES=0
SELF_HEAL_LAST_REBOOT=0
SELF_HEAL_LAST_FAILURE=""
DOCKER_IPV4_HOSTS_TOKEN="flux-panel-rev-docker-ipv4-fallback"
DOCKER_IPV4_HOSTS_ACTIVE=0
GITHUB_IPV4_HOSTS_TOKEN="flux-panel-rev-github-ipv4-fallback"
GITHUB_IPV4_HOSTS_ACTIVE=0
NGINX_IPV6_ENABLED=0

log() { printf '\033[1;32m[INFO]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[WARN]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[ERR ]\033[0m %s\n' "$*" >&2; }
die() { err "$*"; exit 1; }

cleanup_docker_ipv4_fallback() {
  if [[ "${DOCKER_IPV4_HOSTS_ACTIVE}" -eq 1 && -f /etc/hosts ]]; then
    sed -i "/${DOCKER_IPV4_HOSTS_TOKEN}/d" /etc/hosts 2>/dev/null || true
    DOCKER_IPV4_HOSTS_ACTIVE=0
    log "Removed temporary Docker IPv4 fallback entries."
  fi
}

cleanup_github_ipv4_fallback() {
  if [[ "${GITHUB_IPV4_HOSTS_ACTIVE}" -eq 1 && -f /etc/hosts ]]; then
    sed -i "/${GITHUB_IPV4_HOSTS_TOKEN}/d" /etc/hosts 2>/dev/null || true
    GITHUB_IPV4_HOSTS_ACTIVE=0
    log "Removed temporary GitHub IPv4 fallback entries."
  fi
}

cleanup_ipv4_fallbacks() {
  cleanup_docker_ipv4_fallback
  cleanup_github_ipv4_fallback
}

trap cleanup_ipv4_fallbacks EXIT

usage() {
  cat <<EOF
${APP_NAME} VPS deploy script

Usage:
  sudo bash deploy.sh [command] [options]

Commands:
  install       Install or reinstall the panel
  update        Pull latest code, rebuild images, restart services
  auto-update   Process a backend-written update request
  self-heal     Check and repair the running panel stack
  uninstall     Stop and remove services
  status        Show service status
  logs          Follow service logs
  cleanup       Clean unused Docker resources and build cache
  renew-cert    Renew Let's Encrypt certificate and reload Nginx
  menu          Interactive menu (default)

Options:
  --domain DOMAIN        Panel domain, for example panel.example.com
  --email EMAIL          Let's Encrypt email
  --frontend-port PORT   Local frontend port, default 6366
  --backend-port PORT    Local backend port, default 6365
  --phpmyadmin-port PORT Local phpMyAdmin port, default 8066
  --ref REF              Git branch, tag, or commit to deploy, default main
  --with-phpmyadmin      Enable phpMyAdmin container profile
  --skip-ssl             Configure Nginx HTTP only, no certificate request
  --purge                With uninstall, remove database volumes and app dir
  -y, --yes              Non-interactive yes for confirmations
  -h, --help             Show this help

One-line install:
  curl -L ${RAW_DEPLOY_URL} -o deploy.sh && chmod +x deploy.sh && sudo ./deploy.sh install
EOF
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      install|update|auto-update|self-heal|uninstall|status|logs|cleanup|renew-cert|menu)
        ACTION="$1"
        ;;
      --domain)
        DOMAIN="${2:-}"
        shift
        ;;
      --email)
        EMAIL="${2:-}"
        shift
        ;;
      --frontend-port)
        FRONTEND_PORT="${2:-}"
        shift
        ;;
      --backend-port)
        BACKEND_PORT="${2:-}"
        shift
        ;;
      --phpmyadmin-port)
        PHPMYADMIN_PORT="${2:-}"
        shift
        ;;
      --ref|--branch)
        DEPLOY_REF="${2:-}"
        DEPLOY_REF_ARG="${DEPLOY_REF}"
        shift
        ;;
      --with-phpmyadmin)
        WITH_PHPMYADMIN=1
        ;;
      --skip-ssl)
        SKIP_SSL=1
        ;;
      --purge)
        PURGE_DATA=1
        ;;
      -y|--yes)
        ASSUME_YES=1
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        die "Unknown argument: $1"
        ;;
    esac
    shift
  done
  [[ -n "${DEPLOY_REF}" ]] || die "--ref requires a non-empty value."
  ACTION="${ACTION:-menu}"
}

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    if command -v sudo >/dev/null 2>&1; then
      exec sudo -E bash "$0" "$@"
    fi
    die "Please run this script as root."
  fi
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

prompt_value() {
  local var_name="$1"
  local prompt="$2"
  local default_value="$3"
  local current_value="${!var_name:-}"

  if [[ -n "${current_value}" ]]; then
    return 0
  fi
  if [[ "${ASSUME_YES}" -eq 1 ]]; then
    printf -v "${var_name}" '%s' "${default_value}"
    return 0
  fi

  read -r -p "${prompt} [${default_value}]: " input
  printf -v "${var_name}" '%s' "${input:-$default_value}"
}

sanitize_domain() {
  local value="$1"
  value="${value#http://}"
  value="${value#https://}"
  value="${value%%/*}"
  value="${value%%:*}"
  printf '%s' "${value}"
}

detect_system() {
  if [[ -f /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    OS_ID="${ID:-unknown}"
    OS_VERSION="${VERSION_ID:-unknown}"
    OS_CODENAME="${VERSION_CODENAME:-${UBUNTU_CODENAME:-unknown}}"
    OS_PRETTY="${PRETTY_NAME:-$OS_ID $OS_VERSION}"
  fi

  if command -v apt-get >/dev/null 2>&1; then
    PKG_MANAGER="apt"
  elif command -v dnf >/dev/null 2>&1; then
    PKG_MANAGER="dnf"
  elif command -v yum >/dev/null 2>&1; then
    PKG_MANAGER="yum"
  elif command -v apk >/dev/null 2>&1; then
    PKG_MANAGER="apk"
  else
    die "Unsupported system: no apt, dnf, yum, or apk found."
  fi

  case "${ARCH_RAW}" in
    x86_64|amd64)
      ARCH="amd64"
      COMPOSE_ARCH="x86_64"
      MYSQL_IMAGE="mysql:5.7"
      ;;
    aarch64|arm64)
      ARCH="arm64"
      COMPOSE_ARCH="aarch64"
      MYSQL_IMAGE="mysql:8.0"
      ;;
    armv7l|armv7)
      ARCH="armv7"
      COMPOSE_ARCH="armv7"
      MYSQL_IMAGE="mysql:8.0"
      ;;
    *)
      die "Unsupported CPU architecture: ${ARCH_RAW}"
      ;;
  esac

  log "System: ${OS_PRETTY}"
  log "Package manager: ${PKG_MANAGER}"
  log "Architecture: ${ARCH_RAW} (${ARCH})"

  if ! command -v systemctl >/dev/null 2>&1; then
    die "systemd is required for automatic startup and service management."
  fi
}

install_packages() {
  log "Installing base packages..."
  case "${PKG_MANAGER}" in
    apt)
      export DEBIAN_FRONTEND=noninteractive
      apt-get update
      apt-get install -y ca-certificates curl gnupg lsb-release git nginx certbot python3-certbot-nginx openssl dnsutils iproute2 util-linux
      ;;
    dnf)
      dnf install -y ca-certificates curl gnupg2 git nginx certbot python3-certbot-nginx openssl bind-utils iproute util-linux
      ;;
    yum)
      yum install -y ca-certificates curl gnupg2 git nginx certbot python3-certbot-nginx openssl bind-utils iproute util-linux
      ;;
    apk)
      apk update
      apk add --no-cache ca-certificates curl git nginx certbot certbot-nginx openssl bind-tools iproute2 util-linux docker docker-cli-compose
      ;;
  esac
}

install_docker_from_official_apt_repo() {
  [[ "${PKG_MANAGER}" == "apt" ]] || die "Official Docker apt fallback called on a non-apt system."
  [[ "${OS_ID}" == "ubuntu" || "${OS_ID}" == "debian" ]] || die "Unsupported apt distribution for the official Docker fallback: ${OS_ID}."
  [[ "${OS_CODENAME}" != "unknown" ]] || die "Could not determine the ${OS_ID} release codename for the official Docker repository."

  local docker_distribution="${OS_ID}"
  local docker_arch
  docker_arch="$(dpkg --print-architecture)"

  log "Installing Docker from the official ${docker_distribution} repository without the unavailable docker-model-plugin..."
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL "https://download.docker.com/linux/${docker_distribution}/gpg" -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/%s %s stable\n' \
    "${docker_arch}" "${docker_distribution}" "${OS_CODENAME}" > /etc/apt/sources.list.d/docker.list

  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
}

install_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    if [[ "${PKG_MANAGER}" == "apt" && "${OS_ID}" == "ubuntu" && "${OS_VERSION}" == "20.04" ]]; then
      warn "Ubuntu 20.04 is not compatible with the current get.docker.com package list; using the official Docker repository fallback."
      install_docker_from_official_apt_repo
    else
      log "Docker not found. Installing Docker with the official installer..."
      if ! curl -fsSL https://get.docker.com -o /tmp/get-docker.sh || ! sh /tmp/get-docker.sh; then
        warn "The official Docker installer failed; retrying with the official package repository without docker-model-plugin."
        case "${PKG_MANAGER}" in
          apt)
            install_docker_from_official_apt_repo
            ;;
          dnf|yum)
            ${PKG_MANAGER} install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
            ;;
          apk)
            apk add --no-cache docker docker-cli-compose
            ;;
          *)
            die "Docker installation failed on ${PKG_MANAGER}."
            ;;
        esac
      fi
    fi
  fi

  command -v docker >/dev/null 2>&1 || die "Docker installation failed: docker command is unavailable."

  systemctl enable --now docker

  if ! docker compose version >/dev/null 2>&1; then
    log "Docker Compose plugin not found. Installing it..."
    case "${PKG_MANAGER}" in
      apt)
        apt-get update
        apt-get install -y docker-compose-plugin || true
        ;;
      dnf)
        dnf install -y docker-compose-plugin || true
        ;;
      yum)
        yum install -y docker-compose-plugin || true
        ;;
      apk)
        apk add --no-cache docker-cli-compose || true
        ;;
    esac
  fi

  if ! docker compose version >/dev/null 2>&1; then
    local release
    configure_github_ipv4_fallback || true
    release="$(curl -fsSL https://api.github.com/repos/docker/compose/releases/latest | sed -n 's/.*"tag_name": "\(v[^"]*\)".*/\1/p' | head -n 1 || true)"
    release="${release:-v2.29.7}"
    mkdir -p /usr/local/lib/docker/cli-plugins
    curl -fsSL -o /usr/local/lib/docker/cli-plugins/docker-compose \
      "https://github.com/docker/compose/releases/download/${release}/docker-compose-linux-${COMPOSE_ARCH}"
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
  fi

  docker compose version >/dev/null 2>&1 || die "Docker Compose is still unavailable after installation."
  configure_docker_ipv4_fallback
}

docker_endpoint_reachable() {
  local family="$1"
  local host="$2"
  curl -sS "-${family}" --connect-timeout 4 --max-time 8 -o /dev/null "https://${host}/" >/dev/null 2>&1
}

resolve_ipv4() {
  local host="$1"
  local address=""

  if command -v dig >/dev/null 2>&1; then
    address="$(dig +short A "${host}" 2>/dev/null | awk '/^[0-9.]+$/ { print; exit }')"
  fi
  if [[ -z "${address}" ]] && command -v getent >/dev/null 2>&1; then
    address="$(getent ahostsv4 "${host}" 2>/dev/null | awk '$1 ~ /^[0-9.]+$/ { print $1; exit }')"
  fi
  if [[ -z "${address}" ]] && command -v dig >/dev/null 2>&1; then
    # Some VPS providers have a broken or filtered local resolver. Use public
    # DNS only to discover the current IPv4 address; no mirror is involved.
    local resolver
    for resolver in 1.1.1.1 8.8.8.8; do
      address="$(dig @"${resolver}" +time=2 +tries=1 +short A "${host}" 2>/dev/null | awk '/^[0-9.]+$/ { print; exit }')"
      [[ -n "${address}" ]] && break
    done
  fi
  printf '%s' "${address}"
}

github_endpoint_reachable() {
  local family="$1"
  local host="$2"
  curl -fsS "-${family}" --connect-timeout 4 --max-time 8 -o /dev/null "https://${host}/" >/dev/null 2>&1
}

github_ipv4_fallback_needed() {
  local address=""
  local ipv4_ok=0
  local ipv6_failed=0

  github_endpoint_reachable 4 "github.com" && ipv4_ok=1 || true
  github_endpoint_reachable 6 "github.com" || ipv6_failed=1

  if [[ "${ipv4_ok}" -eq 1 && "${ipv6_failed}" -eq 0 ]]; then
    return 1
  fi

  address="$(resolve_ipv4 "github.com")"
  [[ -n "${address}" ]] || return 1

  # Verify that the discovered address really serves GitHub before changing
  # /etc/hosts. This avoids masking a broader network outage.
  curl -fsS -4 --resolve "github.com:443:${address}" \
    --connect-timeout 4 --max-time 8 -o /dev/null "https://github.com/" >/dev/null 2>&1
}

configure_github_ipv4_fallback() {
  [[ "${GITHUB_IPV4_HOSTS_ACTIVE}" -eq 1 ]] && return 0
  command -v curl >/dev/null 2>&1 || return 0
  [[ -f /etc/hosts ]] || return 0

  if ! github_ipv4_fallback_needed; then
    return 0
  fi

  local host address
  local github_hosts=(
    "github.com"
    "api.github.com"
    "raw.githubusercontent.com"
    "codeload.github.com"
    "objects.githubusercontent.com"
  )

  # Remove a stale entry left by an interrupted previous run before adding a
  # fresh set of addresses.
  sed -i "/${GITHUB_IPV4_HOSTS_TOKEN}/d" /etc/hosts 2>/dev/null || true
  warn "GitHub DNS is unavailable on this VPS; temporarily preferring public IPv4 addresses."
  warn "This uses GitHub's official endpoints only and is removed when the script exits."

  for host in "${github_hosts[@]}"; do
    address="$(resolve_ipv4 "${host}")"
    if [[ -n "${address}" ]]; then
      printf '%s %s %s\n' "${address}" "${host}" "${GITHUB_IPV4_HOSTS_TOKEN}" >> /etc/hosts
      log "GitHub IPv4 fallback: ${host} -> ${address}"
      GITHUB_IPV4_HOSTS_ACTIVE=1
    else
      warn "Could not resolve an IPv4 address for ${host}; leaving normal DNS resolution unchanged."
    fi
  done

  if [[ "${GITHUB_IPV4_HOSTS_ACTIVE}" -eq 0 ]]; then
    warn "GitHub IPv4 fallback could not be configured."
  fi
}

docker_ipv4_fallback_needed() {
  local host
  local ipv4_ok=0
  local ipv6_failed=0
  local docker_hosts=(
    "registry-1.docker.io"
    "auth.docker.io"
    "production.cloudflare.docker.com"
    "production.cloudfront.docker.com"
  )

  for host in "${docker_hosts[@]}"; do
    docker_endpoint_reachable 4 "${host}" && ipv4_ok=1 || true
    docker_endpoint_reachable 6 "${host}" || ipv6_failed=1
  done

  [[ "${ipv4_ok}" -eq 1 && "${ipv6_failed}" -eq 1 ]]
}

configure_docker_ipv4_fallback() {
  [[ "${DOCKER_IPV4_HOSTS_ACTIVE}" -eq 1 ]] && return 0
  command -v curl >/dev/null 2>&1 || return 0
  [[ -f /etc/hosts ]] || return 0

  if ! docker_ipv4_fallback_needed; then
    return 0
  fi

  local host address
  local docker_hosts=(
    "registry-1.docker.io"
    "auth.docker.io"
    "production.cloudflare.docker.com"
    "production.cloudfront.docker.com"
  )

  warn "VPS IPv6 route is unavailable but Docker registry IPv4 is reachable."
  warn "Temporarily preferring IPv4 for Docker registry downloads; no domestic mirror will be used."

  for host in "${docker_hosts[@]}"; do
    address="$(resolve_ipv4 "${host}")"
    if [[ -n "${address}" ]]; then
      printf '%s %s %s\n' "${address}" "${host}" "${DOCKER_IPV4_HOSTS_TOKEN}" >> /etc/hosts
      log "Docker IPv4 fallback: ${host} -> ${address}"
      DOCKER_IPV4_HOSTS_ACTIVE=1
    else
      warn "Could not resolve an IPv4 address for ${host}; leaving normal DNS resolution unchanged."
    fi
  done

  if [[ "${DOCKER_IPV4_HOSTS_ACTIVE}" -eq 0 ]]; then
    warn "Docker IPv4 fallback could not be configured."
  fi
}

configure_firewall() {
  if command -v ufw >/dev/null 2>&1; then
    ufw allow OpenSSH >/dev/null 2>&1 || true
    ufw allow 'Nginx Full' >/dev/null 2>&1 || true
  fi

  if systemctl is-active --quiet firewalld 2>/dev/null && command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --permanent --add-service=http >/dev/null 2>&1 || true
    firewall-cmd --permanent --add-service=https >/dev/null 2>&1 || true
    firewall-cmd --reload >/dev/null 2>&1 || true
  fi
}

random_string() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 16
  else
    date +%s%N | sha256sum | cut -c1-32
  fi
}

port_in_use() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -H -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]${port}$" && return 0
  elif command -v netstat >/dev/null 2>&1; then
    netstat -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]${port}$" && return 0
  fi

  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    docker ps --format '{{.Ports}}' 2>/dev/null | grep -Eq "(:|\\[::\\]:|0\\.0\\.0\\.0:)${port}->" && return 0
  fi

  return 1
}

own_container_uses_port() {
  local port="$1"
  local container ports
  for container in "${APP_SLUG}-frontend" "${APP_SLUG}-backend" "${APP_SLUG}-phpmyadmin"; do
    ports="$(docker inspect -f '{{range $port, $bindings := .NetworkSettings.Ports}}{{range $bindings}}{{.HostIp}}:{{.HostPort}} {{end}}{{end}}' "${container}" 2>/dev/null || true)"
    if printf '%s\n' "${ports}" | tr ' ' '\n' | grep -Eq "[:.]${port}$"; then
      return 0
    fi
  done
  return 1
}

port_in_use_by_other() {
  local port="$1"
  port_in_use "${port}" || return 1
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 && own_container_uses_port "${port}"; then
    return 1
  fi
  return 0
}

pick_port() {
  local port="$1"
  while port_in_use "${port}"; do
    port=$((port + 1))
  done
  printf '%s' "${port}"
}

ensure_runtime_port() {
  local var_name="$1"
  local label="$2"
  local current="${!var_name:-}"
  local next

  [[ -n "${current}" ]] || return 0
  next="${current}"
  while port_in_use_by_other "${next}"; do
    next=$((next + 1))
  done

  if [[ "${next}" != "${current}" ]]; then
    warn "${label} port ${current} is already used by another process/container. Using ${next} instead."
    printf -v "${var_name}" '%s' "${next}"
    set_env_value "${var_name}" "${next}"
  fi
}

ensure_runtime_ports() {
  load_env
  ensure_runtime_port FRONTEND_PORT "Frontend"
  ensure_runtime_port BACKEND_PORT "Backend"
  if [[ "${COMPOSE_PROFILES:-}" == *tools* ]]; then
    ensure_runtime_port PHPMYADMIN_PORT "phpMyAdmin"
  fi
}

load_env() {
  if [[ -f "${ENV_FILE}" ]]; then
    set -a
    # shellcheck disable=SC1090
    . "${ENV_FILE}"
    set +a
  fi
}

set_env_value() {
  local key="$1"
  local value="$2"
  local tmp
  tmp="$(mktemp)" || return $?
  if [[ -f "${ENV_FILE}" ]]; then
    grep -v "^${key}=" "${ENV_FILE}" > "${tmp}" || true
  fi
  printf '%s=%s\n' "${key}" "${value}" >> "${tmp}" || return $?
  mv "${tmp}" "${ENV_FILE}" || return $?
  chmod 600 "${ENV_FILE}"
}

sync_build_env_values() {
  load_env || return $?
  local release_version
  release_version="$(sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([0-9][0-9.]*\)".*/\1/p' "${APP_DIR}/vite-frontend/package.json" | head -n 1)"
  if [[ "${release_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    PANEL_VERSION="${release_version}"
    set_env_value PANEL_VERSION "${PANEL_VERSION}" || return $?
  fi
  PANEL_COMMIT="${CURRENT_COMMIT:-$(git -C "${APP_DIR}" rev-parse HEAD 2>/dev/null || true)}"
  [[ "${PANEL_COMMIT}" =~ ^[0-9a-fA-F]{40}$ ]] || { err "Cannot determine the deployed commit."; return 1; }
  set_env_value PANEL_COMMIT "${PANEL_COMMIT}" || return $?

  if [[ -z "${AWS_ENCRYPTION_KEY:-}" ]]; then
    AWS_ENCRYPTION_KEY="$(random_string)"
    set_env_value AWS_ENCRYPTION_KEY "${AWS_ENCRYPTION_KEY}" || return $?
  fi

  local frontend_dockerfile="Dockerfile"
  if [[ -f "${APP_DIR}/vite-frontend/Dockerfile.prebuilt" && -f "${APP_DIR}/vite-frontend/prebuilt-dist/index.html" ]]; then
    frontend_dockerfile="Dockerfile.prebuilt"
  fi
  FRONTEND_DOCKERFILE="${frontend_dockerfile}"
  set_env_value FRONTEND_DOCKERFILE "${FRONTEND_DOCKERFILE}" || return $?

  # Migrate old mirror defaults back to official/global sources for overseas VPS.
  if [[ "${NPM_REGISTRY:-}" == "https://registry.npmmirror.com" ]]; then
    NPM_REGISTRY="https://registry.npmjs.org"
    set_env_value NPM_REGISTRY "${NPM_REGISTRY}" || return $?
  fi
  if [[ "${NPM_FALLBACK_REGISTRY:-}" == "https://registry.npmmirror.com" || "${NPM_FALLBACK_REGISTRY:-}" == "https://registry.npmjs.org" ]]; then
    NPM_FALLBACK_REGISTRY="https://registry.yarnpkg.com"
    set_env_value NPM_FALLBACK_REGISTRY "${NPM_FALLBACK_REGISTRY}" || return $?
  fi
  if [[ "${MAVEN_MIRROR_URL:-}" == "https://maven.aliyun.com/repository/public" ]]; then
    MAVEN_MIRROR_URL=""
    set_env_value MAVEN_MIRROR_URL "${MAVEN_MIRROR_URL}" || return $?
  fi
}

write_env_file() {
  local release_version
  mkdir -p "${APP_DIR}"
  load_env
  [[ -n "${DEPLOY_REF_ARG}" ]] && DEPLOY_REF="${DEPLOY_REF_ARG}"

  DB_NAME="${DB_NAME:-gost}"
  DB_USER="${DB_USER:-$(random_string)}"
  DB_PASSWORD="${DB_PASSWORD:-$(random_string)}"
  JWT_SECRET="${JWT_SECRET:-$(random_string)}"
  OCI_ENCRYPTION_KEY="${OCI_ENCRYPTION_KEY:-$(random_string)}"
  AWS_ENCRYPTION_KEY="${AWS_ENCRYPTION_KEY:-$(random_string)}"
  FRONTEND_NODE_VERSION="${FRONTEND_NODE_VERSION:-22.13.1}"
  FRONTEND_BUILD_MAX_OLD_SPACE_SIZE="${FRONTEND_BUILD_MAX_OLD_SPACE_SIZE:-1536}"
  VITE_LEGACY_BUILD="${VITE_LEGACY_BUILD:-false}"
  PNPM_VERSION="${PNPM_VERSION:-11.7.0}"
  # Migrate old mirror defaults back to official/global sources for overseas VPS.
  NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org}"
  if [[ "${NPM_REGISTRY}" == "https://registry.npmmirror.com" ]]; then
    NPM_REGISTRY="https://registry.npmjs.org"
  fi
  NPM_FALLBACK_REGISTRY="${NPM_FALLBACK_REGISTRY:-https://registry.yarnpkg.com}"
  if [[ "${NPM_FALLBACK_REGISTRY}" == "https://registry.npmmirror.com" || "${NPM_FALLBACK_REGISTRY}" == "https://registry.npmjs.org" ]]; then
    NPM_FALLBACK_REGISTRY="https://registry.yarnpkg.com"
  fi
  MAVEN_MIRROR_URL="${MAVEN_MIRROR_URL:-}"
  if [[ "${MAVEN_MIRROR_URL}" == "https://maven.aliyun.com/repository/public" ]]; then
    MAVEN_MIRROR_URL=""
  fi
  FRONTEND_DOCKERFILE="Dockerfile"
  if [[ -f "${APP_DIR}/vite-frontend/Dockerfile.prebuilt" && -f "${APP_DIR}/vite-frontend/prebuilt-dist/index.html" ]]; then
    FRONTEND_DOCKERFILE="Dockerfile.prebuilt"
  fi
  DOCKER_LOG_MAX_SIZE="${DOCKER_LOG_MAX_SIZE:-50m}"
  DOCKER_LOG_MAX_FILE="${DOCKER_LOG_MAX_FILE:-1}"
  DOCKER_BUILD_CACHE_KEEP="${DOCKER_BUILD_CACHE_KEEP:-512MB}"
  UPDATE_STATE_DIR="${UPDATE_STATE_DIR:-${APP_DIR}/update-state}"
  PANEL_UPDATE_REPOSITORY="${PANEL_UPDATE_REPOSITORY:-${REPO_URL}}"
  release_version="$(sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([0-9][0-9.]*\)".*/\1/p' "${APP_DIR}/vite-frontend/package.json" | head -n 1)"
  if [[ "${release_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    PANEL_VERSION="${release_version}"
  else
    PANEL_VERSION="${PANEL_VERSION:-1.3.106}"
  fi
  PANEL_COMMIT="${CURRENT_COMMIT:-$(git -C "${APP_DIR}" rev-parse HEAD 2>/dev/null || true)}"
  FRONTEND_PORT="${FRONTEND_PORT:-${FRONTEND_PORT_ENV:-6366}}"
  BACKEND_PORT="${BACKEND_PORT:-${BACKEND_PORT_ENV:-6365}}"
  PHPMYADMIN_PORT="${PHPMYADMIN_PORT:-${PHPMYADMIN_PORT_ENV:-8066}}"
  DOMAIN="${DOMAIN:-${DOMAIN_ENV:-}}"
  EMAIL="${EMAIL:-${LETSENCRYPT_EMAIL:-}}"

  FRONTEND_PORT="$(pick_port "${FRONTEND_PORT}")"
  BACKEND_PORT="$(pick_port "${BACKEND_PORT}")"
  PHPMYADMIN_PORT="$(pick_port "${PHPMYADMIN_PORT}")"

  local profiles=""
  if [[ "${WITH_PHPMYADMIN}" -eq 1 || "${COMPOSE_PROFILES:-}" == *tools* ]]; then
    profiles="tools"
  fi

  local panel_url
  if [[ -n "${DOMAIN}" ]]; then
    if [[ "${SKIP_SSL}" -eq 1 ]]; then
      panel_url="http://${DOMAIN}"
    else
      panel_url="https://${DOMAIN}"
    fi
  else
    panel_url="http://$(detect_public_ip_or_hostname)"
  fi

  cat > "${ENV_FILE}" <<EOF
APP_NAME=${APP_NAME}
APP_SLUG=${APP_SLUG}
COMPOSE_PROJECT_NAME=${APP_SLUG}
DEPLOY_REF=${DEPLOY_REF}
MYSQL_IMAGE=${MYSQL_IMAGE}
DB_NAME=${DB_NAME}
DB_USER=${DB_USER}
DB_PASSWORD=${DB_PASSWORD}
JWT_SECRET=${JWT_SECRET}
OCI_ENCRYPTION_KEY=${OCI_ENCRYPTION_KEY}
AWS_ENCRYPTION_KEY=${AWS_ENCRYPTION_KEY}
FRONTEND_NODE_VERSION=${FRONTEND_NODE_VERSION}
FRONTEND_BUILD_MAX_OLD_SPACE_SIZE=${FRONTEND_BUILD_MAX_OLD_SPACE_SIZE}
FRONTEND_DOCKERFILE=${FRONTEND_DOCKERFILE}
VITE_LEGACY_BUILD=${VITE_LEGACY_BUILD}
PNPM_VERSION=${PNPM_VERSION}
NPM_REGISTRY=${NPM_REGISTRY}
NPM_FALLBACK_REGISTRY=${NPM_FALLBACK_REGISTRY}
MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL}
DOCKER_LOG_MAX_SIZE=${DOCKER_LOG_MAX_SIZE}
DOCKER_LOG_MAX_FILE=${DOCKER_LOG_MAX_FILE}
DOCKER_BUILD_CACHE_KEEP=${DOCKER_BUILD_CACHE_KEEP}
UPDATE_STATE_DIR=${UPDATE_STATE_DIR}
UPDATE_STATE_HOST_DIR=${UPDATE_STATE_DIR}
PANEL_UPDATE_REPOSITORY=${PANEL_UPDATE_REPOSITORY}
PANEL_VERSION=${PANEL_VERSION}
PANEL_COMMIT=${PANEL_COMMIT}
FRONTEND_PORT=${FRONTEND_PORT}
BACKEND_PORT=${BACKEND_PORT}
PHPMYADMIN_PORT=${PHPMYADMIN_PORT}
COMPOSE_PROFILES=${profiles}
DOMAIN=${DOMAIN}
LETSENCRYPT_EMAIL=${EMAIL}
PANEL_URL=${panel_url}
SSL_ENABLED=false
EOF
  chmod 600 "${ENV_FILE}"
}

detect_public_ip_or_hostname() {
  local ip
  ip="$(curl -4 -fsS --max-time 5 https://api.ipify.org 2>/dev/null || true)"
  if [[ -z "${ip}" ]]; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
  fi
  printf '%s' "${ip:-127.0.0.1}"
}

collect_install_config() {
  if [[ -z "${DOMAIN}" && "${ASSUME_YES}" -eq 0 ]]; then
    read -r -p "Panel domain (leave blank for HTTP by server IP): " DOMAIN
  fi
  DOMAIN="$(sanitize_domain "${DOMAIN}")"

  if [[ -n "${DOMAIN}" && "${SKIP_SSL}" -eq 0 ]]; then
    EMAIL="${EMAIL:-admin@${DOMAIN}}"
    prompt_value EMAIL "Let's Encrypt email" "${EMAIL}"
  fi

  FRONTEND_PORT="${FRONTEND_PORT:-6366}"
  BACKEND_PORT="${BACKEND_PORT:-6365}"
  PHPMYADMIN_PORT="${PHPMYADMIN_PORT:-8066}"
  prompt_value FRONTEND_PORT "Local frontend bind port" "${FRONTEND_PORT}"
  prompt_value BACKEND_PORT "Local backend bind port" "${BACKEND_PORT}"
  prompt_value PHPMYADMIN_PORT "Local phpMyAdmin bind port" "${PHPMYADMIN_PORT}"

  if [[ "${WITH_PHPMYADMIN}" -eq 0 && "${ASSUME_YES}" -eq 0 ]]; then
    if confirm "Enable phpMyAdmin on 127.0.0.1:${PHPMYADMIN_PORT}?"; then
      WITH_PHPMYADMIN=1
    fi
  fi
}

restore_saved_install_artifacts() {
  local backup_dir="$1"
  local artifact

  if [[ -f "${backup_dir}/.env" && ! -f "${APP_DIR}/.env" ]]; then
    cp -a "${backup_dir}/.env" "${APP_DIR}/.env"
    log "Restored existing deployment settings from ${backup_dir}/.env."
  fi

  if [[ -d "${backup_dir}/update-state" ]]; then
    mkdir -p "${APP_DIR}/update-state"
    for artifact in request.json result.json github-token self-heal.state; do
      if [[ -e "${backup_dir}/update-state/${artifact}" ]]; then
        cp -a "${backup_dir}/update-state/${artifact}" "${APP_DIR}/update-state/${artifact}"
      fi
    done
    for artifact in "${backup_dir}/update-state"/rollback-*.env; do
      [[ -e "${artifact}" ]] || continue
      cp -a "${artifact}" "${APP_DIR}/update-state/"
    done
  fi
}

sync_repo() {
  REPO_URL="${PANEL_UPDATE_REPOSITORY:-${REPO_URL}}"
  if [[ "${REPO_URL}" != *://* ]]; then
    REPO_URL="https://github.com/${REPO_URL%.git}.git"
  fi
  configure_github_ipv4_fallback || true

  local displaced_app_dir=""
  local clone_error="Cannot clone ${REPO_URL}. The VPS cannot reach GitHub; check DNS/network access or use the panel's local-source deployment."

  if [[ -d "${APP_DIR}/.git" ]]; then
    log "Updating repository in ${APP_DIR}..."
    git -C "${APP_DIR}" remote set-url origin "${REPO_URL}" || return $?
    PREVIOUS_COMMIT="$(git -C "${APP_DIR}" rev-parse HEAD 2>/dev/null || true)"
  else
    mkdir -p "$(dirname "${APP_DIR}")"
    if [[ -e "${APP_DIR}" ]]; then
      # The deploy lock creates update-state before the first clone. Remove only
      # that known empty-install residue; preserve all other files in a backup.
      local unexpected_entry
      unexpected_entry="$(find "${APP_DIR}" -mindepth 1 ! -path "${APP_DIR}/update-state" ! -path "${APP_DIR}/update-state/deploy.lock" -print -quit 2>/dev/null || true)"
      if [[ -z "${unexpected_entry}" ]]; then
        rm -f "${APP_DIR}/update-state/deploy.lock"
        rmdir "${APP_DIR}/update-state" "${APP_DIR}" 2>/dev/null || true
      fi
    fi

    if [[ -e "${APP_DIR}" ]]; then
      displaced_app_dir="${APP_DIR}.pre-git-$(date +%Y%m%d%H%M%S)"
      local suffix=0
      while [[ -e "${displaced_app_dir}" ]]; do
        suffix=$((suffix + 1))
        displaced_app_dir="${APP_DIR}.pre-git-$(date +%Y%m%d%H%M%S)-${suffix}"
      done
      log "Existing non-Git directory found. Backing it up to ${displaced_app_dir} and continuing."
      mv "${APP_DIR}" "${displaced_app_dir}" || die "Cannot move ${APP_DIR} to ${displaced_app_dir}. Check permissions and disk space."
    fi

    log "Cloning ${REPO_URL} to ${APP_DIR}..."
    if ! git_authenticated clone "${REPO_URL}" "${APP_DIR}"; then
      rm -rf "${APP_DIR}"
      warn "GitHub clone failed; retrying once after IPv4 fallback setup..."
      configure_github_ipv4_fallback || true
      if ! git_authenticated clone "${REPO_URL}" "${APP_DIR}"; then
        rm -rf "${APP_DIR}"
        if [[ -n "${displaced_app_dir}" ]]; then
          mv "${displaced_app_dir}" "${APP_DIR}" || die "Clone failed and the backup could not be restored from ${displaced_app_dir}."
        fi
        die "${clone_error}"
      fi
    fi

    if [[ -n "${displaced_app_dir}" ]]; then
      restore_saved_install_artifacts "${displaced_app_dir}"
      warn "Backup retained at ${displaced_app_dir}; remove it manually after verifying the installation."
    fi

    # The install lock was opened inside the displaced directory. Rebind it
    # to the freshly cloned checkout before continuing the install.
    if [[ -n "${DEPLOY_LOCK_FD}" ]]; then
      release_deploy_lock
      acquire_deploy_lock
    fi
  fi

  if ! git_authenticated -C "${APP_DIR}" fetch origin --prune --tags --force; then
    warn "GitHub fetch failed; retrying once after IPv4 fallback setup..."
    configure_github_ipv4_fallback || true
    git_authenticated -C "${APP_DIR}" fetch origin --prune --tags --force || die "Cannot update ${REPO_URL}. The VPS cannot resolve or reach GitHub; check DNS/network access or use the panel's local-source deployment."
  fi
  checkout_deploy_ref || return $?
  [[ -f "${APP_DIR}/${COMPOSE_FILE}" ]] || die "Missing ${COMPOSE_FILE} in ${APP_DIR}."
}

checkout_deploy_ref() {
  [[ -n "${DEPLOY_REF}" ]] || DEPLOY_REF="main"

  if git -C "${APP_DIR}" show-ref --verify --quiet "refs/remotes/origin/${DEPLOY_REF}"; then
    log "Deploying branch: ${DEPLOY_REF}"
    git -C "${APP_DIR}" checkout -B "${DEPLOY_REF}" "origin/${DEPLOY_REF}" || return $?
    git -C "${APP_DIR}" reset --hard "origin/${DEPLOY_REF}" || return $?
  elif git -C "${APP_DIR}" show-ref --verify --quiet "refs/tags/${DEPLOY_REF}"; then
    log "Deploying tag: ${DEPLOY_REF}"
    git -C "${APP_DIR}" checkout --detach -f "refs/tags/${DEPLOY_REF}" || return $?
  elif git -C "${APP_DIR}" rev-parse --verify --quiet "${DEPLOY_REF}^{commit}" >/dev/null; then
    log "Deploying commit: ${DEPLOY_REF}"
    git -C "${APP_DIR}" checkout --detach -f "${DEPLOY_REF}" || return $?
  else
    die "Git ref not found: ${DEPLOY_REF}. Use a branch, tag, or commit from ${REPO_URL}."
  fi

  CURRENT_COMMIT="$(git -C "${APP_DIR}" rev-parse HEAD)" || return $?
  log "Checked out commit: ${CURRENT_COMMIT:0:7}"
}

compose() {
  DOCKER_BUILDKIT=1 COMPOSE_DOCKER_CLI_BUILD=1 docker compose --env-file "${ENV_FILE}" -f "${APP_DIR}/${COMPOSE_FILE}" "$@"
}

git_authenticated() {
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    local credential
    credential="$(printf 'x-access-token:%s' "${GITHUB_TOKEN}" | base64 | tr -d '\r\n')" || return $?
    GIT_CONFIG_COUNT=1 \
      GIT_CONFIG_KEY_0=http.https://github.com/.extraHeader \
      GIT_CONFIG_VALUE_0="Authorization: Basic ${credential}" \
      git "$@"
  else
    git "$@"
  fi
}

append_build_service() {
  local service="$1"
  local existing
  for existing in "${BUILD_SERVICES[@]}"; do
    [[ "${existing}" == "${service}" ]] && return 0
  done
  BUILD_SERVICES+=("${service}")
}

image_exists() {
  local service="$1"
  docker image inspect "${APP_SLUG}-${service}:local" >/dev/null 2>&1
}

build_service_requested() {
  local wanted="$1"
  shift || true
  local service
  for service in "$@"; do
    [[ "${service}" == "${wanted}" ]] && return 0
  done
  return 1
}

build_panel_services() {
  local services=("$@")
  local ordered_services=()
  local service

  if [[ "${#services[@]}" -eq 0 ]]; then
    ordered_services=(backend frontend)
  else
    build_service_requested backend "${services[@]}" && ordered_services+=(backend)
    build_service_requested frontend "${services[@]}" && ordered_services+=(frontend)
    for service in "${services[@]}"; do
      case "${service}" in
        backend|frontend)
          ;;
        *)
          ordered_services+=("${service}")
          ;;
      esac
    done
  fi

  for service in "${ordered_services[@]}"; do
    log "Building panel image: ${service}"
    if ! compose build "${service}"; then
      if [[ "${DOCKER_IPV4_HOSTS_ACTIVE}" -eq 0 ]]; then
        configure_docker_ipv4_fallback
      fi
      if [[ "${DOCKER_IPV4_HOSTS_ACTIVE}" -eq 1 ]]; then
        warn "Docker image build failed once; retrying with the temporary IPv4 fallback."
        if ! compose build "${service}"; then
          err "Docker image build failed for ${service}."
          err "This VPS cannot currently download one or more official Docker Hub images."
          err "Check outbound TCP/443 access to registry-1.docker.io, auth.docker.io, and production.cloudflare.docker.com, then retry the same update command."
          return 1
        fi
      else
        err "Docker image build failed for ${service}."
        err "The VPS cannot currently reach the official Docker image registry. Check outbound TCP/443 access and retry."
        return 1
      fi
    fi
  done
}

commit_exists() {
  local commit="$1"
  [[ -n "${commit}" ]] && git -C "${APP_DIR}" cat-file -e "${commit}^{commit}" >/dev/null 2>&1
}

determine_update_build_services() {
  BUILD_SERVICES=()

  if [[ "${FULL_REBUILD:-0}" == "1" || "${FORCE_REBUILD:-0}" == "1" ]]; then
    append_build_service frontend
    append_build_service backend
    log "Full rebuild requested."
    return 0
  fi

  image_exists frontend || append_build_service frontend
  image_exists backend || append_build_service backend

  local base_commit="${LAST_DEPLOYED_COMMIT:-}"
  if ! commit_exists "${base_commit}"; then
    base_commit="${PREVIOUS_COMMIT:-}"
  fi

  if ! commit_exists "${base_commit}" || ! commit_exists "${CURRENT_COMMIT}"; then
    warn "No reliable deployed commit marker found; existing images will be reused unless they are missing."
    return 0
  fi

  if [[ "${base_commit}" == "${CURRENT_COMMIT}" ]]; then
    return 0
  fi

  if [[ -z "${LAST_DEPLOYED_COMMIT:-}" ]]; then
    warn "No previous successful deploy marker found; using the previous checkout as the diff base for this update."
  fi

  log "Checking changed paths from ${base_commit:0:7} to ${CURRENT_COMMIT:0:7}..."
  local path
  while IFS= read -r path; do
    case "${path}" in
      vite-frontend/*)
        append_build_service frontend
        ;;
      springboot-backend/*)
        append_build_service backend
        ;;
      docker-compose.deploy.yml)
        log "Compose file changed; containers will be recreated with the latest runtime settings."
        ;;
    esac
  done < <(git -C "${APP_DIR}" diff --name-only "${base_commit}" "${CURRENT_COMMIT}")
}

mark_deployed_commit() {
  if [[ -n "${CURRENT_COMMIT:-}" ]]; then
    set_env_value LAST_DEPLOYED_COMMIT "${CURRENT_COMMIT}"
  fi
}

stop_existing_stack_for_install() {
  if [[ -f "${APP_DIR}/${COMPOSE_FILE}" && -f "${ENV_FILE}" ]]; then
    log "Stopping existing project containers before install/reinstall..."
    compose down --remove-orphans || warn "Docker Compose down failed; removing known project containers directly."
  fi

  docker rm -f \
    "${APP_SLUG}-frontend" \
    "${APP_SLUG}-backend" \
    "${APP_SLUG}-phpmyadmin" \
    "${APP_SLUG}-mysql" >/dev/null 2>&1 || true
}

extract_allocated_port() {
  sed -n 's/.*Bind for .*:\([0-9][0-9]*\) failed: port is already allocated.*/\1/p' | tail -n 1
}

bump_conflicting_port() {
  local port="$1"
  local key=""
  local next

  load_env
  if [[ "${port}" == "${FRONTEND_PORT:-}" ]]; then
    key="FRONTEND_PORT"
  elif [[ "${port}" == "${BACKEND_PORT:-}" ]]; then
    key="BACKEND_PORT"
  elif [[ "${port}" == "${PHPMYADMIN_PORT:-}" ]]; then
    key="PHPMYADMIN_PORT"
  else
    warn "Could not map conflicting port ${port} to a managed service port."
    return 1
  fi

  next=$((port + 1))
  while port_in_use_by_other "${next}"; do
    next=$((next + 1))
  done

  warn "${key} ${port} is unavailable. Retrying with ${next}."
  set_env_value "${key}" "${next}"
  printf -v "${key}" '%s' "${next}"
}

compose_up_with_port_retry() {
  local recreate="${1:-0}"
  local attempt=1
  local max_attempts=8
  local output
  local status
  local port

  while [[ "${attempt}" -le "${max_attempts}" ]]; do
    if [[ "${recreate}" -eq 1 ]]; then
      if output="$(compose up -d --force-recreate --remove-orphans 2>&1)"; then
        printf '%s\n' "${output}"
        return 0
      else
        status=$?
      fi
    else
      if output="$(compose up -d --remove-orphans 2>&1)"; then
        printf '%s\n' "${output}"
        return 0
      else
        status=$?
      fi
    fi

    printf '%s\n' "${output}" >&2
    port="$(printf '%s\n' "${output}" | extract_allocated_port)"
    [[ -n "${port}" ]] || return "${status}"

    warn "Port ${port} was allocated during container startup. Releasing partial stack and retrying."
    compose down --remove-orphans >/dev/null 2>&1 || true
    bump_conflicting_port "${port}" || return "${status}"
    attempt=$((attempt + 1))
  done

  die "Could not find an available local bind port after ${max_attempts} attempts."
}

install_cli_wrapper() {
  cat > "/usr/local/bin/${APP_SLUG}" <<EOF
#!/usr/bin/env bash
exec bash "${APP_DIR}/deploy.sh" "\$@"
EOF
  chmod +x "/usr/local/bin/${APP_SLUG}"
}

ensure_update_state_dir() {
  UPDATE_STATE_DIR="${UPDATE_STATE_DIR:-${APP_DIR}/update-state}"
  mkdir -p "${UPDATE_STATE_DIR}"
  chmod 700 "${UPDATE_STATE_DIR}"
}

acquire_deploy_lock() {
  [[ -n "${DEPLOY_LOCK_FD}" ]] && return 0
  ensure_update_state_dir
  exec {DEPLOY_LOCK_FD}>"${UPDATE_STATE_DIR}/deploy.lock"
  flock -n "${DEPLOY_LOCK_FD}" || die "Another panel install or update is already running."
}

release_deploy_lock() {
  if [[ -n "${DEPLOY_LOCK_FD}" ]]; then
    flock -u "${DEPLOY_LOCK_FD}" 2>/dev/null || true
    eval "exec ${DEPLOY_LOCK_FD}>&-" 2>/dev/null || true
    DEPLOY_LOCK_FD=""
  fi
}

try_acquire_deploy_lock() {
  [[ -n "${DEPLOY_LOCK_FD}" ]] && return 0
  ensure_update_state_dir
  exec {DEPLOY_LOCK_FD}>"${UPDATE_STATE_DIR}/deploy.lock"
  if ! flock -n "${DEPLOY_LOCK_FD}"; then
    eval "exec ${DEPLOY_LOCK_FD}>&-" 2>/dev/null || true
    DEPLOY_LOCK_FD=""
    return 1
  fi
}

install_self_heal_timer() {
  ensure_update_state_dir
  cat > "/etc/systemd/system/${APP_SLUG}-self-heal.service" <<EOF
[Unit]
Description=${APP_NAME} panel self-healing check
After=docker.service nginx.service

[Service]
Type=oneshot
ExecStart=/usr/local/bin/${APP_SLUG} self-heal --yes
TimeoutStartSec=10min
EOF
  cat > "/etc/systemd/system/${APP_SLUG}-self-heal.timer" <<EOF
[Unit]
Description=${APP_NAME} panel self-healing timer

[Timer]
OnBootSec=2min
OnUnitActiveSec=1min
Persistent=true
AccuracySec=30s

[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload
  systemctl enable --now "${APP_SLUG}-self-heal.timer"
}

install_update_timer() {
  ensure_update_state_dir
  cat > "/etc/systemd/system/${APP_SLUG}-auto-update.service" <<EOF
[Unit]
Description=${APP_NAME} automatic main commit updater
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
ExecStart=/usr/local/bin/${APP_SLUG} auto-update --yes
EOF
  cat > "/etc/systemd/system/${APP_SLUG}-auto-update.timer" <<EOF
[Unit]
Description=${APP_NAME} automatic main commit updater timer

[Timer]
OnBootSec=5min
OnUnitActiveSec=3min
Persistent=true

[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload
  systemctl enable --now "${APP_SLUG}-auto-update.timer"
  install_self_heal_timer
}

remove_update_timer() {
  systemctl disable --now "${APP_SLUG}-auto-update.timer" >/dev/null 2>&1 || true
  systemctl disable --now "${APP_SLUG}-self-heal.timer" >/dev/null 2>&1 || true
  rm -f "/etc/systemd/system/${APP_SLUG}-auto-update.timer" "/etc/systemd/system/${APP_SLUG}-auto-update.service"
  rm -f "/etc/systemd/system/${APP_SLUG}-self-heal.timer" "/etc/systemd/system/${APP_SLUG}-self-heal.service"
  systemctl daemon-reload >/dev/null 2>&1 || true
}

json_value() {
  local key="$1" file="$2"
  [[ -f "${file}" ]] || return 0
  sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "${file}" | head -n 1
}

json_safe() {
  printf '%s' "${1:-}" | tr '\r\n\"' '   '
}

write_update_result() {
  local request_id="$1" status="$2" message="$3" result_file="${UPDATE_STATE_DIR}/result.json" tmp
  local safe_message
  safe_message="$(json_safe "${message}")"
  tmp="${result_file}.tmp.$$"
  printf '{"requestId":"%s","status":"%s","message":"%s","finishedAt":%s}\n' \
    "$(json_safe "${request_id}")" "${status}" "${safe_message}" "$(date +%s000)" > "${tmp}"
  chmod 600 "${tmp}"
  mv -f "${tmp}" "${result_file}"
}

consume_update_request() {
  local request_id="$1" request_file="${UPDATE_STATE_DIR}/request.json"
  [[ "$(json_value requestId "${request_file}")" == "${request_id}" ]] || return 0
  # Keep the request present until its credential is removed. The backend
  # must not enqueue another request while this credential is being consumed.
  rm -f "${UPDATE_STATE_DIR}/github-token" || return $?
  rm -f "${request_file}"
}

auto_update_flow() {
  load_env
  ensure_update_state_dir
  acquire_deploy_lock

  local request_file="${UPDATE_STATE_DIR}/request.json"
  local token_file="${UPDATE_STATE_DIR}/github-token"
  local result_file="${UPDATE_STATE_DIR}/result.json"
  [[ -f "${request_file}" ]] || { release_deploy_lock; return 0; }

  local request_id tag commit previous_id previous_status
  request_id="$(json_value requestId "${request_file}")"
  tag="$(json_value tag "${request_file}")"
  commit="$(json_value commit "${request_file}")"
  previous_id="$(json_value requestId "${result_file}")"
  previous_status="$(json_value status "${result_file}")"
  [[ "${request_id}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ && "${tag}" == main ]] || {
    write_update_result "${request_id}" failed "更新请求格式无效"
    consume_update_request "${request_id}"
    release_deploy_lock
    return 1
  }
  if [[ ! "${commit}" =~ ^[0-9a-fA-F]{40}$ ]]; then
    write_update_result "${request_id}" failed "更新请求中的提交 SHA 无效"
    consume_update_request "${request_id}"
    release_deploy_lock
    return 1
  fi
  if [[ "${previous_id}" == "${request_id}" && ( "${previous_status}" == success || "${previous_status}" == failed ) ]]; then
    consume_update_request "${request_id}"
    release_deploy_lock
    return 0
  fi
  if [[ "${LAST_DEPLOYED_COMMIT:-}" =~ ^[0-9a-fA-F]{40}$ && "${LAST_DEPLOYED_COMMIT,,}" == "${commit,,}" ]]; then
    write_update_result "${request_id}" success "提交 ${commit:0:7} 已部署，跳过拉取、构建和重启"
    consume_update_request "${request_id}"
    release_deploy_lock
    return 0
  fi

  if [[ -f "${token_file}" ]]; then
    GITHUB_TOKEN="$(<"${token_file}")"
    export GITHUB_TOKEN
  fi
  write_update_result "${request_id}" running "正在拉取 ${tag} 并重建面板"
  if auto_update_apply "${request_id}" "${tag}" "${commit}"; then
    write_update_result "${request_id}" success "面板已更新到 main 最新提交 ${commit:0:7}"
  else
    write_update_result "${request_id}" failed "面板自动更新失败，已尝试恢复之前版本"
    consume_update_request "${request_id}"
    unset GITHUB_TOKEN
    release_deploy_lock
    return 1
  fi
  consume_update_request "${request_id}"
  unset GITHUB_TOKEN
  release_deploy_lock
}

auto_update_cleanup_snapshot() {
  local image_tag
  for image_tag in "${AUTO_ROLLBACK_BACKEND_TAG:-}" "${AUTO_ROLLBACK_FRONTEND_TAG:-}"; do
    [[ -n "${image_tag}" ]] && docker image rm "${image_tag}" >/dev/null 2>&1 || true
  done
  [[ -n "${AUTO_ROLLBACK_ENV_FILE:-}" ]] && rm -f "${AUTO_ROLLBACK_ENV_FILE}"
}

auto_update_rollback() {
  local exit_code="$1" restored=1 stack_changed="${AUTO_ROLLBACK_STACK_CHANGED:-0}"
  trap - EXIT
  if [[ "${exit_code}" -eq 0 || "${AUTO_ROLLBACK_ACTIVE:-0}" -ne 1 ]]; then
    exit "${exit_code}"
  fi

  set +e
  err "Automatic update failed; restoring the previous panel release."
  git -C "${APP_DIR}" checkout --detach -f "${AUTO_ROLLBACK_COMMIT}" >/dev/null 2>&1 || restored=0
  cp -p "${AUTO_ROLLBACK_ENV_FILE}" "${ENV_FILE}" || restored=0
  chmod 600 "${ENV_FILE}" 2>/dev/null || restored=0
  docker tag "${AUTO_ROLLBACK_BACKEND_ID}" "${APP_SLUG}-backend:local" || restored=0
  docker tag "${AUTO_ROLLBACK_FRONTEND_ID}" "${APP_SLUG}-frontend:local" || restored=0
  if [[ "${restored}" -ne 1 ]]; then
    err "Could not restore all panel files and images. The rollback snapshot was kept."
    exit "${exit_code}"
  fi
  load_env

  if [[ "${stack_changed}" -eq 1 ]]; then
    if compose up -d --force-recreate --remove-orphans \
        && wait_for_health "${APP_SLUG}-mysql" 180 \
        && wait_for_health "${APP_SLUG}-backend" 240 \
        && wait_for_health "${APP_SLUG}-frontend" 120; then
      warn "Previous panel release is healthy again."
      auto_update_cleanup_snapshot
    else
      err "Panel rollback could not be verified. The previous images and environment backup were kept."
    fi
  else
    warn "Automatic update failed before containers changed; the current panel containers were left running."
    auto_update_cleanup_snapshot
  fi
  exit "${exit_code}"
}

auto_update_apply() (
  local request_id="$1" tag="$2" target_commit="$3"
  load_env
  AUTO_ROLLBACK_COMMIT="$(git -C "${APP_DIR}" rev-parse HEAD 2>/dev/null || true)"
  [[ -n "${AUTO_ROLLBACK_COMMIT}" ]] || return 1

  AUTO_ROLLBACK_BACKEND_ID="$(docker image inspect -f '{{.Id}}' "${APP_SLUG}-backend:local" 2>/dev/null || true)"
  AUTO_ROLLBACK_FRONTEND_ID="$(docker image inspect -f '{{.Id}}' "${APP_SLUG}-frontend:local" 2>/dev/null || true)"
  [[ -n "${AUTO_ROLLBACK_BACKEND_ID}" && -n "${AUTO_ROLLBACK_FRONTEND_ID}" ]] || {
    err "Cannot snapshot the current panel images for rollback."
    return 1
  }

  AUTO_ROLLBACK_ENV_FILE="${UPDATE_STATE_DIR}/rollback-${request_id}.env"
  AUTO_ROLLBACK_BACKEND_TAG="${APP_SLUG}-backend:rollback-${request_id}"
  AUTO_ROLLBACK_FRONTEND_TAG="${APP_SLUG}-frontend:rollback-${request_id}"
  cp -p "${ENV_FILE}" "${AUTO_ROLLBACK_ENV_FILE}" || return 1
  chmod 600 "${AUTO_ROLLBACK_ENV_FILE}" || return $?
  if ! docker tag "${AUTO_ROLLBACK_BACKEND_ID}" "${AUTO_ROLLBACK_BACKEND_TAG}" \
      || ! docker tag "${AUTO_ROLLBACK_FRONTEND_ID}" "${AUTO_ROLLBACK_FRONTEND_TAG}"; then
    auto_update_cleanup_snapshot
    return 1
  fi

  AUTO_ROLLBACK_ACTIVE=1
  AUTO_ROLLBACK_STACK_CHANGED=0
  trap 'auto_update_rollback "$?"' EXIT
  DEPLOY_REF="${tag}"
  DEPLOY_REF_ARG="${target_commit}"
  update_flow || exit $?
  set_env_value DEPLOY_REF "${tag}" || exit $?

  AUTO_ROLLBACK_ACTIVE=0
  trap - EXIT
  auto_update_cleanup_snapshot
)

wait_for_health() {
  local container="$1"
  local timeout="${2:-180}"
  local elapsed=0
  local status=""
  local has_health=""

  while [[ "${elapsed}" -lt "${timeout}" ]]; do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container}" 2>/dev/null || true)"
    has_health="$(docker inspect -f '{{if .State.Health}}yes{{else}}no{{end}}' "${container}" 2>/dev/null || true)"
    if [[ "${has_health}" == "yes" && "${status}" == "healthy" ]]; then
      return 0
    fi
    if [[ "${has_health}" == "no" && "${status}" == "running" ]]; then
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done

  warn "${container} did not become healthy within ${timeout}s. Current status: ${status:-unknown}"
  return 1
}

self_heal_state_path() {
  SELF_HEAL_STATE_FILE="${UPDATE_STATE_DIR}/self-heal.state"
}

self_heal_read_state() {
  self_heal_state_path
  SELF_HEAL_FAILURES=0
  SELF_HEAL_LAST_REBOOT=0
  [[ -f "${SELF_HEAL_STATE_FILE}" ]] || return 0

  local key value
  while IFS='=' read -r key value; do
    case "${key}" in
      failures) SELF_HEAL_FAILURES="${value:-0}" ;;
      last_reboot) SELF_HEAL_LAST_REBOOT="${value:-0}" ;;
    esac
  done < "${SELF_HEAL_STATE_FILE}"
  [[ "${SELF_HEAL_FAILURES}" =~ ^[0-9]+$ ]] || SELF_HEAL_FAILURES=0
  [[ "${SELF_HEAL_LAST_REBOOT}" =~ ^[0-9]+$ ]] || SELF_HEAL_LAST_REBOOT=0
}

self_heal_write_state() {
  self_heal_state_path
  local tmp="${SELF_HEAL_STATE_FILE}.tmp.$$"
  printf 'failures=%s\nlast_reboot=%s\n' \
    "${SELF_HEAL_FAILURES:-0}" "${SELF_HEAL_LAST_REBOOT:-0}" > "${tmp}"
  chmod 600 "${tmp}"
  mv -f "${tmp}" "${SELF_HEAL_STATE_FILE}"
}

self_heal_container_ready() {
  local container="$1" status has_health
  status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container}" 2>/dev/null || true)"
  has_health="$(docker inspect -f '{{if .State.Health}}yes{{else}}no{{end}}' "${container}" 2>/dev/null || true)"
  [[ "${has_health}" == "yes" && "${status}" == "healthy" ]] || \
    [[ "${has_health}" == "no" && "${status}" == "running" ]]
}

self_heal_probe_http() {
  local url="$1"
  curl -fsS --connect-timeout 3 --max-time 8 -o /dev/null "${url}" >/dev/null 2>&1
}

self_heal_probe_nginx_entry() {
  local host="${DOMAIN:-}" scheme=http port=80
  [[ -n "${host}" ]] || return 0
  host="$(sanitize_domain "${host}")"
  if [[ "${SSL_ENABLED:-false}" == "true" || "${PANEL_URL:-}" == https://* ]]; then
    scheme=https
    port=443
  fi
  curl -k -fsS --connect-timeout 3 --max-time 8 \
    --resolve "${host}:${port}:127.0.0.1" "${scheme}://${host}/" \
    -o /dev/null >/dev/null 2>&1
}

self_heal_probe() {
  SELF_HEAL_LAST_FAILURE=""

  if ! systemctl is-active --quiet docker; then
    SELF_HEAL_LAST_FAILURE="Docker service is not active"
    return 1
  fi
  local service container
  for service in mysql backend frontend; do
    container="${APP_SLUG}-${service}"
    if ! self_heal_container_ready "${container}"; then
      SELF_HEAL_LAST_FAILURE="Container ${container} is not healthy"
      return 1
    fi
  done
  if ! self_heal_probe_http "http://127.0.0.1:${FRONTEND_PORT}/"; then
    SELF_HEAL_LAST_FAILURE="Frontend local entry failed"
    return 1
  fi
  if ! self_heal_probe_http "http://127.0.0.1:${FRONTEND_PORT}/flow/test"; then
    SELF_HEAL_LAST_FAILURE="Frontend to backend proxy failed"
    return 1
  fi
  if ! systemctl is-active --quiet nginx || ! nginx -t >/dev/null 2>&1; then
    SELF_HEAL_LAST_FAILURE="Nginx service or configuration failed"
    return 1
  fi
  if ! self_heal_probe_nginx_entry; then
    SELF_HEAL_LAST_FAILURE="Nginx local entry failed"
    return 1
  fi
}

self_heal_restart_unhealthy() {
  local service container
  local unhealthy=()
  for service in mysql backend frontend; do
    container="${APP_SLUG}-${service}"
    if ! self_heal_container_ready "${container}"; then
      unhealthy+=("${container}")
    fi
  done
  [[ "${#unhealthy[@]}" -eq 0 ]] || docker restart "${unhealthy[@]}"
}

self_heal_repair() {
  local repair_status=0
  if ! systemctl is-active --quiet docker; then
    systemctl restart docker || repair_status=1
  fi
  compose up -d --no-build --remove-orphans || repair_status=1
  self_heal_restart_unhealthy || repair_status=1
  wait_for_health "${APP_SLUG}-mysql" 120 || repair_status=1
  wait_for_health "${APP_SLUG}-backend" 180 || repair_status=1
  wait_for_health "${APP_SLUG}-frontend" 120 || repair_status=1
  if nginx -t >/dev/null 2>&1; then
    if ! systemctl reload nginx && ! systemctl restart nginx; then
      repair_status=1
    fi
  else
    repair_status=1
  fi
  return "${repair_status}"
}

self_heal_maybe_reboot() {
  self_heal_read_state
  local now
  now="$(date +%s)"
  if (( SELF_HEAL_LAST_REBOOT > 0 && now - SELF_HEAL_LAST_REBOOT < SELF_HEAL_REBOOT_COOLDOWN )); then
    warn "Self-healing is still failing, but VPS reboot cooldown is active."
    return 1
  fi
  SELF_HEAL_LAST_REBOOT="${now}"
  self_heal_write_state
  warn "Panel self-healing failed after container and Nginx recovery; rebooting the VPS."
  systemctl reboot
}

self_heal_flow() {
  load_env
  [[ -f "${APP_DIR}/${COMPOSE_FILE}" && -f "${ENV_FILE}" ]] || {
    warn "Panel stack is not installed at ${APP_DIR}."
    return 0
  }
  ensure_update_state_dir
  self_heal_read_state

  if self_heal_probe; then
    if (( SELF_HEAL_FAILURES > 0 )); then
      log "Panel self-healing check recovered the stack."
    fi
    SELF_HEAL_FAILURES=0
    self_heal_write_state
    return 0
  fi

  SELF_HEAL_FAILURES=$((SELF_HEAL_FAILURES + 1))
  self_heal_write_state
  warn "Panel self-healing check failed (${SELF_HEAL_FAILURES}/${SELF_HEAL_FAILURE_THRESHOLD}): ${SELF_HEAL_LAST_FAILURE}"
  if (( SELF_HEAL_FAILURES < SELF_HEAL_FAILURE_THRESHOLD )); then
    return 0
  fi

  if ! try_acquire_deploy_lock; then
    warn "A panel install or update is running; self-healing will retry on the next timer tick."
    return 0
  fi
  self_heal_repair || true
  release_deploy_lock

  if self_heal_probe; then
    log "Panel self-healing restored the stack without rebooting the VPS."
    SELF_HEAL_FAILURES=0
    self_heal_write_state
    return 0
  fi
  self_heal_maybe_reboot || true
}

start_stack() {
  local recreate="${1:-0}"
  [[ $# -gt 0 ]] && shift
  local build_mode="${1:-all}"
  [[ $# -gt 0 ]] && shift

  case "${build_mode}" in
    all)
      log "Building panel images..."
      build_panel_services || return $?
      ;;
    changed)
      if [[ "$#" -gt 0 ]]; then
        log "Building changed panel image(s) sequentially: $*"
        build_panel_services "$@" || return $?
      else
        log "No panel image rebuild needed for this update."
      fi
      ;;
    *)
      die "Unknown build mode: ${build_mode}"
      ;;
  esac

  log "Starting containers in background..."
  AUTO_ROLLBACK_STACK_CHANGED=1
  compose_up_with_port_retry "${recreate}" || return $?
  wait_for_health "${APP_SLUG}-mysql" 180 || die "MySQL did not become healthy. Check the MySQL container logs before retrying."
  wait_for_health "${APP_SLUG}-backend" 240 || die "Backend did not become healthy. Nginx was not reconfigured; check the backend container logs before retrying."
  wait_for_health "${APP_SLUG}-frontend" 120 || die "Frontend did not become healthy. Nginx was not reconfigured; check the frontend container logs before retrying."
}

post_deploy_cleanup() {
  log "Running lightweight cleanup after deploy..."
  docker image prune -f >/dev/null 2>&1 || true
  cleanup_package_cache
  log "Skipping automatic Docker build-cache pruning to avoid SSH stalls on small VPS."
}

cleanup_package_cache() {
  log "Cleaning package manager cache..."
  case "${PKG_MANAGER}" in
    apt)
      apt-get clean >/dev/null 2>&1 || true
      rm -rf /var/lib/apt/lists/* >/dev/null 2>&1 || true
      ;;
    dnf)
      dnf clean all >/dev/null 2>&1 || true
      ;;
    yum)
      yum clean all >/dev/null 2>&1 || true
      ;;
    apk)
      rm -rf /var/cache/apk/* >/dev/null 2>&1 || true
      ;;
  esac
}

cleanup_builder_cache() {
  local keep="${DOCKER_BUILD_CACHE_KEEP:-512MB}"
  log "Cleaning Docker build cache, keeping up to ${keep}..."
  if command -v timeout >/dev/null 2>&1; then
    if command -v ionice >/dev/null 2>&1; then
      timeout 300s ionice -c3 nice -n 19 docker builder prune -af --keep-storage "${keep}" >/dev/null 2>&1 || warn "Docker build-cache cleanup timed out or failed; skipped remaining cache cleanup."
    else
      timeout 300s nice -n 19 docker builder prune -af --keep-storage "${keep}" >/dev/null 2>&1 || warn "Docker build-cache cleanup timed out or failed; skipped remaining cache cleanup."
    fi
  else
    nice -n 19 docker builder prune -af --keep-storage "${keep}" >/dev/null 2>&1 || warn "Docker build-cache cleanup failed; skipped remaining cache cleanup."
  fi
}

detect_nginx_ipv6() {
  NGINX_IPV6_ENABLED=0
  if curl -6 -fsS --max-time 5 --noproxy '*' https://api64.ipify.org >/dev/null 2>&1; then
    NGINX_IPV6_ENABLED=1
  fi
}

nginx_ipv6_listen() {
  if [[ "${NGINX_IPV6_ENABLED}" -eq 1 ]]; then
    printf '    listen [::]:%s;\n' "$1"
  fi
}

nginx_conf_path() {
  if [[ -d /etc/nginx/sites-available && -d /etc/nginx/sites-enabled ]]; then
    NGINX_CONF="/etc/nginx/sites-available/${APP_SLUG}.conf"
    NGINX_LINK="/etc/nginx/sites-enabled/${APP_SLUG}.conf"
  else
    NGINX_CONF="/etc/nginx/conf.d/${APP_SLUG}.conf"
    NGINX_LINK=""
  fi
}

disable_default_nginx_site() {
  rm -f /etc/nginx/sites-enabled/default 2>/dev/null || true
  if [[ -f /etc/nginx/conf.d/default.conf ]]; then
    mv /etc/nginx/conf.d/default.conf "/etc/nginx/conf.d/default.conf.${APP_SLUG}.bak" 2>/dev/null || true
  fi
}

activate_nginx_conf() {
  if [[ -n "${NGINX_LINK:-}" ]]; then
    ln -sf "${NGINX_CONF}" "${NGINX_LINK}"
  fi
}

reload_nginx() {
  nginx -t
  systemctl enable --now nginx
  systemctl reload nginx || systemctl restart nginx
}

write_nginx_http_config() {
  local server_name="$1"
  local frontend_port="$2"
  mkdir -p "/var/www/${APP_SLUG}"
  detect_nginx_ipv6

  cat > "${NGINX_CONF}" <<EOF
map \$http_upgrade \$connection_upgrade_${APP_SLUG//-/_} {
    default upgrade;
    '' '';
}

server {
    listen 80;
$(nginx_ipv6_listen 80)    server_name ${server_name};

    client_max_body_size 100m;

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/${APP_SLUG};
        default_type "text/plain";
    }

    # Standalone Android probe callback. It uses a per-node token and does not require panel login.
    location ^~ /api/remote/ {
        access_log off;
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 30s;
        proxy_send_timeout 30s;
    }

    location ^~ /api/probe-sync/ {
        access_log off;
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 30s;
        proxy_send_timeout 30s;
    }

    location / {
        proxy_pass http://127.0.0.1:${frontend_port};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$connection_upgrade_${APP_SLUG//-/_};
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
EOF
}

write_nginx_ssl_config() {
  local domain="$1"
  local frontend_port="$2"
  detect_nginx_ipv6

  cat > "${NGINX_CONF}" <<EOF
map \$http_upgrade \$connection_upgrade_${APP_SLUG//-/_} {
    default upgrade;
    '' '';
}

server {
    listen 80;
$(nginx_ipv6_listen 80)    server_name ${domain};

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/${APP_SLUG};
        default_type "text/plain";
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl http2;
$(if [[ "${NGINX_IPV6_ENABLED}" -eq 1 ]]; then printf '    listen [::]:443 ssl http2;\n'; fi)    server_name ${domain};

    ssl_certificate /etc/letsencrypt/live/${domain}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${domain}/privkey.pem;
    ssl_session_timeout 1d;
    ssl_session_cache shared:${APP_SLUG//-/_}_ssl:10m;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;

    client_max_body_size 100m;

    # Standalone Android probe callback. It uses a per-node token and does not require panel login.
    location ^~ /api/remote/ {
        access_log off;
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 30s;
        proxy_send_timeout 30s;
    }

    location ^~ /api/probe-sync/ {
        access_log off;
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 30s;
        proxy_send_timeout 30s;
    }

    location / {
        proxy_pass http://127.0.0.1:${frontend_port};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$connection_upgrade_${APP_SLUG//-/_};
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
EOF
}

public_ips() {
  {
    curl -4 -fsS --max-time 5 https://api.ipify.org 2>/dev/null || true
    echo
    curl -6 -fsS --max-time 5 https://api64.ipify.org 2>/dev/null || true
    echo
  } | sed '/^$/d' | sort -u
}

domain_ips() {
  local domain="$1"
  {
    if command -v dig >/dev/null 2>&1; then
      dig +short A "${domain}" || true
      dig +short AAAA "${domain}" || true
    fi
    getent ahosts "${domain}" 2>/dev/null | awk '{print $1}' || true
  } | sed '/^$/d' | sort -u
}

ip_sets_intersect() {
  local current="$1"
  local resolved="$2"
  local ip
  [[ -n "${current}" && -n "${resolved}" ]] || return 1

  while read -r ip; do
    [[ -n "${ip}" ]] || continue
    if printf '%s\n' "${current}" | grep -Fxq "${ip}"; then
      return 0
    fi
  done <<< "${resolved}"
  return 1
}

domain_points_here() {
  local domain="$1"
  local current resolved
  current="$(public_ips)"
  resolved="$(domain_ips "${domain}")"
  ip_sets_intersect "${current}" "${resolved}"
}

verify_domain_dns() {
  local domain="$1"
  local current resolved answer resolved_ipv6
  [[ -n "${domain}" ]] || return 1

  current="$(public_ips)"
  resolved="$(domain_ips "${domain}")"
  resolved_ipv6=""
  if command -v dig >/dev/null 2>&1; then
    resolved_ipv6="$(dig +short AAAA "${domain}" 2>/dev/null | sed '/^$/d' | sort -u || true)"
  fi

  if ip_sets_intersect "${current}" "${resolved}"; then
    log "DNS check passed for ${domain}."
    if [[ -n "${resolved_ipv6}" ]] && ! curl -6 -fsS --max-time 5 --noproxy '*' https://api64.ipify.org >/dev/null 2>&1; then
      warn "DNS has an AAAA record, but this VPS has no working public IPv6 route."
      warn "Let's Encrypt may validate the AAAA address first; remove the stale AAAA record or restore IPv6 before requesting HTTPS."
      return 1
    fi
    return 0
  fi

  warn "DNS for ${domain} does not appear to point to this VPS yet."
  warn "Current VPS public IPs:"
  printf '%s\n' "${current:-none}" | sed 's/^/  - /' || true
  warn "Resolved domain IPs:"
  printf '%s\n' "${resolved:-none}" | sed 's/^/  - /' || true

  if [[ "${ASSUME_YES}" -eq 1 ]]; then
    warn "Continuing because --yes was supplied."
    return 0
  fi

  while true; do
    read -r -p "Fix DNS, then press Enter to recheck; type c to continue anyway; type s to skip SSL: " answer
    case "${answer}" in
      c|C) return 0 ;;
      s|S) SKIP_SSL=1; return 1 ;;
      *)
        current="$(public_ips)"
        resolved="$(domain_ips "${domain}")"
        if ip_sets_intersect "${current}" "${resolved}"; then
          log "DNS check passed for ${domain}."
          return 0
        fi
        warn "DNS is still not ready."
        ;;
    esac
  done
}

verify_acme_challenge_locally() {
  local domain="$1"
  local token challenge_file response
  token="$(random_string)"
  challenge_file="/var/www/${APP_SLUG}/.well-known/acme-challenge/${token}"
  mkdir -p "$(dirname "${challenge_file}")"
  printf '%s\n' "${token}" > "${challenge_file}"

  response="$(curl -4 --noproxy '*' -fsS --max-time 10 \
    --resolve "${domain}:80:127.0.0.1" \
    "http://${domain}/.well-known/acme-challenge/${token}" 2>/dev/null || true)"
  rm -f "${challenge_file}"

  if [[ "${response}" != "${token}" ]]; then
    warn "Nginx cannot serve the ACME challenge locally for ${domain}."
    warn "Check the generated Nginx site, duplicate server blocks, and port 80 availability before requesting HTTPS."
    return 1
  fi
  return 0
}

install_certbot_renewal() {
  mkdir -p /etc/letsencrypt/renewal-hooks/deploy
  cat > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh <<'EOF'
#!/usr/bin/env bash
systemctl reload nginx >/dev/null 2>&1 || true
EOF
  chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh

  if systemctl list-unit-files | grep -q '^certbot\.timer'; then
    systemctl enable --now certbot.timer
  else
    cat > /etc/cron.d/${APP_SLUG}-certbot-renew <<EOF
SHELL=/bin/sh
PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
17 3,15 * * * root certbot renew --quiet --deploy-hook "systemctl reload nginx"
EOF
  fi
}

existing_certificate_valid() {
  local domain="$1"
  local cert="/etc/letsencrypt/live/${domain}/fullchain.pem"
  local key="/etc/letsencrypt/live/${domain}/privkey.pem"

  [[ -s "${cert}" && -s "${key}" ]] || return 1
  openssl x509 -checkend 0 -noout -in "${cert}" >/dev/null 2>&1
}

request_certificate() {
  local domain="$1"
  local email="$2"

  [[ -n "${domain}" ]] || return 1
  [[ "${SKIP_SSL}" -eq 0 ]] || return 1

  if ! verify_domain_dns "${domain}"; then
    warn "Skipping certificate request because DNS or IPv6 validation failed."
    set_env_value SSL_ENABLED false
    set_env_value PANEL_URL "http://${domain}"
    return 1
  fi

  if ! verify_acme_challenge_locally "${domain}"; then
    warn "Skipping certificate request because the local ACME challenge check failed."
    set_env_value SSL_ENABLED false
    set_env_value PANEL_URL "http://${domain}"
    return 1
  fi

  log "Requesting Let's Encrypt certificate for ${domain}..."
  if certbot certonly --webroot -w "/var/www/${APP_SLUG}" -d "${domain}" \
      --agree-tos --email "${email}" --non-interactive --keep-until-expiring; then
    install_certbot_renewal
    set_env_value SSL_ENABLED true
    set_env_value PANEL_URL "https://${domain}"
    return 0
  fi

  warn "Certificate request failed. Keeping HTTP-only Nginx config for now."
  set_env_value SSL_ENABLED false
  set_env_value PANEL_URL "http://${domain}"
  return 1
}

configure_nginx() {
  load_env
  nginx_conf_path
  disable_default_nginx_site

  local server_name="_"
  if [[ -n "${DOMAIN:-}" ]]; then
    server_name="${DOMAIN}"
  fi

  log "Writing Nginx reverse proxy config..."
  write_nginx_http_config "${server_name}" "${FRONTEND_PORT}"
  activate_nginx_conf
  reload_nginx

  if [[ -n "${DOMAIN:-}" && "${SKIP_SSL}" -eq 0 ]]; then
    if existing_certificate_valid "${DOMAIN}"; then
      log "Existing valid certificate found for ${DOMAIN}. Reusing it."
      install_certbot_renewal
      set_env_value SSL_ENABLED true
      set_env_value PANEL_URL "https://${DOMAIN}"
      load_env
      write_nginx_ssl_config "${DOMAIN}" "${FRONTEND_PORT}"
      activate_nginx_conf
      reload_nginx
    elif request_certificate "${DOMAIN}" "${LETSENCRYPT_EMAIL:-admin@${DOMAIN}}"; then
      load_env
      write_nginx_ssl_config "${DOMAIN}" "${FRONTEND_PORT}"
      activate_nginx_conf
      reload_nginx
    fi
  fi
}

update_panel_address_in_db() {
  load_env
  local mysql_container="${APP_SLUG}-mysql"
  local now
  now="$(date +%s000)"
  local panel_url="${PANEL_URL:-http://$(detect_public_ip_or_hostname)}"
  local sql

  wait_for_health "${mysql_container}" 180 || true

  sql="INSERT INTO vite_config (\`name\`, \`value\`, \`time\`) VALUES ('ip', '${panel_url}', ${now}), ('app_name', '${APP_NAME}', ${now}) ON DUPLICATE KEY UPDATE \`value\` = VALUES(\`value\`), \`time\` = VALUES(\`time\`);"
  if printf '%s\n' "${sql}" | docker exec -i "${mysql_container}" mysql -u"${DB_USER}" -p"${DB_PASSWORD}" "${DB_NAME}" >/dev/null 2>&1; then
    log "Panel URL saved to database: ${panel_url}"
  else
    warn "Could not write panel URL to database. You can set it later in site settings."
  fi
}

install_flow() {
  local lock_was_held=0
  [[ -n "${DEPLOY_LOCK_FD}" ]] && lock_was_held=1
  acquire_deploy_lock
  install_flow_locked
  [[ "${lock_was_held}" -eq 1 ]] || release_deploy_lock
}

install_flow_locked() {
  detect_system
  install_packages
  install_docker
  configure_firewall
  collect_install_config
  sync_repo
  stop_existing_stack_for_install
  write_env_file
  install_cli_wrapper
  install_update_timer
  ensure_runtime_ports
  start_stack
  mark_deployed_commit
  post_deploy_cleanup
  configure_nginx
  update_panel_address_in_db
  print_summary
}

update_flow() {
  local lock_was_held=0 status=0
  [[ -n "${DEPLOY_LOCK_FD}" ]] && lock_was_held=1
  acquire_deploy_lock || return $?
  update_flow_locked || status=$?
  [[ "${lock_was_held}" -eq 1 ]] || release_deploy_lock
  return "${status}"
}

update_flow_locked() {
  detect_system || return $?
  install_packages || return $?
  install_docker || return $?
  load_env || return $?
  [[ -n "${DEPLOY_REF_ARG}" ]] && DEPLOY_REF="${DEPLOY_REF_ARG}"
  sync_repo || return $?
  set_env_value DEPLOY_REF "${DEPLOY_REF}" || return $?
  sync_build_env_values || return $?
  install_cli_wrapper || return $?
  install_update_timer || return $?
  ensure_runtime_ports || return $?
  determine_update_build_services || return $?
  start_stack 1 changed "${BUILD_SERVICES[@]}" || return $?
  mark_deployed_commit || return $?
  post_deploy_cleanup
  configure_nginx || return $?
  update_panel_address_in_db
  print_summary
}

uninstall_flow() {
  local lock_was_held=0
  [[ -n "${DEPLOY_LOCK_FD}" ]] && lock_was_held=1
  acquire_deploy_lock
  uninstall_flow_locked
  [[ "${lock_was_held}" -eq 1 ]] || release_deploy_lock
}

uninstall_flow_locked() {
  load_env
  if [[ ! -d "${APP_DIR}" ]]; then
    warn "${APP_DIR} does not exist."
  fi

  if [[ "${PURGE_DATA}" -eq 0 ]]; then
    if confirm "Remove database volumes and ${APP_DIR}?"; then
      PURGE_DATA=1
    fi
  fi

  if ! confirm "Uninstall ${APP_NAME} from this VPS?"; then
    log "Cancelled."
    return 0
  fi

  if [[ -f "${APP_DIR}/${COMPOSE_FILE}" && -f "${ENV_FILE}" ]]; then
    if [[ "${PURGE_DATA}" -eq 1 ]]; then
      compose down -v --remove-orphans || true
    else
      compose down --remove-orphans || true
    fi
  fi

  nginx_conf_path
  rm -f "${NGINX_CONF:-}" "${NGINX_LINK:-}" 2>/dev/null || true
  systemctl reload nginx 2>/dev/null || true

  rm -f "/usr/local/bin/${APP_SLUG}" "/etc/cron.d/${APP_SLUG}-certbot-renew"
  remove_update_timer

  if [[ "${PURGE_DATA}" -eq 1 ]]; then
    if [[ -n "${DOMAIN:-}" ]] && command -v certbot >/dev/null 2>&1; then
      certbot delete --cert-name "${DOMAIN}" --non-interactive >/dev/null 2>&1 || true
    fi
    rm -rf "${APP_DIR}"
  fi

  log "Uninstall completed."
}

status_flow() {
  load_env
  if [[ -f "${APP_DIR}/${COMPOSE_FILE}" && -f "${ENV_FILE}" ]]; then
    compose ps
  else
    warn "Stack is not installed at ${APP_DIR}."
  fi

  systemctl --no-pager --full status nginx 2>/dev/null || true
  systemctl --no-pager --full status docker 2>/dev/null || true
  systemctl --no-pager --full status certbot.timer 2>/dev/null || true
  systemctl --no-pager --full status "${APP_SLUG}-self-heal.timer" 2>/dev/null || true
}

logs_flow() {
  load_env
  [[ -f "${APP_DIR}/${COMPOSE_FILE}" && -f "${ENV_FILE}" ]] || die "Stack is not installed."
  compose logs -f --tail=200
}

cleanup_flow() {
  load_env
  [[ -f "${APP_DIR}/${COMPOSE_FILE}" && -f "${ENV_FILE}" ]] || die "Stack is not installed."

  log "Current Docker disk usage:"
  docker system df || true

  if ! confirm "Clean unused containers, networks, dangling images, and package cache?"; then
    log "Cancelled."
    return 0
  fi

  docker container prune -f || true
  docker network prune -f || true
  docker image prune -f || true
  cleanup_package_cache

  warn "Docker build-cache cleanup can cause high disk I/O and may stall SSH on small VPS."
  if confirm "Also clean Docker build cache now?"; then
    cleanup_builder_cache
  fi

  log "Docker disk usage after cleanup:"
  docker system df || true
}

renew_cert_flow() {
  detect_system
  install_packages
  certbot renew
  systemctl reload nginx
}

print_summary() {
  load_env
  local current_commit="unknown"
  if [[ -d "${APP_DIR}/.git" ]]; then
    current_commit="$(git -C "${APP_DIR}" rev-parse --short HEAD 2>/dev/null || printf 'unknown')"
  fi
  echo
  log "Deployment is ready."
  echo "Panel URL: ${PANEL_URL:-http://$(detect_public_ip_or_hostname)}"
  echo "Deploy dir: ${APP_DIR}"
  echo "Deploy ref: ${DEPLOY_REF:-main}"
  echo "Deploy commit: ${current_commit}"
  echo "Command helper: ${APP_SLUG} status | logs | update | uninstall"
  echo "Default admin: admin_user / admin_user"
  echo "Please change the default password after first login."
}

menu_loop() {
  while true; do
    cat <<EOF

===============================================
 ${APP_NAME} VPS 部署菜单
===============================================
 1. 安装 / 重装
 2. 更新并重新构建
 3. 卸载
 4. 查看状态
 5. 查看日志
 6. 续期证书
 7. 清理 Docker 缓存
 0. 退出
===============================================
EOF
    read -r -p "请选择: " choice
    case "${choice}" in
      1) install_flow ;;
      2) update_flow ;;
      3) uninstall_flow ;;
      4) status_flow ;;
      5) logs_flow ;;
      6) renew_cert_flow ;;
      7) cleanup_flow ;;
      0) exit 0 ;;
      *) warn "无效选择。" ;;
    esac
  done
}

main() {
  local original_args=("$@")
  parse_args "$@"
  require_root "${original_args[@]}"

  case "${ACTION}" in
    install)
      if [[ "${ASSUME_YES}" -eq 1 || -n "${DOMAIN}" || -n "${EMAIL}" || -n "${FRONTEND_PORT}" || -n "${BACKEND_PORT}" || -n "${PHPMYADMIN_PORT}" || "${SKIP_SSL}" -eq 1 || "${WITH_PHPMYADMIN}" -eq 1 ]]; then
        install_flow
      else
        menu_loop
      fi
      ;;
    update) update_flow ;;
    auto-update) auto_update_flow ;;
    self-heal) self_heal_flow ;;
    uninstall) uninstall_flow ;;
    status) status_flow ;;
    logs) logs_flow ;;
    cleanup) cleanup_flow ;;
    renew-cert) renew_cert_flow ;;
    menu) menu_loop ;;
    *) usage; exit 1 ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
