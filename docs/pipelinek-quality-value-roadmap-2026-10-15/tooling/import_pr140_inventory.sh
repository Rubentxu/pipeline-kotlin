#!/usr/bin/env bash
# Fetches *an audit branch* as a reference; does not merge or modify main.
set -euo pipefail
repo=${1:-.}
out=${2:-/tmp/pipelinek-pr140-uat-e2e.csv}
branch=audit/uat-e2e-retirement-inventory-20261015
path=v2/test-harness/inventory/uat-e2e-retirement.csv
if [[ ! -d "$repo/.git" ]]; then echo 'Expected PipelineK checkout' >&2; exit 2; fi
git -C "$repo" fetch --no-tags origin "refs/heads/$branch:refs/remotes/origin/$branch"
mkdir -p "$(dirname "$out")"
git -C "$repo" show "refs/remotes/origin/$branch:$path" > "$out"
echo "Imported $(($(wc -l < "$out") - 1)) rows into $out; DO NOT delete from these rows automatically"
