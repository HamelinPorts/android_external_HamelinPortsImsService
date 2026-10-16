#!/bin/bash
# Fails if the app's code (src/ and jni/) can bind the whole process to a
# network. Every SIP socket is pinned on its own; a process binding would send
# every other socket of both slots over one slot's network.
# Any mention of a process-binding API counts: a call with any spacing, a
# method reference, a string or a comment.
#   usage: check-process-binding.sh [tree-root]   (default: this repository)
set -euo pipefail

root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$root"

for dir in src jni; do
    [ -d "$dir" ] || { echo "FAIL: no $dir/ under $root — gate cannot see the code" >&2; exit 2; }
done
n_files="$(find src jni -type f \( -name '*.java' -o -name '*.cpp' -o -name '*.h' \) | wc -l)"
[ "$n_files" -gt 0 ] || { echo "FAIL: no sources under $root — gate cannot see the code" >&2; exit 2; }

process_binding_apis='bindProcessToNetwork|setProcessDefaultNetwork|android_setprocnetwork|android_setprocdns|setNetworkForProcess'
hits="$(/bin/grep -rnwE "$process_binding_apis" src jni || true)"

if [ -n "$hits" ]; then
    echo "FAIL: process network binding in the app"
    printf '%s\n' "$hits"
    exit 1
fi
echo "PASS: no process network binding in $n_files files under src/ and jni/"
