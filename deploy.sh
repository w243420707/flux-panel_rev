#!/usr/bin/env bash
set -Eeuo pipefail

APP_NAME="flux-panel_rev"
APP_SLUG="flux-panel-rev"
REPO_URL="${REPO_URL:-https://github.com/w243420707/flux-panel_rev.git}"
DEPLOY_REF="${DEPLOY_REF:-${BRANCH:-main}}"
APP_DIR="${APP_DIR:-/opt/${APP_NAME}}"
COMPOSE_FILE="docker-compose.deploy.yml"
ENV_FILE="${APP_DIR}/.env"
RAW_DEPLOY_URL="${RAW_DEPLOY_URL:-https://raw.githubusercontent.com/w243420707/flux-panel_rev/refs/heads/main/deploy.sh}"

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
OS_PRETTY="unknown"
PKG_MANAGER=""
ARCH_RAW="$(uname -m)"
ARCH="unknown"
COMPOSE_ARCH="unknown"
MYSQL_IMAGE="mysql:5.7"

log() { printf '\033[1;32m[INFO]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[WARN]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[ERR ]\033[0m %s\n' "$*" >&2; }
die() { err "$*"; exit 1; }

usage() {
  cat <<EOF
${APP_NAME} VPS deploy script

Usage:
  sudo bash deploy.sh [command] [options]

Commands:
  install       Install or reinstall the panel
  update        Pull latest code, rebuild images, restart services
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
      install|update|uninstall|status|logs|cleanup|renew-cert|menu)
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
      apt-get install -y ca-certificates curl gnupg lsb-release git nginx certbot python3-certbot-nginx openssl dnsutils iproute2
      ;;
    dnf)
      dnf install -y ca-certificates curl gnupg2 git nginx certbot python3-certbot-nginx openssl bind-utils iproute
      ;;
    yum)
      yum install -y ca-certificates curl gnupg2 git nginx certbot python3-certbot-nginx openssl bind-utils iproute
      ;;
    apk)
      apk update
      apk add --no-cache ca-certificates curl git nginx certbot certbot-nginx openssl bind-tools iproute2 docker docker-cli-compose
      ;;
  esac
}

install_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    log "Docker not found. Installing Docker with the official installer..."
    curl -fsSL https://get.docker.com -o /tmp/get-docker.sh
    sh /tmp/get-docker.sh
  fi

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
    release="$(curl -fsSL https://api.github.com/repos/docker/compose/releases/latest | sed -n 's/.*"tag_name": "\(v[^"]*\)".*/\1/p' | head -n 1 || true)"
    release="${release:-v2.29.7}"
    mkdir -p /usr/local/lib/docker/cli-plugins
    curl -fsSL -o /usr/local/lib/docker/cli-plugins/docker-compose \
      "https://github.com/docker/compose/releases/download/${release}/docker-compose-linux-${COMPOSE_ARCH}"
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
  fi

  docker compose version >/dev/null 2>&1 || die "Docker Compose is still unavailable after installation."
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
    ss -ltn "( sport = :${port} )" 2>/dev/null | grep -q ":${port}"
  else
    netstat -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]${port}$"
  fi
}

pick_port() {
  local port="$1"
  while port_in_use "${port}"; do
    port=$((port + 1))
  done
  printf '%s' "${port}"
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
  tmp="$(mktemp)"
  if [[ -f "${ENV_FILE}" ]]; then
    grep -v "^${key}=" "${ENV_FILE}" > "${tmp}" || true
  fi
  printf '%s=%s\n' "${key}" "${value}" >> "${tmp}"
  mv "${tmp}" "${ENV_FILE}"
  chmod 600 "${ENV_FILE}"
}

