#!/bin/bash
# Fails if the JNI code can cache a listener method ID resolved from a
# listener's concrete class. Such an ID is dispatched by that class's vtable
# slot, so another implementation of the same interface runs the wrong method.
# Rules, over every source file in jni/:
#   1. no GetObjectClass at all (any mention: call, comment or string);
#   2. GetMethodID is called only in jni/call_listener_slot.cpp, once, on the
#      class FindClass returned for the interface;
#   3. no file-scope or static jmethodID variable (a cache): listener IDs live
#      in ListenerMethodIds.
#   usage: check-jni-listener-ids.sh [tree-root]   (default: this repository)
set -euo pipefail

root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$root"

[ -d jni ] || { echo "FAIL: no jni/ under $root — gate cannot see the code" >&2; exit 2; }
files="$(find jni -type f \( -name '*.cpp' -o -name '*.h' -o -name '*.c' \) | sort)"
[ -n "$files" ] || { echo "FAIL: no sources in $root/jni — gate cannot see the code" >&2; exit 2; }
slot=jni/call_listener_slot.cpp

findings=""
add() { findings="$findings$1"$'\n'; }

hits="$(printf '%s\n' "$files" | xargs /bin/grep -n 'GetObjectClass' || true)"
[ -z "$hits" ] || add "GetObjectClass in jni/ (a listener's concrete class must not feed a method ID):"$'\n'"$hits"

hits="$(printf '%s\n' "$files" | /bin/grep -v -x "$slot" | xargs /bin/grep -nE 'GetMethodID[[:space:]]*\(' || true)"
[ -z "$hits" ] || add "GetMethodID called outside $slot:"$'\n'"$hits"

if [ -f "$slot" ]; then
    calls="$(/bin/grep -cE 'GetMethodID[[:space:]]*\(' "$slot" || true)"
    from_find="$(/bin/grep -c 'jclass iface = env->FindClass(spec.interfaceName);' "$slot" || true)"
    uses_iface="$(/bin/grep -c 'env->GetMethodID(iface, spec.name, spec.signature)' "$slot" || true)"
    if [ "$calls" != 1 ] || [ "$from_find" != 1 ] || [ "$uses_iface" != 1 ]; then
        add "$slot: expected one GetMethodID, on the class FindClass returned for the interface (calls=$calls find=$from_find iface=$uses_iface)"
    fi
else
    add "$slot missing: listener method IDs must be resolved there, from the interfaces"
fi

hits="$(printf '%s\n' "$files" | xargs /bin/grep -nE '^(static[[:space:]]+)?jmethodID[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*(=|;|,)|^[[:space:]]+static[[:space:]]+jmethodID[[:space:]]' || true)"
[ -z "$hits" ] || add "file-scope or static jmethodID (a method-ID cache) outside ListenerMethodIds:"$'\n'"$hits"

if [ -n "$findings" ]; then
    echo "FAIL: listener method IDs may come from a concrete class"
    printf '%s' "$findings"
    exit 1
fi
echo "PASS: $(printf '%s\n' "$files" | wc -l) files under jni/; listener method IDs only from the interfaces"
