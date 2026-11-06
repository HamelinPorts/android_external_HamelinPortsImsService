"""Compile production lifecycle fragments with host stand-ins; no device or wire I/O.

This supplements the host suites. It tests application dispatch decisions, not the
reSIProcate protocol implementation. --candidate selects a staged source overlay.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument("--root", required=True, type=Path)
p.add_argument("--candidate", type=Path)
a = p.parse_args()
APP = "packages/apps/HamelinPortsImsService"

def read(path):
    candidate = a.candidate / path if a.candidate else a.root / path
    return candidate.read_text() if candidate.exists() else (a.root / path).read_text()

def fragment(source, marker):
    start = source.index(marker)
    opening = source.index("{", start)
    end, depth = opening + 1, 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]

jni = read(APP + "/jni/hamelinports_ims_jni.cpp")
java = read(APP + "/src/org/hamelinports/ims/HamelinPortsCallSession.java")
resip = read("external/resiprocate/resip/stack/TransactionState.cxx")

java_program = r'''
import java.util.concurrent.atomic.AtomicBoolean;
public class ConnectedProbe {
    static final String TAG = "test";
    static class Log { static void i(String a, String b) {} }
    AtomicBoolean mTerminated = new AtomicBoolean();
    AtomicBoolean mTerminalReported = new AtomicBoolean();
    long mCallStartNs;
    Object mProfile = new Object();
    int media;
    static class Listener {
        int initiated;
        void callSessionInitiated(Object profile) { initiated++; }
    }
    Listener mListener = new Listener();
    void startMediaEngine() { media++; }
    CALLBACK
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        ConnectedProbe terminated = new ConnectedProbe();
        terminated.mTerminated.set(true);
        terminated.onConnected(200, "late");
        check(terminated.media == 0 && terminated.mListener.initiated == 0);
        check(terminated.mCallStartNs == 0);
        ConnectedProbe terminal = new ConnectedProbe();
        terminal.mTerminalReported.set(true);
        terminal.onConnected(200, "late");
        check(terminal.media == 0 && terminal.mListener.initiated == 0);
        ConnectedProbe live = new ConnectedProbe();
        live.onConnected(200, "ok");
        check(live.media == 1 && live.mListener.initiated == 1);
        check(live.mCallStartNs != 0);
        System.out.println("Java production callback: 5 checks PASS");
    }
}
'''.replace("CALLBACK", fragment(java, "@Override public void onConnected(").replace("@Override ", ""))

local_end_program = r'''
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import org.hamelinports.ims.call.SipStatus;
public class LocalEndProbe {
    static final String TAG = "test";
    static class Log {
        static void i(String a, String b) {}
        static void w(String a, String b) {}
        static void w(String a, String b, Exception e) {}
        static void e(String a, String b) {}
        static void e(String a, String b, Exception e) {}
    }
    static class ImsReasonInfo {
        static final int CODE_USER_TERMINATED = 1, CODE_SIP_TEMPRARILY_UNAVAILABLE = 2;
        ImsReasonInfo(int a, int b, String c) {}
    }
    static class Listener {
        int ended, failed;
        void callSessionTerminated(ImsReasonInfo r) { ended++; }
        void callSessionInitiatedFailed(ImsReasonInfo r) { failed++; }
    }
    static class DeferredRequests { static class Entry { void cancel() {} } }
    class Stack {
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        boolean started, ended, failEnd;
        Runnable synchronousCallback;
        void enqueueStart() { queue.add(() -> started = true); }
        void endCall() {
            if (synchronousCallback != null) synchronousCallback.run();
            if (failEnd) throw new IllegalStateException();
            queue.add(() -> ended = started); // DUM may end without any Java callback.
        }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
    }
    class Controller { Stack stack = new Stack(); Stack stack() { return stack; } }
    Controller mRegController = new Controller();
    Listener mListener = new Listener();
    AtomicBoolean mTerminated = new AtomicBoolean(), mTerminalReported = new AtomicBoolean();
    DeferredRequests.Entry mDeferredStart;
    boolean mSrvccStarted, failCleanup;
    int cleanups;
    void cleanup() { cleanups++; if (failCleanup) throw new IllegalStateException(); }
    TERMINATE
    TERMINATED
    FAILURE
    static int checks;
    static void check(boolean b) { checks++; if (!b) throw new AssertionError(); }
    static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
    }
    public static void main(String[] args) throws Exception {
        // No InviteSession exists for no-1xx or 100-only. Final responses destroy
        // DUM's set silently: deliberately never deliver a native callback here.
        for (boolean onlyTrying : new boolean[]{false, true}) {
            for (int finalCode : new int[]{487, 408, 486, 503}) {
                LocalEndProbe p = new LocalEndProbe();
                p.mRegController.stack.enqueueStart();
                if (onlyTrying) p.mRegController.stack.drain();
                p.terminate(0);
                check(p.mListener.ended == 1 && p.mListener.failed == 0);
                p.mRegController.stack.drain();
                check(p.mRegController.stack.ended && p.cleanups == 1);
                p.terminate(0);
                p.onTerminated(finalCode, "late");
                p.onFailure(finalCode, "late");
                check(p.mListener.ended == 1 && p.mListener.failed == 0 && p.cleanups == 1);
            }
        }
        for (boolean failure : new boolean[]{false, true}) {
            LocalEndProbe p = new LocalEndProbe();
            p.mRegController.stack.synchronousCallback = () -> {
                if (failure) p.onFailure(503, "racing end");
                else p.onTerminated(487, "racing end");
            };
            p.terminate(0);
            check(p.mListener.ended == 1 && p.mListener.failed == 0 && p.cleanups == 1);
        }
        LocalEndProbe failedEnd = new LocalEndProbe();
        failedEnd.mRegController.stack.failEnd = true;
        failedEnd.terminate(0);
        check(failedEnd.mListener.ended == 1 && failedEnd.cleanups == 1);
        LocalEndProbe failedCleanup = new LocalEndProbe();
        failedCleanup.failCleanup = true;
        try { failedCleanup.terminate(0); } catch (IllegalStateException expected) {}
        check(failedCleanup.mListener.ended == 1);
        for (int n = 0; n < 64; n++) {
            LocalEndProbe p = new LocalEndProbe();
            CountDownLatch gate = new CountDownLatch(1);
            Thread local = new Thread(() -> { await(gate); p.terminate(0); });
            Thread nativeEnd = new Thread(() -> { await(gate); p.onTerminated(487, "race"); });
            local.start(); nativeEnd.start(); gate.countDown(); local.join(); nativeEnd.join();
            check(p.mListener.ended == 1 && p.mListener.failed == 0 && p.cleanups == 1);
        }
        LocalEndProbe srvcc = new LocalEndProbe();
        srvcc.mSrvccStarted = true;
        srvcc.terminate(0);
        check(srvcc.mListener.ended == 0 && !srvcc.mTerminalReported.get());
        System.out.println("Java local-end terminal: " + checks + " checks PASS");
    }
}
'''
local_terminate = fragment(java, 'public void terminate(int reason)')
local_end_program = local_end_program.replace('TERMINATE\n', local_terminate + '\n')
local_end_program = local_end_program.replace('TERMINATED\n', fragment(java, '@Override public void onTerminated(').replace('@Override ', '') + '\n')
local_end_program = local_end_program.replace('FAILURE\n', fragment(java, '@Override public void onFailure(').replace('@Override ', '') + '\n')

native = r'''
#include <cassert>
#include <functional>
#include <iostream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <utility>
#include <vector>
#define LOGI(...) do {} while (0)
#define LOGE(...) do {} while (0)
using EncodeStream = std::ostream;
struct Ref { int id; };
using jobject = Ref*;
struct JNIEnv {
    bool ExceptionCheck() { return false; }
    void ExceptionClear() {}
};
int globals = 0, locals = 0;
struct ListenerSlot {
    Ref* ref = nullptr;
    void set(JNIEnv*, Ref* next) { globals += (next != nullptr) - (ref != nullptr); ref = next; }
    bool isSet() const { return ref; }
};
struct ScopedListener {
    Ref* ref;
    ScopedListener(JNIEnv*, const ListenerSlot& slot) : ref(slot.ref) { locals += ref != nullptr; }
    ~ScopedListener() { locals -= ref != nullptr; }
    operator bool() const { return ref; }
    Ref* get() { return ref; }
};
struct FakeVm { void DetachCurrentThread() {} } vm;
FakeVm* gJvm = &vm;
JNIEnv env;
JNIEnv* attachJni(bool* attached) { *attached = false; return &env; }
struct Controls;
using Bridge = Controls;
ListenerSlot& bridgeCallListener(Bridge*);
namespace resip {
struct DialogUsageManager {};
template<class T> struct Handle {
    T* p = nullptr;
    bool isValid() const { return p != nullptr; }
    T* get() const { if (!p) throw std::logic_error("invalid handle"); return p; }
    T* operator->() const { return p; }
    T& operator*() const { return *p; }
    unsigned long getId() const { return reinterpret_cast<unsigned long>(p); }
    bool operator==(Handle h) const { return p == h.p; }
    bool operator!=(Handle h) const { return p != h.p; }
    static Handle NotValid() { return {}; }
};
struct AppDialogSet {
    explicit AppDialogSet(DialogUsageManager&) {}
    virtual ~AppDialogSet() = default;
    int ends = 0;
    void end() { ends++; }
    Handle<AppDialogSet> getHandle() { return {this}; }
};
using AppDialogSetHandle = Handle<AppDialogSet>;
struct Session {
    AppDialogSet* set;
    int ends = 0;
    AppDialogSetHandle getAppDialogSet() { return {set}; }
    Handle<Session> getSessionHandle() { return {this}; }
    void end() { ends++; }
};
using ClientInviteSessionHandle = Handle<Session>;
using InviteSessionHandle = Handle<Session>;
struct InviteSession { enum OfferAnswerType { Answer }; };
struct MasterProfile { int generation = 1; };
struct SipMessage {
    struct Header {
        int statusCode() const { return 503; }
        std::string reason() const { return "failure"; }
    };
    Header header(int) const { return {}; }
};
const int h_StatusLine = 0;
struct DumCommandAdapter {
    virtual ~DumCommandAdapter() = default;
    virtual void executeCommand() = 0;
    virtual EncodeStream& encodeBrief(EncodeStream&) const = 0;
};
}
SNAPSHOT_FUNCTION
SET_CLASS
CONFIG_STRUCT
COMMAND_CLASS
struct Handler {
    Bridge* mBridge = nullptr;
    std::mutex mMu;
    resip::ClientInviteSessionHandle mActive;
    resip::AppDialogSetHandle mOutgoing;
    int connections = 0, answers = 0, terminations = 0, failures = 0;
    Ref* terminalListener = nullptr;
    Ref* failureListener = nullptr;
    struct { int callTerminated = 1, callFailure = 2; } gIds;
    void upcall(int kind, int, const std::string&, const ListenerSlot* slot) {
        if (kind == 2) { failures++; failureListener = slot->ref; }
        else { terminations++; terminalListener = slot->ref; }
    }
    BEGIN
    END
    NEW
    CURRENT
    TERMINAL_HELPER
    FAILURE_CALLBACK
    LISTENER_FOR
    CONNECTED
    ANSWER
    TERMINATED
};
struct FakeQueue {
    std::vector<std::unique_ptr<resip::DumCommandAdapter>> queued;
    void post(resip::DumCommandAdapter* cmd) { queued.emplace_back(cmd); }
    void drain() {
        auto commands = std::move(queued);
        for (auto& cmd : commands) cmd->executeCommand();
    }
};
struct Controls {
    std::mutex mMu;
    bool mStarted = true, mDumRun = true;
    std::unique_ptr<FakeQueue> mDum = std::make_unique<FakeQueue>();
    Ref original{1}, replacement{2};
    ListenerSlot current;
    std::string mPcscfHostForSends = "proxy.invalid";
    unsigned int mPortCTransportKey = 7;
    struct { int pcscfPortS = 5060; } mAkaCache;
    std::shared_ptr<resip::MasterProfile> mProf = std::make_shared<resip::MasterProfile>();
    Controls() { current.set(&env, &original); }
    ~Controls() { current.set(&env, nullptr); }
    struct {
        int failures = 0, ends = 0;
        void callSetupFailed(const ListenerSlot& listener) { assert(listener.ref->id == 1); failures++; }
        void endOutgoing() { ends++; }
    } mInviteHandler;
    int starts = 0, accepts = 0, rejects = 0;
    bool result = true;
    bool startCallOnDum(const std::string& target, const std::string& sdp,
                        const std::shared_ptr<ListenerSlot>& listener,
                        const OutgoingCallConfig& config) {
        assert(listener->ref->id == 1);
        assert(config.pcscfHost == "proxy.invalid" && config.transportKey == 7);
        assert(config.pcscfPort == 5060 && config.profile->generation == 1);
        assert(config.profile != mProf);
        assert(target == "target" && sdp == "sdp"); starts++; return result;
    }
    bool acceptIncomingCallOnDum(const std::string&, const std::string&) { accepts++; return result; }
    bool rejectIncomingCallOnDum(const std::string&, int) { rejects++; return result; }
    QUEUE_METHODS
};
ListenerSlot& bridgeCallListener(Bridge* bridge) {
    static ListenerSlot empty;
    return bridge ? bridge->current : empty;
}
int checks = 0;
#define CHECK(x) do { ++checks; assert(x); } while (0)
int main() {
    resip::DialogUsageManager dum;
    resip::SipMessage msg;
    { // No provisional dialog exists yet: end the INVITE set.
        Handler h;
        OutgoingCallDialogSet set(dum);
        h.beginOutgoing(set);
        h.endOutgoing();
        h.endOutgoing();
        CHECK(set.ends == 1 && set.locallyEnded);
        CHECK(!h.mActive.isValid());
    }
    { // Three early forks: never BYE just the last-seen branch.
        Handler h;
        OutgoingCallDialogSet set(dum);
        resip::Session x{&set}, y{&set}, z{&set};
        h.beginOutgoing(set);
        for (auto* s : {&x, &y, &z})
            h.onNewSession({s}, resip::InviteSession::Answer, msg);
        h.endOutgoing();
        CHECK(set.ends == 1);
        CHECK(x.ends == 0 && y.ends == 0 && z.ends == 0);
        h.onAnswer({&x});
        h.onConnected({&y}, msg);
        CHECK(y.ends == 1 && h.connections == 0 && h.answers == 0);
        CHECK(!h.isCurrentOutgoing({&z}));
    }
    { // Retain the winner; reject SDP and connection from a later loser.
        Handler h;
        OutgoingCallDialogSet set(dum);
        resip::Session winner{&set}, loser{&set};
        h.beginOutgoing(set);
        h.onConnected({&winner}, msg);
        CHECK(h.isCurrentOutgoing({&winner}));
        CHECK(!h.isCurrentOutgoing({&loser})); // loser failure/provisional ignored
        h.onNewSession({&loser}, resip::InviteSession::Answer, msg);
        h.onAnswer({&loser});
        h.onConnected({&loser}, msg);
        h.onTerminated({&loser});
        CHECK(h.connections == 1 && h.mActive.p == &winner);
        CHECK(loser.ends == 1 && h.answers == 0 && h.terminations == 0);
        h.onAnswer({&winner});
        CHECK(h.answers == 1); // winning dialog re-INVITE still works
        h.endOutgoing();
        CHECK(winner.ends == 1 && set.ends == 0);
        CHECK(!h.isCurrentOutgoing({&winner}));
    }
    { // Old call responses must not reach a new session's listener.
        Handler h;
        OutgoingCallDialogSet old(dum), current(dum);
        resip::Session stale{&old};
        h.beginOutgoing(old);
        h.endOutgoing();
        h.beginOutgoing(current);
        h.onAnswer({&stale});
        h.onConnected({&stale}, msg);
        h.onTerminated({&stale});
        CHECK(stale.ends == 1 && h.connections == 0);
        CHECK(h.answers == 0 && h.terminations == 1);
        CHECK(h.mOutgoing.p == &current);
    }
    { // Commands defer execution and own argument copies.
        int started = 0;
        std::string value = "copied";
        CallControlCommand command([value, &started] { assert(value == "copied"); started++; });
        value.clear();
        CHECK(started == 0);
        command.executeCommand();
        CHECK(started == 1);
    }
    { // Actual JNI wrappers queue work and preserve failure callbacks.
        Controls c;
        CHECK(c.startCall("target", "sdp"));
        c.endCall();
        CHECK(c.starts == 0 && c.mInviteHandler.ends == 0);
        c.mPcscfHostForSends = "replacement.invalid";
        c.mPortCTransportKey = 9;
        c.mAkaCache.pcscfPortS = 5070;
        c.mProf->generation = 2;
        c.mDum->drain();
        CHECK(c.starts == 1 && c.mInviteHandler.ends == 1);
        c.mPcscfHostForSends = "proxy.invalid";
        c.mPortCTransportKey = 7;
        c.mAkaCache.pcscfPortS = 5060;
        c.mProf->generation = 1;
        c.result = false;
        CHECK(c.startCall("target", "sdp"));
        CHECK(c.acceptIncomingCall("call", "sdp"));
        CHECK(c.rejectIncomingCall("call", 486));
        c.current.set(&env, &c.replacement); // swap while DUM commands wait
        CHECK(c.mInviteHandler.failures == 0);
        c.mDum->drain();
        CHECK(c.mInviteHandler.failures == 2 && c.accepts == 1 && c.rejects == 1);
        c.mDumRun = false;
        CHECK(!c.startCall("target", "sdp"));
        CHECK(!c.acceptIncomingCall("call", "sdp"));
        CHECK(c.mDum->queued.empty());
    }
    { // Missing dialog-set handles throw on get(), exactly like real handles.
        Handler h;
        resip::Session detached{nullptr};
        CHECK(!h.listenerFor({}).isSet());
        CHECK(!h.isCurrentOutgoing({&detached}));
        CHECK(!h.shouldReportOutgoingTerminal({&detached}));
        h.onAnswer({&detached});
        h.onTerminated({&detached});
        h.onConnected({&detached}, msg);
        CHECK(h.answers == 0 && h.terminations == 0 && h.connections == 0);
        CHECK(detached.ends == 1);
    }
    { // Old winner terminals route to its snapshot, leaving the new winner intact.
        Controls c;
        Handler h;
        h.mBridge = &c;
        auto original = snapshotCallListener(&c);
        OutgoingCallDialogSet old(dum, original);
        resip::Session winner{&old}, loser{&old};
        h.beginOutgoing(old);
        h.onConnected({&winner}, msg);
        c.current.set(&env, &c.replacement);
        auto replacement = snapshotCallListener(&c);
        OutgoingCallDialogSet next(dum, replacement);
        resip::Session nextWinner{&next};
        h.beginOutgoing(next);
        h.onConnected({&nextWinner}, msg);
        CHECK(h.shouldReportOutgoingTerminal({&winner}));
        CHECK(!h.shouldReportOutgoingTerminal({&loser}));
        h.onTerminated({&loser});
        CHECK(h.terminations == 0);
        h.onFailure({&loser}, msg);
        CHECK(h.failures == 0);
        h.onFailure({&winner}, msg);
        CHECK(h.failures == 1 && h.failureListener == &c.original);
        CHECK(h.mActive.p == &nextWinner && h.mOutgoing.p == &next);
        h.onTerminated({&winner});
        CHECK(h.terminations == 1 && h.terminalListener == &c.original);
        CHECK(h.mActive.p == &nextWinner && h.mOutgoing.p == &next);
        old.locallyEnded = true;
        CHECK(h.shouldReportOutgoingTerminal({&winner}));
    }
    { // Old MO callbacks keep their original listener before next start runs.
        Controls c;
        Handler h;
        h.mBridge = &c;
        auto snapshot = snapshotCallListener(&c);
        CHECK(snapshot && snapshot->ref == &c.original);
        OutgoingCallDialogSet set(dum, snapshot);
        c.current.set(&env, &c.replacement);
        CHECK(h.listenerFor(set.getHandle()).ref == &c.original);
        CHECK(c.current.ref == &c.replacement && locals == 0);
    }
    CHECK(globals == 0 && locals == 0);
    std::cout << "JNI production lifecycle fragments: " << checks << " checks PASS\n";
}
'''

native = native.replace("SET_CLASS", fragment(jni, "class OutgoingCallDialogSet") + ";")
native = native.replace("CONFIG_STRUCT", fragment(jni, "struct OutgoingCallConfig") + ";")
native = native.replace("SNAPSHOT_FUNCTION", fragment(jni, "std::shared_ptr<ListenerSlot> snapshotCallListener("))
native = native.replace("LISTENER_FOR", fragment(jni, "const ListenerSlot& listenerFor("))
# The lifecycle-only probes do not dispatch JNI callbacks, so an empty listener
# object suffices; snapshot lifetime/routing is exercised separately above.
native = native.replace("set(dum);", "set(dum, std::make_shared<ListenerSlot>());")
native = native.replace("old(dum), current(dum);", "old(dum, std::make_shared<ListenerSlot>()), current(dum, std::make_shared<ListenerSlot>());")
native = native.replace("COMMAND_CLASS", fragment(jni, "class CallControlCommand") + ";")
native = native.replace("QUEUE_METHODS", "\n".join(fragment(jni, marker) for marker in [
    "bool postCallControl(", "bool startCall(", "void endCall()",
    "bool acceptIncomingCall(", "bool rejectIncomingCall(",
]))
for token, marker in [
    ("BEGIN", "void beginOutgoing("), ("END", "void endOutgoing("),
    ("NEW", "void onNewSession(resip::ClientInviteSessionHandle"),
    ("CURRENT", "bool isCurrentOutgoing("),
    ("TERMINAL_HELPER", "bool shouldReportOutgoingTerminal("),
    ("FAILURE_CALLBACK", "void onFailure(resip::ClientInviteSessionHandle h,"),
]:
    native = native.replace(token, fragment(jni, marker).replace(" override", ""))

# Retain the complete decision blocks; omit only logging/header diagnostics and
# JNI/SDP framework plumbing, replaced by counters after each accepted block.
connected = fragment(jni, "void onConnected(resip::ClientInviteSessionHandle")
connected = connected[:connected.index("        try {")]
connected = connected[connected.index("        bool accept;"):]
native = native.replace("CONNECTED", "void onConnected(resip::ClientInviteSessionHandle h, const resip::SipMessage&) {\n" + connected + "connections++; }")
answer = fragment(jni, "void onAnswer(resip::InviteSessionHandle h")
answer = answer[answer.index("        auto set ="):answer.index('        LOGI("INVITE onAnswer')]
native = native.replace("ANSWER", "void onAnswer(resip::InviteSessionHandle h) {\n" + answer + "answers++; }")
terminated = fragment(jni, "void onTerminated(resip::InviteSessionHandle h")
terminated = terminated[terminated.index("        auto set ="):terminated.index('        LOGI("INVITE onTerminated')]
native = native.replace("TERMINATED", "void onTerminated(resip::InviteSessionHandle h) { int reason = 0;\n" + terminated + "terminations++; }")

# Test the actual REGISTER 503 switch branch with a minimal DNS recorder.
start = resip.index("         case 503:", resip.index("TransactionState::sendToTU("))
end = resip.index("         case 408:", start)
branch = resip[start:end]
register_program = r'''
#include <cassert>
#include <iostream>
enum Method { REGISTER, INVITE, MESSAGE };
namespace resip { enum Header { h_RetryAfter }; struct Timer { static unsigned getTimeMs() { return 0; } }; }
struct Retry {
    unsigned retry;
    bool isWellFormed() const { return true; }
    unsigned value() const { return retry; }
};
struct Message {
    Method methodValue;
    unsigned retry;
    bool present;
    Method method() const { return methodValue; }
    bool exists(resip::Header) const { return present; }
    Retry const_header(resip::Header) const { return {retry}; }
};
struct Dns { int blacklisted = 0; void blacklistLast(unsigned) { blacklisted++; } };
void respond(Message* sipMsg, Dns* mDnsResult) { switch (503) { BRANCH } }
int main() {
    Dns dns;
    Message reg{REGISTER, 300, true}, invite{INVITE, 300, true}, sms{MESSAGE, 300, true};
    respond(&reg, &dns); assert(dns.blacklisted == 0);
    respond(&invite, &dns); assert(dns.blacklisted == 1);
    respond(&sms, &dns); assert(dns.blacklisted == 2);
    invite.retry = 0; respond(&invite, &dns); assert(dns.blacklisted == 2);
    invite.retry = 300; invite.present = false;
    respond(&invite, &dns); assert(dns.blacklisted == 2);
    std::cout << "REGISTER production 503 branch: 5 checks PASS (no wire test)\n";
}
'''.replace("BRANCH", branch)

with tempfile.TemporaryDirectory(prefix="a51-366-probes-") as tmp:
    tmp = Path(tmp)
    jdk = a.root / "prebuilts/jdk/jdk21/linux-x86/bin"
    j = tmp / "ConnectedProbe.java"
    j.write_text(java_program)
    subprocess.run([str(jdk / "javac"), "-d", str(tmp), str(j)], check=True)
    subprocess.run([str(jdk / "java"), "-cp", str(tmp), "ConnectedProbe"], check=True)
    local = tmp / 'LocalEndProbe.java'
    def local_probe(program, expected):
        local.write_text(program)
        subprocess.run([str(jdk / 'javac'), '-d', str(tmp), str(local),
                        str(a.root / APP / 'src/org/hamelinports/ims/call/SipStatus.java')], check=True)
        result = subprocess.run([str(jdk / 'java'), '-cp', str(tmp), 'LocalEndProbe'],
                                text=True, capture_output=True)
        if expected:
            assert result.returncode == 0, result.stderr
            print(result.stdout.strip(), flush=True)
        else:
            assert result.returncode != 0 and 'AssertionError' in result.stderr
    local_probe(local_end_program, True)
    callback = 'mListener.callSessionTerminated(new ImsReasonInfo(\n                            ImsReasonInfo.CODE_USER_TERMINATED, 0, "Local end"));'
    assert local_end_program.count(callback) == 1
    local_probe(local_end_program.replace(callback, ';'), False)
    # Remove only terminate's CAS; repeated local end must then fail exactly-once.
    cas = 'if (!mTerminalReported.compareAndSet(false, true)) return;'
    assert cas in local_terminate
    local_probe(local_end_program.replace(local_terminate, local_terminate.replace(cas, '', 1)), False)
    print('Local-end mutation controls: 2/2 caught after compilation', flush=True)
    for name, code in [("native", native), ("register", register_program)]:
        src = tmp / (name + ".cpp")
        src.write_text(code)
        binary = tmp / name
        subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                        "-pthread", str(src), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
