#!/usr/bin/env bash
# Helper sourced by CI jobs and device-tests.sh to execute steps without aborting early
# and record structured TSV outcomes in ci-status/<id>.tsv + logs in ci-logs/<id>.log.

CI_LOG_DIR="${CI_LOG_DIR:-ci-logs}"
CI_STATUS_DIR="${CI_STATUS_DIR:-ci-status}"
mkdir -p "$CI_LOG_DIR" "$CI_STATUS_DIR"

ci_record() {
  local id="$1"
  local status="$2"
  local desc="$3"
  local rc="${4:-0}"
  mkdir -p "$CI_STATUS_DIR"
  # Sanitize tabs/newlines in description
  local clean_desc
  clean_desc=$(printf '%s' "$desc" | tr '\t\r\n' '   ')
  printf '%s\t%s\t%s\t%s\n' "$id" "$status" "$rc" "$clean_desc" > "$CI_STATUS_DIR/$id.tsv"
  echo "[ci_record] $id: $status (rc=$rc) - $clean_desc"
}

ci_step() {
  local id="$1"
  local desc="$2"
  shift 2
  mkdir -p "$CI_LOG_DIR" "$CI_STATUS_DIR"
  local log_file="$CI_LOG_DIR/$id.log"

  echo "================================================================================"
  echo "[ci_step] Starting '$id': $desc"
  echo "Command: $*"
  echo "================================================================================"

  set +e
  "$@" 2>&1 | tee "$log_file"
  local rc="${PIPESTATUS[0]}"
  set -e

  local status="PASS"
  if [ "$rc" -ne 0 ]; then
    status="FAIL"
  elif [ -n "${CI_FAIL_IF_LOG_MATCHES:-}" ] && grep -Eq "$CI_FAIL_IF_LOG_MATCHES" "$log_file"; then
    status="FAIL"
    rc=1
  fi

  ci_record "$id" "$status" "$desc" "$rc"
  return 0
}
