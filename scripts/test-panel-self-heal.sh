#!/usr/bin/env bash
# Isolated self-healing regression checks; never invokes Docker or systemd.
# Run: bash scripts/test-panel-self-heal.sh [path/to/deploy.sh]
set -uo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_SCRIPT="${1:-${ROOT_DIR}/deploy.sh}"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/flux-panel-self-heal-tests.XXXXXX")"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT

# Loading deploy.sh is safe because its main entry point is guarded.
# shellcheck disable=SC1090
source "${DEPLOY_SCRIPT}"

passed=0
failed=0

fail() { printf '  %s\n' "$*" >&2; return 1; }
assert_equal() { [[ "$1" == "$2" ]] || fail "${3:-Value mismatch}: expected '$1', got '$2'"; }
assert_count() {
  local expected="$1" event="$2"
  assert_equal "${expected}" "$(grep -Fxc -- "${event}" "${EVENTS}" 2>/dev/null || true)" "Calls to ${event}"
}

prepare_case() {
  CASE_DIR="$(mktemp -d "${TEST_ROOT}/case.XXXXXX")"
  APP_DIR="${CASE_DIR}/app"
  UPDATE_STATE_DIR="${APP_DIR}/update-state"
  ENV_FILE="${APP_DIR}/.env"
  EVENTS="${CASE_DIR}/events"
  mkdir -p "${UPDATE_STATE_DIR}"
  : > "${APP_DIR}/${COMPOSE_FILE}"
  printf 'FRONTEND_PORT=6366\nBACKEND_PORT=6365\nDOMAIN=\nSSL_ENABLED=false\n' > "${ENV_FILE}"
  : > "${EVENTS}"
  APP_SLUG=flux-panel-test
  COMPOSE_FILE=docker-compose.deploy.yml
  FRONTEND_PORT=6366
  BACKEND_PORT=6365
  DOMAIN=""
  PANEL_URL=http://127.0.0.1
  SELF_HEAL_FAILURE_THRESHOLD=3
  SELF_HEAL_REBOOT_COOLDOWN=1800
  DEPLOY_LOCK_FD=""
  CONTAINERS_HEALTHY=1
  HTTP_OK=1
  DOCKER_ACTIVE=1
  NGINX_ACTIVE=1
  NGINX_CONFIG_OK=1

  log() { record "log:$*"; }
  warn() { record "warn:$*"; }
  err() { record "err:$*"; }
  record() { printf '%s\n' "$*" >> "${EVENTS}"; }
  try_acquire_deploy_lock() { record acquire_lock; return 0; }
  release_deploy_lock() { record release_lock; DEPLOY_LOCK_FD=""; }

  systemctl() {
    case "$*" in
      *"is-active"*docker*) [[ "${DOCKER_ACTIVE}" -eq 1 ]] ;;
      *"is-active"*nginx*) [[ "${NGINX_ACTIVE}" -eq 1 ]] ;;
      *"restart docker"*) record restart_docker; DOCKER_ACTIVE=1 ;;
      *"reload nginx"*) record reload_nginx; [[ "${NGINX_ACTIVE}" -eq 1 ]] ;;
      *"restart nginx"*) record restart_nginx; NGINX_ACTIVE=1 ;;
      *"reboot"*) record reboot ;;
      *) return 0 ;;
    esac
  }

  docker() {
    case "$1" in
      inspect)
        if [[ "${CONTAINERS_HEALTHY}" -eq 1 ]]; then
          [[ "${3:-}" == *Status* ]] && printf 'healthy\n' || printf 'yes\n'
        else
          [[ "${3:-}" == *Status* ]] && printf 'unhealthy\n' || printf 'yes\n'
        fi
        ;;
      restart) record "restart:$*"; CONTAINERS_HEALTHY=1 ;;
      *) fail "Unexpected docker invocation: $*"; return 97 ;;
    esac
  }

  compose() { record "compose:$*"; return 0; }
  curl() { [[ "${HTTP_OK}" -eq 1 ]]; }
  nginx() { [[ "${NGINX_CONFIG_OK}" -eq 1 ]]; }
  wait_for_health() { record "health:$1"; return 0; }
}

run_test() {
  local name="$1" status
  shift
  ( set -e; prepare_case; "$@" )
  status=$?
  if [[ "${status}" -eq 0 ]]; then
    passed=$((passed + 1))
    printf 'PASS %s\n' "${name}"
  else
    failed=$((failed + 1))
    printf 'FAIL %s (exit %s)\n' "${name}" "${status}"
  fi
}

test_healthy_no_action() {
  self_heal_flow
  assert_count 0 compose:
  assert_count 0 reboot
  assert_equal 0 "${SELF_HEAL_FAILURES}" 'Healthy failure counter'
}

test_single_failure_waits() {
  HTTP_OK=0
  self_heal_flow
  assert_count 0 compose:
  assert_count 0 reboot
  assert_equal 1 "${SELF_HEAL_FAILURES}" 'Single failure counter'
}

test_three_failures_reboot() {
  HTTP_OK=0
  self_heal_flow
  self_heal_flow
  self_heal_flow
  assert_count 1 'compose:up -d --no-build --remove-orphans'
  assert_count 1 reboot
  assert_equal 3 "${SELF_HEAL_FAILURES}" 'Failure counter before reboot'
}

test_nginx_recovery() {
  SELF_HEAL_FAILURE_THRESHOLD=1
  NGINX_ACTIVE=0
  self_heal_flow
  assert_count 1 'compose:up -d --no-build --remove-orphans'
  assert_count 1 restart_nginx
  assert_equal 0 "${SELF_HEAL_FAILURES}" 'Nginx recovery counter'
  assert_count 0 reboot
}

test_recovery_resets_counter() {
  HTTP_OK=0
  self_heal_flow
  self_heal_flow
  HTTP_OK=1
  self_heal_flow
  assert_equal 0 "${SELF_HEAL_FAILURES}" 'Recovered failure counter'
  assert_count 0 reboot
}

test_deploy_lock_skips_repair() {
  HTTP_OK=0
  SELF_HEAL_FAILURE_THRESHOLD=1
  try_acquire_deploy_lock() { return 1; }
  self_heal_flow
  assert_count 0 compose:
  assert_count 0 reboot
}

test_reboot_cooldown() {
  HTTP_OK=0
  SELF_HEAL_FAILURE_THRESHOLD=1
  SELF_HEAL_LAST_REBOOT="$(date +%s)"
  self_heal_write_state
  self_heal_flow
  assert_count 0 reboot
}

run_test 'Healthy stack performs no repair' test_healthy_no_action
run_test 'One failed probe waits for the threshold' test_single_failure_waits
run_test 'Three failures repair and reboot when still unhealthy' test_three_failures_reboot
run_test 'Nginx failure is repaired without VPS reboot' test_nginx_recovery
run_test 'Recovered probe resets the failure counter' test_recovery_resets_counter
run_test 'Deployment lock defers self-healing' test_deploy_lock_skips_repair
run_test 'VPS reboot cooldown suppresses repeated reboot' test_reboot_cooldown

printf '\nResult: %s passed, %s failed\n' "${passed}" "${failed}"
[[ "${failed}" -eq 0 ]]
