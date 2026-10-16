#!/usr/bin/env bash
# Isolated regression checks; never invokes Docker, GitHub, or system services.
# Run: bash scripts/test-panel-auto-update.sh [path/to/deploy.sh]
set -uo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_SCRIPT="${1:-${ROOT_DIR}/deploy.sh}"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/flux-panel-update-tests.XXXXXX")"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT
OLD_SHA=1111111111111111111111111111111111111111
NEW_SHA=abcdefabcdefabcdefabcdefabcdefabcdefabcd
REQUEST_ID=12345678-1234-1234-1234-123456789abc
NEXT_ID=87654321-4321-4321-4321-cba987654321
passed=0
failed=0

# Extract only the requested function, so sourcing cannot execute main, traps,
# host discovery, or deployment defaults. Handles CRLF checkouts and () bodies.
source_function() {
  local name="$1" definition
  definition="$(awk -v name="${name}" '
    { sub(/\r$/, "") }
    $0 ~ "^" name "\\(\\)[[:space:]]*[{(]" { active = 1 }
    active { print }
    active && /^[})][[:space:]]*$/ { exit }
  ' "${DEPLOY_SCRIPT}")"
  [[ -n "${definition}" ]] || { printf 'Missing function: %s\n' "${name}" >&2; return 1; }
  source <(printf '%s\n' "${definition}")
}

for function_name in ensure_update_state_dir acquire_deploy_lock release_deploy_lock \
  json_value json_safe write_update_result consume_update_request auto_update_flow \
  auto_update_apply auto_update_cleanup_snapshot auto_update_rollback \
  start_stack update_flow update_flow_locked; do
  source_function "${function_name}" || exit 1
done

fail() { printf '  %s\n' "$*" >&2; return 1; }
assert_equal() { [[ "$1" == "$2" ]] || fail "${3:-Value mismatch}: expected '$1', got '$2'"; }
assert_absent() { [[ ! -e "$1" ]] || fail "Unexpected file remains: $1"; }
record() { printf '%s\n' "$1" >> "${EVENTS}"; }
count_event() { awk -v event="$1" '$0 == event { count++ } END { print count + 0 }' "${EVENTS}"; }
assert_count() { assert_equal "$2" "$(count_event "$1")" "Calls to $1"; }
expect_status() {
  local expected="$1" actual=0
  shift
  # Deliberately invoke in a condition: production auto_update_apply does too.
  # This catches accidental reliance on set -e in nested deployment functions.
  if "$@"; then actual=0; else actual=$?; fi
  assert_equal "${expected}" "${actual}" "Exit status of $1"
}
stage() { record "$1"; [[ "${FAIL_STAGE}" != "$1" ]] || return 41; }

