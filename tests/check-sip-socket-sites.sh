#!/bin/bash
# Fails unless every socket(), accept(), socketpair() and connect() call site in
# the reSIProcate sources this app compiles, and in jni/, is listed in
# tests/sip-socket-sites.txt with its status. A "hooked" site must show its
# evidence (the socket-function call): within WINDOW lines, or, written as
# @<path>@<regex>, in that file of the same tree. Any other status needs a
# reason. A new, changed or unlisted site fails, and so does a stale entry.
# The structural checks below assert that the socket
# function reaches every socket the stack creates and that the connect() guard
# is linked into the JNI library.
#   usage: check-sip-socket-sites.sh [--list] [app-root] [resiprocate-root]
set -euo pipefail

list=false
[ "${1:-}" = --list ] && { list=true; shift; }
app="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
resip="${2:-$app/../../../external/resiprocate}"
sites="$app/tests/sip-socket-sites.txt"
WINDOW=30
G=/bin/grep

[ -d "$app/jni" ] || { echo "FAIL: no jni/ under $app — gate cannot see the code" >&2; exit 2; }
[ -d "$resip/resip/stack" ] || { echo "FAIL: no resip/stack/ under $resip — gate cannot see the code" >&2; exit 2; }

# The compiled sources: external/resiprocate/Android.bp srcs minus exclude_srcs.
compiled() {
    (cd "$resip" && ls rutil/*.cxx rutil/dns/*.cxx rutil/stun/*.cxx rutil/hep/*.cxx rutil/ssl/*.cxx \
        resip/stack/*.cxx resip/stack/gen/*.cxx resip/stack/ssl/*.cxx resip/dum/*.cxx resip/dum/ssl/*.cxx 2>/dev/null) \
        | $G -v -E '^rutil/(WinCompat|Plugin|ProtonThreadBase|PyExtensionBase)\.cxx$|^rutil/dns/LocalDns\.cxx$|^resip/stack/(makeCert|ssl/MacSecurity|ssl/WinSecurity)\.cxx$' \
        | sed 's#^#resip:#'
    (cd "$app" && ls jni/*.cpp jni/*.h) | sed 's#^#app:#'
}

call_re='(^|[^A-Za-z0-9_>.])(::)?(socket|accept|accept4|socketpair|connect)[[:space:]]*[(]'

# One line per call site: <tree:path> TAB <occurrence of this text in the file> TAB <line> TAB <text>
found="$(compiled | while read -r ref; do
    tree=${ref%%:*}; path=${ref#*:}
    base=$([ "$tree" = resip ] && echo "$resip" || echo "$app")
    awk -v ref="$ref" -v re="$call_re" '
        { line = $0; sub(/^[ \t]+/, "", line); sub(/[ \t]+$/, "", line) }
        line ~ /^(\/\/|\*|\/\*)/ { next }
        $0 ~ re { n[line]++; printf "%s\t%d\t%d\t%s\n", ref, n[line], FNR, line }
    ' "$base/$path"
done)"
[ -n "$found" ] || { echo "FAIL: no call sites found — gate cannot see the code" >&2; exit 2; }

if $list; then
    printf '%s\n' "$found"
    exit 0
fi

fail=0
n_sites=0
while IFS=$'\t' read -r ref occ lineno text; do
    n_sites=$((n_sites + 1))
    entry="$(awk -F'\t' -v ref="$ref" -v occ="$occ" -v text="$text" \
        '$1 == ref && $2 == occ && $5 == text' "$sites")"
    if [ -z "$entry" ]; then
        echo "FAIL: unlisted call site $ref:$lineno: $text"
        fail=1
        continue
    fi
    status="$(printf '%s' "$entry" | cut -f3)"
    evidence="$(printf '%s' "$entry" | cut -f4)"
    if [ -z "$evidence" ]; then
        echo "FAIL: $ref:$lineno has no evidence or reason in the allowlist: $text"
        fail=1
    elif [ "$status" = hooked ]; then
        tree=${ref%%:*}; path=${ref#*:}
        base=$([ "$tree" = resip ] && echo "$resip" || echo "$app")
        case "$evidence" in
        @*@*)
            other=${evidence#@}; other=${other%%@*}; regex=${evidence#@*@}
            if ! $G -q -E "$regex" "$base/$other"; then
                echo "FAIL: $ref:$lineno is listed as hooked but '$regex' is not in $other: $text"
                fail=1
            fi ;;
        *)
            from=$((lineno > WINDOW ? lineno - WINDOW : 1))
            if ! sed -n "${from},$((lineno + WINDOW))p" "$base/$path" | $G -q -E "$evidence"; then
                echo "FAIL: $ref:$lineno is listed as hooked but '$evidence' is not within $WINDOW lines: $text"
                fail=1
            fi ;;
        esac
    fi
done <<< "$found"

# Listed sites that no longer exist are stale: the allowlist must follow the code.
while IFS=$'\t' read -r ref occ status evidence text; do
    case "$ref" in ''|'#'*) continue ;; esac
    if ! printf '%s\n' "$found" | awk -F'\t' -v ref="$ref" -v occ="$occ" -v text="$text" \
            'BEGIN { f = 0 } $1 == ref && $2 == occ && $4 == text { f = 1 } END { exit !f }'; then
        echo "FAIL: stale allowlist entry (site gone or changed): $ref #$occ: $text"
        fail=1
    fi
done < "$sites"

# Structural checks: <file> <extended regex> <what it proves>.
require() {
    if ! $G -q -E "$2" "$1"; then
        echo "FAIL: $3 (no '$2' in ${1#$app/})"
        fail=1
    fi
}
require "$resip/resip/stack/TransportSelector.cxx" 'mSocketFunc\(mSocket6s\[netNs\], UDP' \
    "the IPv6 route probe does not reach the socket function"
require "$resip/resip/stack/TransportSelector.cxx" 'mSocketFunc\(mSockets\[netNs\], UDP' \
    "the IPv4 route probe does not reach the socket function"
require "$resip/resip/stack/SipStack.cxx" 'transportSelector\(\)\.setSocketFunc\(socketFunc\)' \
    "SipStack does not give the transport selector its socket function"
require "$resip/rutil/dns/AresDns.cxx" 'ares_set_socket_configure_callback\(mChannel, &AresDns::configureSocket' \
    "c-ares sockets do not reach the socket function before connect()"
require "$app/jni/hamelinports_ims_jni.cpp" 'hamelinports::markSocketPinned\(fd\)' \
    "the hook does not report pinned sockets to the connect() guard"
jni_block="$(awk '/^cc_library_shared \{/ { b = 1 } b { print } b && /^\}/ { exit }' "$app/Android.bp")"
for flag in -Wl,--wrap=socket -Wl,--wrap=connect jni/sip_socket_guard.cpp; do
    printf '%s\n' "$jni_block" | $G -q -F -- "\"$flag\"" \
        || { echo "FAIL: libhamelinports_ims_jni does not have \"$flag\" (Android.bp)"; fail=1; }
done
if $G -q -E 'if \(transportType != resip::TCP\) return;' "$app/jni/hamelinports_ims_jni.cpp"; then
    echo "FAIL: the socket hook skips non-TCP sockets (the route probe and DNS are UDP)"
    fail=1
fi

[ "$fail" = 0 ] || exit 1
echo "PASS: $n_sites call sites listed with their status; probe, DNS and connect() guard wired"
