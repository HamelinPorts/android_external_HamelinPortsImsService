#!/bin/bash
# Fails if code under src/ binds or clears the process network binding outside
# the owner-aware helper: a slot clearing it would unbind the network another
# slot bound. Only net/ConnectivityProcessBinding.java may call
# bindProcessToNetwork (reached through ProcessNetworkOwner).
#   usage: check-process-binding.sh [tree-root]   (default: this repository)
set -euo pipefail

root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$root"

helper='src/org/hamelinports/ims/net/ConnectivityProcessBinding.java'

[ -d src ] || { echo "FAIL: no src/ under $root — gate cannot see the code" >&2; exit 2; }

clear_hits="$(/bin/grep -rnE 'bindProcessToNetwork[(][[:space:]]*null[[:space:]]*[)]' src \
    | /bin/grep -v "^$helper:" || true)"
call_hits="$(/bin/grep -rnE 'bindProcessToNetwork[(]|setProcessDefaultNetwork[(]' src \
    | /bin/grep -v "^$helper:" || true)"
in_helper="$(/bin/grep -cE 'bindProcessToNetwork[(]null[)]' "$helper" 2>/dev/null || true)"

if [ -n "$clear_hits" ] || [ -n "$call_hits" ]; then
    echo "FAIL: process network binding touched outside $helper"
    [ -z "$call_hits" ] || printf '%s\n' "$call_hits"
    exit 1
fi
if [ "${in_helper:-0}" != 1 ]; then
    echo "FAIL: expected exactly one clear in $helper, found ${in_helper:-0}"
    exit 1
fi
echo "PASS: bindProcessToNetwork only in $helper (one clear there)"
