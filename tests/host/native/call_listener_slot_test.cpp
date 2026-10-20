/*
 * Host test of jni/call_listener_slot.{h,cpp} against a fake JNIEnv that
 * tracks every reference and records class lookups:
 *  - every listener method ID comes from its interface (FindClass), never
 *    from a listener's concrete class (GetObjectClass);
 *  - a second listener class does not change the IDs;
 *  - after set(null) no upcall reaches a listener;
 *  - an upcall interleaved with a set never uses a deleted global reference.
 * Exit status 0 = every check passed.
 */
#include "call_listener_slot.h"

#include <stdio.h>
#include <string.h>

#include <atomic>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

using hamelinports::ListenerMethodIds;
using hamelinports::ListenerSlot;
using hamelinports::ScopedListener;

namespace {

int gChecks = 0;
int gFailures = 0;

#define CHECK(cond)                                                     \
    do {                                                                \
        ++gChecks;                                                      \
        if (!(cond)) {                                                  \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++gFailures;                                                \
        }                                                               \
    } while (0)

/* ---------------------------------------------------------------- fake VM */

enum class Kind { Object, Global, Local, Class };

struct Handle {
    Kind kind;
    std::string className;  /* Object/Class: its class; refs: the referent's */
    Handle* referent;       /* refs: the object they point to */
    bool alive;
};

struct MethodId {
    std::string className;
    std::string name;
    std::string signature;
};

struct FakeVm {
    std::mutex mu;
    std::vector<Handle*> handles;
    std::vector<MethodId*> methods;
    std::vector<std::string> foundClasses;
    std::set<std::string> missingClasses;   /* FindClass fails for these */
    std::set<std::string> missingMethods;   /* GetMethodID fails for these names */
    int getObjectClassCalls = 0;
    int violations = 0;
    bool exceptionPending = false;