write_env_file() {
  mkdir -p "${APP_DIR}"
  load_env
  [[ -n "${DEPLOY_REF_ARG}" ]] && DEPLOY_REF="${DEPLOY_REF_ARG}"

  DB_NAME="${DB_NAME:-gost}"
  DB_USER="${DB_USER:-$(random_string)}"
  DB_PASSWORD="${DB_PASSWORD:-$(random_string)}"
  JWT_SECRET="${JWT_SECRET:-$(random_string)}"
  PNPM_VERSION="${PNPM_VERSION:-11.7.0}"
  NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org}"
  NPM_FALLBACK_REGISTRY="${NPM_FALLBACK_REGISTRY:-https://registry.npmmirror.com}"
  MAVEN_MIRROR_URL="${MAVEN_MIRROR_URL:-}"
  DOCKER_LOG_MAX_SIZE="${DOCKER_LOG_MAX_SIZE:-50m}"
  DOCKER_LOG_MAX_FILE="${DOCKER_LOG_MAX_FILE:-1}"
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
PNPM_VERSION=${PNPM_VERSION}
NPM_REGISTRY=${NPM_REGISTRY}
NPM_FALLBACK_REGISTRY=${NPM_FALLBACK_REGISTRY}
MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL}
DOCKER_LOG_MAX_SIZE=${DOCKER_LOG_MAX_SIZE}
DOCKER_LOG_MAX_FILE=${DOCKER_LOG_MAX_FILE}
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

sync_repo() {
  if [[ -d "${APP_DIR}/.git" ]]; then
    log "Updating repository in ${APP_DIR}..."
  else
    if [[ -d "${APP_DIR}" && -n "$(find "${APP_DIR}" -mindepth 1 -maxdepth 1 2>/dev/null)" ]]; then
      die "${APP_DIR} exists and is not a Git checkout. Move it away first."
    fi
    log "Cloning ${REPO_URL} to ${APP_DIR}..."
    mkdir -p "$(dirname "${APP_DIR}")"
    git clone "${REPO_URL}" "${APP_DIR}"
  fi

  git -C "${APP_DIR}" fetch origin --prune --tags --force
  checkout_deploy_ref
  [[ -f "${APP_DIR}/${COMPOSE_FILE}" ]] || die "Missing ${COMPOSE_FILE} in ${APP_DIR}."
}

checkout_deploy_ref() {
  [[ -n "${DEPLOY_REF}" ]] || DEPLOY_REF="main"

  if git -C "${APP_DIR}" show-ref --verify --quiet "refs/remotes/origin/${DEPLOY_REF}"; then
    log "Deploying branch: ${DEPLOY_REF}"
    git -C "${APP_DIR}" checkout -B "${DEPLOY_REF}" "origin/${DEPLOY_REF}"
    git -C "${APP_DIR}" reset --hard "origin/${DEPLOY_REF}"
  elif git -C "${APP_DIR}" show-ref --verify --quiet "refs/tags/${DEPLOY_REF}"; then
    log "Deploying tag: ${DEPLOY_REF}"
    git -C "${APP_DIR}" checkout --detach -f "refs/tags/${DEPLOY_REF}"
  elif git -C "${APP_DIR}" rev-parse --verify --quiet "${DEPLOY_REF}^{commit}" >/dev/null; then
    log "Deploying commit: ${DEPLOY_REF}"
    git -C "${APP_DIR}" checkout --detach -f "${DEPLOY_REF}"
  else
    die "Git ref not found: ${DEPLOY_REF}. Use a branch, tag, or commit from ${REPO_URL}."
  fi

  local commit
  commit="$(git -C "${APP_DIR}" rev-parse --short HEAD)"
  log "Checked out commit: ${commit}"
}

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${APP_DIR}/${COMPOSE_FILE}" "$@"
}

install_cli_wrapper() {
  cat > "/usr/local/bin/${APP_SLUG}" <<EOF
#!/usr/bin/env bash
exec bash "${APP_DIR}/deploy.sh" "\$@"
EOF
  chmod +x "/usr/local/bin/${APP_SLUG}"
}

wait_for_health() {
  local container="$1"
  local timeout="${2:-180}"
  local elapsed=0
  local status=""

  while [[ "${elapsed}" -lt "${timeout}" ]]; do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container}" 2>/dev/null || true)"
    if [[ "${status}" == "healthy" || "${status}" == "running" ]]; then
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done

  warn "${container} did not become healthy within ${timeout}s. Current status: ${status:-unknown}"
  return 1
}

