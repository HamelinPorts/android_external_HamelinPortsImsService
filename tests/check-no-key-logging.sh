#!/bin/bash
# Fails if a log statement under src/ or jni/ can carry AKA key material:
# RES/CK/IK, the RAND/AUTN challenge, digest internals, hex or Base64 dumps.
# A log call is checked as a whole statement, continuation lines included.
#   usage: check-no-key-logging.sh [tree-root]   (default: this repository)
set -euo pipefail

root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$root"

log_call='(Log[.][a-z]|LOG[IWED])[(]'
key_material='hex\(|Base64|\b(res|ck|ik|rand|autn|nonce|ha1|kc|auts)\b *[=(+]|data='

statements="$(find src jni -type f \( -name '*.java' -o -name '*.cpp' -o -name '*.h' \) -print0 \
    | sort -z \
    | xargs -0 awk -v call="$log_call" '
        FNR == 1 { inlog = 0 }
        !inlog && $0 ~ call { inlog = 1; stmt = ""; start = FNR }
        inlog {
            stmt = stmt " " $0
            if ($0 ~ /\);/) { print FILENAME ":" start ":" stmt; inlog = 0 }
        }')"
[ -n "$statements" ] || { echo "FAIL: no log statements found under $root — gate cannot see the code" >&2; exit 2; }

key_hits="$(printf '%s\n' "$statements" | /bin/grep -iE "$key_material" || true)"
hex_hits="$(/bin/grep -rn 'AkaHelper[.]hex' src || true)"

n_stmt="$(printf '%s\n' "$statements" | wc -l)"
if [ -n "$key_hits" ] || [ -n "$hex_hits" ]; then
    echo "FAIL: key material may reach the log ($n_stmt log statements scanned)"
    [ -z "$key_hits" ] || printf '%s\n' "$key_hits"
    [ -z "$hex_hits" ] || printf '%s\n' "$hex_hits"
    exit 1
fi
echo "PASS: $n_stmt log statements scanned, none can carry key material"