prepare_case() {
  CASE_DIR="$(mktemp -d "${TEST_ROOT}/case.XXXXXX")"
  APP_DIR="${CASE_DIR}/app"
  UPDATE_STATE_DIR="${APP_DIR}/update-state"
  ENV_FILE="${APP_DIR}/.env"
  EVENTS="${CASE_DIR}/events"
  mkdir -p "${UPDATE_STATE_DIR}"
  : > "${UPDATE_STATE_DIR}/auto-update-enabled"
  printf '# Test fixture only\n' > "${ENV_FILE}"
  : > "${EVENTS}"
  APP_NAME=flux-panel-test
  APP_SLUG=flux-panel-test
  DEPLOY_LOCK_FD=""
  DEPLOY_REF=main
  DEPLOY_REF_ARG=""
  LAST_DEPLOYED_COMMIT="${OLD_SHA}"
  CURRENT_COMMIT="${NEW_SHA}"
  BUILD_SERVICES=(backend frontend)
  FAIL_STAGE=""
  APPLY_STATUS=0
  GITHUB_TOKEN=""
  AUTO_ROLLBACK_STACK_CHANGED=0

  log() { :; }
  warn() { :; }
  err() { record error; }
  die() { record die; exit 1; }
  load_env() { stage load_env; }
  detect_system() { stage detect_system; }
  install_packages() { stage install_packages; }
  install_docker() { stage install_docker; }
  sync_repo() { stage sync_repo; }
  set_env_value() { stage "env:$1"; }
  sync_build_env_values() { stage sync_build_env_values; }
  install_cli_wrapper() { stage install_cli_wrapper; }
  install_update_timer() { stage install_update_timer; }
  ensure_runtime_ports() { stage ensure_runtime_ports; }
  determine_update_build_services() { stage determine_update_build_services; }
  build_panel_services() { stage build_panel_services; }
  compose_up_with_port_retry() { stage compose_up; }
  wait_for_health() { stage "health:$1"; }
  mark_deployed_commit() { stage mark_deployed_commit; }
  post_deploy_cleanup() { stage post_deploy_cleanup; }
  configure_nginx() { stage configure_nginx; }
  update_panel_address_in_db() { stage update_panel_address_in_db; }
  print_summary() { stage print_summary; }
  auto_update_apply() {
    record apply
    printf '%s\n' "$1" "$2" "$3" "${GITHUB_TOKEN:-}" > "${CASE_DIR}/apply-args"
    return "${APPLY_STATUS}"
  }

  # Git Bash has no flock; an atomic temporary directory models contention.
  # The production acquire/release functions and descriptor handling stay real.
  flock() {
    case "$1" in
      -n) record lock_acquire; mkdir "${UPDATE_STATE_DIR}/mock-lock" 2>/dev/null ;;
      -u) record lock_release; rmdir "${UPDATE_STATE_DIR}/mock-lock" 2>/dev/null ;;
      *) fail "Unexpected flock arguments: $*" ;;
    esac
  }
  rm() {
    local path
    for path in "$@"; do
      case "${path}" in
        "${UPDATE_STATE_DIR}/github-token") record remove_token ;;
        "${UPDATE_STATE_DIR}/request.json") record remove_request ;;
      esac
    done
    command rm "$@"
  }
  # Fail closed if a missed mock would reach a real external operation.
  docker() { fail 'Unexpected Docker invocation'; return 97; }
  systemctl() { fail 'Unexpected service invocation'; return 97; }
  git() { fail 'Unexpected Git invocation'; return 97; }
  curl() { fail 'Unexpected network invocation'; return 97; }
  wget() { fail 'Unexpected network invocation'; return 97; }
}

write_request() {
  printf '{"requestId":"%s","tag":"%s","commit":"%s"}\n' \
    "${1-${REQUEST_ID}}" "${2-main}" "${3-${NEW_SHA}}" > "${UPDATE_STATE_DIR}/request.json"
  printf 'fixture-token' > "${UPDATE_STATE_DIR}/github-token"
}
assert_result() { assert_equal "$1" "$(json_value status "${UPDATE_STATE_DIR}/result.json")" 'Update result'; }
assert_consumed() {
  assert_absent "${UPDATE_STATE_DIR}/request.json"
  assert_absent "${UPDATE_STATE_DIR}/github-token"
  assert_equal $'remove_token\nremove_request' "$(awk '/^remove_(token|request)$/ { print }' "${EVENTS}")" 'Cleanup ordering'
}