start_stack() {
  local recreate="${1:-0}"
  log "Building and starting containers in background..."
  if [[ "${recreate}" -eq 1 ]]; then
    compose build --pull
    compose up -d --force-recreate
  else
    compose up -d --build
  fi
  wait_for_health "${APP_SLUG}-mysql" 180 || true
  wait_for_health "${APP_SLUG}-backend" 240 || true
}

post_deploy_cleanup() {
  log "Cleaning unused Docker build cache and dangling images..."
  docker builder prune -af --filter "until=168h" >/dev/null 2>&1 || true
  docker image prune -f >/dev/null 2>&1 || true
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

  cat > "${NGINX_CONF}" <<EOF
map \$http_upgrade \$connection_upgrade_${APP_SLUG//-/_} {
    default upgrade;
    '' close;
}

server {
    listen 80;
    listen [::]:80;
    server_name ${server_name};

    client_max_body_size 100m;

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/${APP_SLUG};
        default_type "text/plain";
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

  cat > "${NGINX_CONF}" <<EOF
map \$http_upgrade \$connection_upgrade_${APP_SLUG//-/_} {
    default upgrade;
    '' close;
}

server {
    listen 80;
    listen [::]:80;
    server_name ${domain};

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
    listen [::]:443 ssl http2;
    server_name ${domain};

    ssl_certificate /etc/letsencrypt/live/${domain}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${domain}/privkey.pem;
    ssl_session_timeout 1d;
    ssl_session_cache shared:${APP_SLUG//-/_}_ssl:10m;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;

    client_max_body_size 100m;

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

domain_points_here() {
  local domain="$1"
  local current resolved ip
  current="$(public_ips)"
  resolved="$(domain_ips "${domain}")"

  [[ -n "${current}" && -n "${resolved}" ]] || return 1

  while read -r ip; do
    [[ -n "${ip}" ]] || continue
    if printf '%s\n' "${current}" | grep -Fxq "${ip}"; then
      return 0
    fi
  done <<< "${resolved}"
  return 1
}

verify_domain_dns() {
  local domain="$1"
  [[ -n "${domain}" ]] || return 1

  if domain_points_here "${domain}"; then
    log "DNS check passed for ${domain}."
    return 0
  fi

  warn "DNS for ${domain} does not appear to point to this VPS yet."
  warn "Current VPS public IPs:"
  public_ips | sed 's/^/  - /' || true
  warn "Resolved domain IPs:"
  domain_ips "${domain}" | sed 's/^/  - /' || true

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
        if domain_points_here "${domain}"; then
          log "DNS check passed for ${domain}."
          return 0
        fi
        warn "DNS is still not ready."
        ;;
    esac
  done
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

request_certificate() {
  local domain="$1"
  local email="$2"

  [[ -n "${domain}" ]] || return 1
  [[ "${SKIP_SSL}" -eq 0 ]] || return 1

  if ! verify_domain_dns "${domain}"; then
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
    if request_certificate "${DOMAIN}" "${LETSENCRYPT_EMAIL:-admin@${DOMAIN}}"; then
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
  detect_system
  install_packages
  install_docker
  configure_firewall
  collect_install_config
  sync_repo
  write_env_file
  install_cli_wrapper
  start_stack
  post_deploy_cleanup
  configure_nginx
  update_panel_address_in_db
  print_summary
}

update_flow() {
  detect_system
  install_packages
  install_docker
  load_env
  [[ -n "${DEPLOY_REF_ARG}" ]] && DEPLOY_REF="${DEPLOY_REF_ARG}"
  sync_repo
  set_env_value DEPLOY_REF "${DEPLOY_REF}"
  install_cli_wrapper
  start_stack 1
  post_deploy_cleanup
  configure_nginx
  update_panel_address_in_db
  print_summary
}

uninstall_flow() {
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

  if ! confirm "Clean unused containers, networks, dangling images, and build cache?"; then
    log "Cancelled."
    return 0
  fi

  docker container prune -f || true
  docker network prune -f || true
  docker image prune -f || true
  docker builder prune -af --filter "until=24h" || true

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
    uninstall) uninstall_flow ;;
    status) status_flow ;;
    logs) logs_flow ;;
    cleanup) cleanup_flow ;;
    renew-cert) renew_cert_flow ;;
    menu) menu_loop ;;
    *) usage; exit 1 ;;
  esac
}

main "$@"
