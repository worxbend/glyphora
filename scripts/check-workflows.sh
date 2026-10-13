#!/usr/bin/env bash
# Verify a fixed actionlint release before executing it; no mutable installer or action.
set -euo pipefail
version=1.7.12
checksum=8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8
work=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:?set TMPDIR}}/glyphora-actionlint.XXXXXX")
trap 'rm -rf "$work"' EXIT
curl --fail --silent --show-error --location --retry 3 --max-time 120 \
  -o "$work/actionlint.tar.gz" \
  "https://github.com/rhysd/actionlint/releases/download/v${version}/actionlint_${version}_linux_amd64.tar.gz"
printf '%s  %s\n' "$checksum" "$work/actionlint.tar.gz" | sha256sum --check --strict
tar -xzf "$work/actionlint.tar.gz" -C "$work" actionlint
# ShellCheck is deliberately disabled: use the same checks locally and in CI,
# irrespective of which optional executables happen to be installed on the runner.
"$work/actionlint" -shellcheck= -pyflakes=
python3 scripts/validate-workflows.py