    Handle* make(Kind k, const std::string& cls, Handle* referent) {
        Handle* h = new Handle{k, cls, referent, true};
        handles.push_back(h);
        return h;
    }
    int liveGlobals() {
        std::lock_guard<std::mutex> g(mu);
        int n = 0;
        for (Handle* h : handles) n += (h->kind == Kind::Global && h->alive) ? 1 : 0;
        return n;
    }
    int liveLocals() {
        std::lock_guard<std::mutex> g(mu);
        int n = 0;
        for (Handle* h : handles) n += (h->kind == Kind::Local && h->alive) ? 1 : 0;
        return n;
    }
};

FakeVm* gVm = nullptr;

Handle* H(jobject o) { return reinterpret_cast<Handle*>(o); }
jobject J(Handle* h) { return reinterpret_cast<jobject>(h); }

/* A reference is usable if it is a live global or local ref, or a plain object (a JNI argument). */
bool usable(Handle* h) {
    if (h == nullptr) return false;
    if (h->kind == Kind::Object) return true;
    return (h->kind == Kind::Global || h->kind == Kind::Local) && h->alive && h->referent->alive;
}

Handle* referentOf(Handle* h) { return h->kind == Kind::Object ? h : h->referent; }

/* Like CheckJNI: only the exception and reference-deletion calls are allowed with an exception pending. */
void requireNoPendingException() {
    if (gVm->exceptionPending) gVm->violations++;
}

jclass fakeFindClass(JNIEnv*, const char* name) {
    std::lock_guard<std::mutex> g(gVm->mu);
    requireNoPendingException();
    gVm->foundClasses.push_back(name);
    if (gVm->missingClasses.count(name)) {
        gVm->exceptionPending = true;
        return nullptr;
    }
    return reinterpret_cast<jclass>(gVm->make(Kind::Class, name, nullptr));
}

jmethodID fakeGetMethodID(JNIEnv*, jclass cls, const char* name, const char* sig) {
    std::lock_guard<std::mutex> g(gVm->mu);
    requireNoPendingException();
    Handle* c = reinterpret_cast<Handle*>(cls);
    if (c == nullptr || c->kind != Kind::Class || !c->alive) {
        gVm->violations++;
        return nullptr;
    }
    if (gVm->missingMethods.count(name)) {
        gVm->exceptionPending = true;
        return nullptr;
    }
    MethodId* m = new MethodId{c->className, name, sig};
    gVm->methods.push_back(m);
    return reinterpret_cast<jmethodID>(m);
}

jclass fakeGetObjectClass(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(gVm->mu);
    gVm->getObjectClassCalls++;
    return nullptr;
}

jboolean fakeExceptionCheck(JNIEnv*) {
    std::lock_guard<std::mutex> g(gVm->mu);
    return gVm->exceptionPending ? JNI_TRUE : JNI_FALSE;
}

void fakeExceptionClear(JNIEnv*) {
    std::lock_guard<std::mutex> g(gVm->mu);
    gVm->exceptionPending = false;
}

jobject fakeNewGlobalRef(JNIEnv*, jobject o) {
    std::lock_guard<std::mutex> g(gVm->mu);
    requireNoPendingException();
    Handle* h = H(o);
    if (!usable(h)) {
        gVm->violations++;
        return nullptr;
    }
    Handle* r = referentOf(h);
    return J(gVm->make(Kind::Global, r->className, r));
}

jobject fakeNewLocalRef(JNIEnv*, jobject o) {
    std::lock_guard<std::mutex> g(gVm->mu);
    requireNoPendingException();
    Handle* h = H(o);
    if (!usable(h)) {
        gVm->violations++;
        return nullptr;
    }
    Handle* r = referentOf(h);
    return J(gVm->make(Kind::Local, r->className, r));
}

void fakeDeleteGlobalRef(JNIEnv*, jobject o) {
    std::lock_guard<std::mutex> g(gVm->mu);
    Handle* h = H(o);
    if (h == nullptr || h->kind != Kind::Global || !h->alive) {
        gVm->violations++;
        return;
    }
    h->alive = false;
}

void fakeDeleteLocalRef(JNIEnv*, jobject o) {
    std::lock_guard<std::mutex> g(gVm->mu);
    Handle* h = H(o);
    if (h == nullptr) return;
    if ((h->kind != Kind::Local && h->kind != Kind::Class) || !h->alive) {
        gVm->violations++;
        return;
    }
    h->alive = false;
}

struct FakeEnv {
    JNINativeInterface table;
    _JNIEnv env;
    FakeEnv() {
        memset(&table, 0, sizeof(table));
        table.FindClass = fakeFindClass;
        table.GetMethodID = fakeGetMethodID;
        table.GetObjectClass = fakeGetObjectClass;
        table.ExceptionCheck = fakeExceptionCheck;
        table.ExceptionClear = fakeExceptionClear;
        table.NewGlobalRef = fakeNewGlobalRef;
        table.NewLocalRef = fakeNewLocalRef;
        table.DeleteGlobalRef = fakeDeleteGlobalRef;
        table.DeleteLocalRef = fakeDeleteLocalRef;
        env.functions = &table;
    }
};

/** A Java object of the given concrete class. */
jobject newObject(const std::string& cls) {
    std::lock_guard<std::mutex> g(gVm->mu);
    return J(gVm->make(Kind::Object, cls, nullptr));
}

/** What an upcall does with its reference: records a violation unless it is usable. */
std::string upcall(jobject ref) {
    std::lock_guard<std::mutex> g(gVm->mu);
    Handle* h = H(ref);
    if (!usable(h)) {
        gVm->violations++;
        return "";
    }
    return referentOf(h)->className;
}

void resetVm() {
    delete gVm;
    gVm = new FakeVm();
}

/* --------------------------------------------- expected listener methods */

/* Written from the Java interfaces, independently of the production table. */
struct Expected {
    const char* iface;
    const char* name;
    const char* sig;
    jmethodID ListenerMethodIds::*id;
};
const char* kStr = "Ljava/lang/String;";
const std::string kAnswer = std::string("(") + kStr + "IIII" + kStr + kStr + ")V";
const std::string kReinvite = std::string("(") + kStr + "IIII" + kStr + kStr + kStr + "IIII" + kStr + kStr + ")V";
const std::string kInvite = std::string("(") + kStr + kStr + kStr + "IIII" + kStr + kStr + ")V";
const std::string kInviteVideo = std::string("(") + kStr + kStr + "IIII" + kStr + kStr + ")V";
const Expected kExpected[] = {
    {"org/hamelinports/ims/sip/CallSessionListener", "onProvisional", "(ILjava/lang/String;)V", &ListenerMethodIds::callProvisional},
    {"org/hamelinports/ims/sip/CallSessionListener", "onConnected", "(ILjava/lang/String;)V", &ListenerMethodIds::callConnected},
    {"org/hamelinports/ims/sip/CallSessionListener", "onTerminated", "(ILjava/lang/String;)V", &ListenerMethodIds::callTerminated},
    {"org/hamelinports/ims/sip/CallSessionListener", "onFailure", "(ILjava/lang/String;)V", &ListenerMethodIds::callFailure},
    {"org/hamelinports/ims/sip/CallSessionListener", "onAnswer", kAnswer.c_str(), &ListenerMethodIds::callAnswer},
    {"org/hamelinports/ims/sip/CallSessionListener", "onAnswerVideo", kAnswer.c_str(), &ListenerMethodIds::callAnswerVideo},
    {"org/hamelinports/ims/sip/CallSessionListener", "onRemoteReinvite", kReinvite.c_str(), &ListenerMethodIds::callRemoteReinvite},
    {"org/hamelinports/ims/sip/IncomingCallListener", "onIncomingInvite", kInvite.c_str(), &ListenerMethodIds::incomingInvite},
    {"org/hamelinports/ims/sip/IncomingCallListener", "onIncomingInviteVideo", kInviteVideo.c_str(), &ListenerMethodIds::incomingInviteVideo},
    {"org/hamelinports/ims/sip/IncomingCallListener", "onIncomingCancelled", "(Ljava/lang/String;I)V", &ListenerMethodIds::incomingCancelled},
    {"org/hamelinports/ims/sip/SmsSessionListener", "onSendSuccess", "(ILjava/lang/String;)V", &ListenerMethodIds::smsSendSuccess},
    {"org/hamelinports/ims/sip/SmsSessionListener", "onSendFailure", "(ILjava/lang/String;)V", &ListenerMethodIds::smsSendFailure},
    {"org/hamelinports/ims/sip/SmsSessionListener", "onIncomingSms", "([B)V", &ListenerMethodIds::smsIncoming},
    {"org/hamelinports/ims/sip/RegistrationListener", "onRegistered", "(Ljava/lang/String;)V", &ListenerMethodIds::registered},
    {"org/hamelinports/ims/sip/RegistrationListener", "onDeregistered", "(ILjava/lang/String;IZ)V", &ListenerMethodIds::deregistered},
    {"org/hamelinports/ims/sip/RegistrationListener", "onExpiresReported", "(I)V", &ListenerMethodIds::expiresReported},
    {"org/hamelinports/ims/sip/AkaProvider", "onAuthChallenge",
     "([B[BJJIILjava/lang/String;)Lorg/hamelinports/ims/sip/AkaResult;", &ListenerMethodIds::authChallenge},
};
const size_t kExpectedCount = sizeof(kExpected) / sizeof(kExpected[0]);

/* ------------------------------------------------------------------ tests */

void idsComeFromTheInterfaces() {
    resetVm();
    FakeEnv fe;
    ListenerMethodIds ids;
    CHECK(hamelinports::resolveListenerMethods(&fe.env, &ids) == 0);
    CHECK(gVm->getObjectClassCalls == 0);
    CHECK(!gVm->exceptionPending);
    size_t count = 0;
    hamelinports::listenerMethodSpecs(&count);
    CHECK(count == kExpectedCount);
    for (const Expected& e : kExpected) {
        const MethodId* m = reinterpret_cast<const MethodId*>(ids.*e.id);
        CHECK(m != nullptr);
        if (!m) continue;
        CHECK(m->className == e.iface);
        CHECK(m->name == e.name);
        CHECK(m->signature == e.sig);
    }
    for (const std::string& c : gVm->foundClasses) {
        CHECK(c.rfind("org/hamelinports/ims/sip/", 0) == 0);
    }
    /* Every class reference FindClass returned was released. */
    int liveClasses = 0;
    for (Handle* h : gVm->handles) liveClasses += (h->kind == Kind::Class && h->alive) ? 1 : 0;
    CHECK(liveClasses == 0);
    CHECK(gVm->violations == 0);
}

void aSecondListenerClassDoesNotChangeTheIds() {
    resetVm();
    FakeEnv fe;
    ListenerMethodIds ids;
    hamelinports::resolveListenerMethods(&fe.env, &ids);
    const ListenerMethodIds before = ids;
    ListenerSlot slot;
    slot.set(&fe.env, newObject("org/hamelinports/ims/HamelinPortsCallSession$2"));
    {
        ScopedListener l(&fe.env, slot);
        CHECK(upcall(l.get()) == "org/hamelinports/ims/HamelinPortsCallSession$2");
    }
    slot.set(&fe.env, newObject("org/hamelinports/ims/HamelinPortsIncomingCallSession$1"));
    {
        ScopedListener l(&fe.env, slot);
        CHECK(upcall(l.get()) == "org/hamelinports/ims/HamelinPortsIncomingCallSession$1");
    }
    CHECK(memcmp(&before, &ids, sizeof(ids)) == 0);
    CHECK(gVm->getObjectClassCalls == 0);
    const MethodId* failure = reinterpret_cast<const MethodId*>(ids.callFailure);
    CHECK(failure && failure->className == "org/hamelinports/ims/sip/CallSessionListener");
    slot.set(&fe.env, nullptr);
    CHECK(gVm->violations == 0);
}

void missingMethodsAreCountedAndCleared() {
    resetVm();
    gVm->missingMethods.insert("onAnswerVideo");
    gVm->missingClasses.insert("org/hamelinports/ims/sip/SmsSessionListener");
    FakeEnv fe;
    ListenerMethodIds ids;
    CHECK(hamelinports::resolveListenerMethods(&fe.env, &ids) == 4);
    CHECK(ids.callAnswerVideo == nullptr);
    CHECK(ids.smsSendSuccess == nullptr && ids.smsSendFailure == nullptr && ids.smsIncoming == nullptr);
    CHECK(ids.callAnswer != nullptr && ids.registered != nullptr);
    CHECK(!gVm->exceptionPending);
    CHECK(gVm->violations == 0);
}

void noUpcallAfterClear() {
    resetVm();
    FakeEnv fe;
    ListenerSlot slot;
    CHECK(!slot.isSet());
    slot.set(&fe.env, newObject("A"));
    CHECK(slot.isSet());
    CHECK(gVm->liveGlobals() == 1);
    slot.set(&fe.env, nullptr);
    CHECK(!slot.isSet());
    CHECK(gVm->liveGlobals() == 0);
    ScopedListener l(&fe.env, slot);
    CHECK(!l);
    CHECK(l.get() == nullptr);
    CHECK(gVm->violations == 0);
}

void replacingDeletesTheOldGlobal() {
    resetVm();
    FakeEnv fe;
    ListenerSlot slot;
    slot.set(&fe.env, newObject("A"));
    slot.set(&fe.env, newObject("B"));
    CHECK(gVm->liveGlobals() == 1);
    {
        ScopedListener l(&fe.env, slot);
        CHECK(upcall(l.get()) == "B");
        CHECK(gVm->liveLocals() == 1);
    }
    CHECK(gVm->liveLocals() == 0);
    slot.set(&fe.env, nullptr);
    CHECK(gVm->liveGlobals() == 0);
    CHECK(gVm->violations == 0);
}

/** The fake catches a use of a deleted global: what the unlocked raw read did. */
void theFakeDetectsAUseOfADeletedGlobal() {
    resetVm();
    FakeEnv fe;
    jobject raw = fe.env.NewGlobalRef(newObject("A"));
    fe.env.DeleteGlobalRef(raw);
    upcall(raw);
    CHECK(gVm->violations == 1);
}

void upcallInterleavedWithASet() {
    resetVm();
    FakeEnv fe;
    ListenerSlot slot;
    slot.set(&fe.env, newObject("MO"));
    {
        ScopedListener l(&fe.env, slot);    /* the DUM thread takes its reference */
        slot.set(&fe.env, newObject("MT")); /* the binder thread swaps; MO's global is deleted */
        CHECK(gVm->liveGlobals() == 1);
        CHECK(upcall(l.get()) == "MO");     /* the upcall still works on its own reference */
    }
    {
        ScopedListener l(&fe.env, slot);
        CHECK(upcall(l.get()) == "MT");
    }
    slot.set(&fe.env, nullptr);
    CHECK(gVm->liveLocals() == 0);
    CHECK(gVm->liveGlobals() == 0);
    CHECK(gVm->violations == 0);
}

void concurrentSetsAndUpcalls() {
    resetVm();
    FakeEnv fe;
    ListenerSlot slot;
    std::vector<jobject> objects;
    for (const char* c : {"MO", "MT"}) objects.push_back(newObject(c));
    std::atomic<bool> stop{false};
    std::atomic<int> calls{0};
    std::thread caller([&] {
        while (!stop.load()) {
            ScopedListener l(&fe.env, slot);
            if (l) {
                upcall(l.get());
                calls++;
            }
        }
    });
    for (int i = 0; i < 20000; i++) {
        slot.set(&fe.env, i % 3 == 2 ? nullptr : objects[i % 2]);
    }
    stop = true;
    caller.join();
    slot.set(&fe.env, nullptr);
    CHECK(calls.load() > 0);
    CHECK(gVm->liveGlobals() == 0);
    CHECK(gVm->liveLocals() == 0);
    CHECK(gVm->violations == 0);
}

}  // namespace

int main() {
    idsComeFromTheInterfaces();
    aSecondListenerClassDoesNotChangeTheIds();
    missingMethodsAreCountedAndCleared();
    noUpcallAfterClear();
    replacingDeletesTheOldGlobal();
    theFakeDetectsAUseOfADeletedGlobal();
    upcallInterleavedWithASet();
    concurrentSetsAndUpcalls();
    printf("%s: %d checks, %d failed\n", gFailures ? "FAIL" : "PASS", gChecks, gFailures);
    return gFailures ? 1 : 0;
}
