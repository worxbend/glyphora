#!/usr/bin/env bash
# Git receives credentials only on demand, never via a URL, argv or persistent config.
set -euo pipefail
case "${1:-}" in
  *Username*) printf '%s\n' x-access-token ;;
  *Password*) printf '%s\n' "${GLYPHORA_GIT_TOKEN:?Git token is not configured}" ;;
  *) exit 1 ;;
esac