test_no_request() {
  expect_status 0 auto_update_flow
  assert_count apply 0
  assert_absent "${UPDATE_STATE_DIR}/result.json"
  assert_equal '' "${DEPLOY_LOCK_FD}" 'Lock release'
}
test_disabled_request_is_discarded_without_deployment() {
  mv "${UPDATE_STATE_DIR}/auto-update-enabled" "${CASE_DIR}/disabled-marker"
  write_request
  expect_status 0 auto_update_flow
  assert_count apply 0
  assert_absent "${UPDATE_STATE_DIR}/request.json"
  assert_absent "${UPDATE_STATE_DIR}/github-token"
}
test_same_commit() {
  write_request "${REQUEST_ID}" main "${NEW_SHA^^}"
  LAST_DEPLOYED_COMMIT="${NEW_SHA}"
  expect_status 0 auto_update_flow
  assert_count apply 0
  assert_result success
  assert_consumed
}
test_new_commit() {
  write_request
  expect_status 0 auto_update_flow
  expect_status 0 auto_update_flow
  assert_count apply 1
  assert_equal "$(printf '%s\n' "${REQUEST_ID}" main "${NEW_SHA}" fixture-token)" "$(<"${CASE_DIR}/apply-args")" 'Pinned update arguments'
  assert_result success
  assert_consumed
  assert_equal '' "${GITHUB_TOKEN:-}" 'In-memory token cleanup'
}
test_failed_apply() {
  write_request
  APPLY_STATUS=41
  expect_status 1 auto_update_flow
  expect_status 0 auto_update_flow
  assert_count apply 1
  assert_result failed
  assert_consumed
  assert_equal '' "${GITHUB_TOKEN:-}" 'In-memory token cleanup'
}
test_completed_request() {
  write_request
  write_update_result "${REQUEST_ID}" "$1" 'Already handled'
  expect_status 0 auto_update_flow
  assert_count apply 0
  assert_result "$1"
  assert_consumed
}
test_invalid_request() {
  write_request "$1" "$2" "$3"
  expect_status 1 auto_update_flow
  expect_status 0 auto_update_flow
  assert_count apply 0
  assert_result failed
  assert_consumed
}
test_newer_request_preserved() {
  write_request "${NEXT_ID}"
  consume_update_request "${REQUEST_ID}"
  assert_equal "${NEXT_ID}" "$(json_value requestId "${UPDATE_STATE_DIR}/request.json")" 'Newer request preserved'
  assert_equal fixture-token "$(<"${UPDATE_STATE_DIR}/github-token")" 'Newer token preserved'
  assert_count remove_token 0
  assert_count remove_request 0
}
test_update_success() {
  expect_status 0 update_flow
  assert_count build_panel_services 1
  assert_count compose_up 1
  assert_equal 1 "${AUTO_ROLLBACK_STACK_CHANGED}" 'Stack change marker'
  assert_count mark_deployed_commit 1
  assert_count configure_nginx 1
  assert_equal '' "${DEPLOY_LOCK_FD}" 'Lock release'
}
test_update_stage_failure() {
  FAIL_STAGE="$1"
  expect_status 41 update_flow
  assert_count "$1" 1
  assert_count compose_up 0
  assert_count mark_deployed_commit 0
  assert_count configure_nginx 0
  assert_equal '' "${DEPLOY_LOCK_FD}" 'Failure lock release'
}
test_all_build_failure() {
  FAIL_STAGE=build_panel_services
  expect_status 41 start_stack
  assert_count compose_up 0
}
test_start_failure() {
  FAIL_STAGE=compose_up
  expect_status 41 update_flow
  assert_count "health:${APP_SLUG}-mysql" 0
  assert_count mark_deployed_commit 0
}
test_health_failure() {
  FAIL_STAGE="health:${APP_SLUG}-$1"
  expect_status 1 isolated_update
  assert_count "${FAIL_STAGE}" 1
  assert_count compose_up 1
  assert_count mark_deployed_commit 0
  assert_count configure_nginx 0
  [[ "$1" == frontend ]] || assert_count "health:${APP_SLUG}-frontend" 0
}
isolated_update() ( update_flow; )
test_lock_contention() {
  write_request
  acquire_deploy_lock
  expect_status 1 competing_update
  assert_count apply 0
  release_deploy_lock
  expect_status 0 auto_update_flow
  assert_count apply 1
}
competing_update() ( DEPLOY_LOCK_FD=''; auto_update_flow; )
test_inherited_lock() {
  acquire_deploy_lock
  expect_status 0 update_flow
  assert_count lock_acquire 1
  assert_count lock_release 0
  release_deploy_lock
}
test_apply_rolls_back() {
  source_function auto_update_apply
  git() { printf '%s\n' "${OLD_SHA}"; }
  docker() {
    case "$1" in
      image) printf 'sha256:fixture-image\n' ;;
      tag) record snapshot_image ;;
      *) return 97 ;;
    esac
  }
  update_flow() { record failing_update; return 41; }
  auto_update_rollback() { record rollback; exit "$1"; }
  expect_status 41 auto_update_apply "${REQUEST_ID}" main "${NEW_SHA}"
  assert_count failing_update 1
  assert_count rollback 1
  assert_count env:DEPLOY_REF 0
}
test_apply_failure_before_stack_change_keeps_containers() {
  source_function auto_update_rollback
  AUTO_ROLLBACK_ACTIVE=1
  AUTO_ROLLBACK_STACK_CHANGED=0
  AUTO_ROLLBACK_COMMIT="${OLD_SHA}"
  AUTO_ROLLBACK_BACKEND_ID=fixture-backend
  AUTO_ROLLBACK_FRONTEND_ID=fixture-frontend
  AUTO_ROLLBACK_BACKEND_TAG=flux-panel-test-backend:rollback
  AUTO_ROLLBACK_FRONTEND_TAG=flux-panel-test-frontend:rollback
  AUTO_ROLLBACK_ENV_FILE="${CASE_DIR}/rollback.env"
  printf 'fixture env\n' > "${AUTO_ROLLBACK_ENV_FILE}"
  git() {
    case "$*" in
      *checkout*) record checkout ;;
      *) return 0 ;;
    esac
  }
  docker() {
    case "$1" in
      image)
        case "$2" in
          inspect) printf 'sha256:fixture-image\n' ;;
          rm) record cleanup_image ;;
          *) fail "Unexpected Docker image invocation: $*" ;;
        esac
        ;;
      tag) record snapshot_image ;;
      *) fail "Unexpected Docker invocation: $*" ;;
    esac
  }
  load_env() { record load_env; }
  local rollback_status=0 rollback_output
  if rollback_output="$(auto_update_rollback 41 2>&1)"; then rollback_status=0; else rollback_status=$?; fi
  assert_equal 41 "${rollback_status}" 'Rollback exit status'
  assert_count compose_up 0
  assert_count cleanup_image 2
  assert_count checkout 1
}
test_normal_dependency_returns() {
  source_function sync_build_env_values
  source_function ensure_runtime_ports
  source_function determine_update_build_services
  mkdir -p "${APP_DIR}/vite-frontend"
  printf '{\n  "version":"1.3.99"\n}\n' > "${APP_DIR}/vite-frontend/package.json"
  AWS_ENCRYPTION_KEY=fixture
  NPM_REGISTRY=https://registry.npmjs.org
  NPM_FALLBACK_REGISTRY=https://registry.yarnpkg.com
  MAVEN_MIRROR_URL=''
  COMPOSE_PROFILES=''
  ensure_runtime_port() { record "port:$1"; }
  image_exists() { return 0; }
  commit_exists() { return 0; }
  git() { return 0; }
  expect_status 0 sync_build_env_values
  expect_status 0 ensure_runtime_ports
  assert_count port:PHPMYADMIN_PORT 0
  expect_status 0 determine_update_build_services
  assert_equal 0 "${#BUILD_SERVICES[@]}" 'No changed images'
  CURRENT_COMMIT="${LAST_DEPLOYED_COMMIT}"
  expect_status 0 determine_update_build_services
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

run_test 'No request performs no deployment' test_no_request
run_test 'Disabled update request is discarded without deployment' test_disabled_request_is_discarded_without_deployment
run_test 'Same SHA is consumed without deployment (case-insensitive)' test_same_commit
run_test 'New SHA is pinned and applied exactly once across timer ticks' test_new_commit
run_test 'Failed application is consumed and never loops' test_failed_apply
run_test 'Completed success request is not repeated' test_completed_request success
run_test 'Completed failure request is not repeated' test_completed_request failed
run_test 'Missing UUID is rejected and consumed' test_invalid_request '' main "${NEW_SHA}"
run_test 'Malformed UUID is rejected and consumed' test_invalid_request '../escape' main "${NEW_SHA}"
run_test 'Non-main target is rejected and consumed' test_invalid_request "${REQUEST_ID}" feature "${NEW_SHA}"
run_test 'Missing SHA is rejected and consumed' test_invalid_request "${REQUEST_ID}" main ''
run_test 'Short SHA is rejected and consumed' test_invalid_request "${REQUEST_ID}" main abcdef
run_test 'Non-hex SHA is rejected and consumed' test_invalid_request "${REQUEST_ID}" main zbcdefabcdefabcdefabcdefabcdefabcdefabcd
run_test 'Cleanup never deletes a replacement request or credential' test_newer_request_preserved
run_test 'Successful update starts services and records deployment' test_update_success
for stage_name in detect_system install_packages install_docker load_env sync_repo env:DEPLOY_REF \
  sync_build_env_values install_cli_wrapper install_update_timer ensure_runtime_ports \
  determine_update_build_services build_panel_services; do
  run_test "Failure stops update at ${stage_name}" test_update_stage_failure "${stage_name}"
done
run_test 'Full-build failure never starts containers' test_all_build_failure
run_test 'Container startup failure never records deployment' test_start_failure
for service_name in mysql backend frontend; do
  run_test "Unhealthy ${service_name} never records success" test_health_failure "${service_name}"
done
run_test 'Competing update is blocked until lock is released (mock flock)' test_lock_contention
run_test 'Nested update preserves its caller lock' test_inherited_lock
run_test 'Failed update inside apply invokes rollback despite conditional context' test_apply_rolls_back
run_test 'Failure before container startup leaves the current stack running' test_apply_failure_before_stack_change_keeps_containers
run_test 'Unchanged environment/ports/images return success normally' test_normal_dependency_returns

printf '\nResult: %s passed, %s failed\n' "${passed}" "${failed}"
[[ "${failed}" -eq 0 ]]
