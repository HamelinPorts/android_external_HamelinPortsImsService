#pragma once

/* Java listener references and method IDs for the JNI upcalls.
 *
 * Method IDs are resolved once, in JNI_OnLoad, from the listener INTERFACES.
 * An interface method ID dispatches on the receiver's class, so it is valid
 * for every implementation. An ID taken from one implementation's concrete
 * class is not: ART release builds dispatch it by that class's vtable slot,
 * so another implementation runs the wrong method.
 *
 * A ListenerSlot holds one listener's global reference. The binder thread
 * replaces it while the DUM thread makes upcalls, so the reference is read
 * under a mutex and each upcall works on its own local reference. */

#include <jni.h>

#include <mutex>

namespace hamelinports {

/** Every listener method the native stack calls; null if it could not be resolved. */
struct ListenerMethodIds {
    /* org.hamelinports.ims.sip.CallSessionListener */
    jmethodID callProvisional = nullptr;
    jmethodID callConnected = nullptr;
    jmethodID callTerminated = nullptr;
    jmethodID callFailure = nullptr;
    jmethodID callAnswer = nullptr;
    jmethodID callAnswerVideo = nullptr;
    jmethodID callRemoteReinvite = nullptr;
    /* org.hamelinports.ims.sip.IncomingCallListener */
    jmethodID incomingInvite = nullptr;
    jmethodID incomingInviteVideo = nullptr;
    jmethodID incomingCancelled = nullptr;
    /* org.hamelinports.ims.sip.SmsSessionListener */
    jmethodID smsSendSuccess = nullptr;
    jmethodID smsSendFailure = nullptr;
    jmethodID smsIncoming = nullptr;
    /* org.hamelinports.ims.sip.RegistrationListener */
    jmethodID registered = nullptr;
    jmethodID deregistered = nullptr;
    jmethodID expiresReported = nullptr;
    /* org.hamelinports.ims.sip.AkaProvider */
    jmethodID authChallenge = nullptr;
};

/** One listener method: its interface, name and signature, and where its ID goes. */
struct ListenerMethodSpec {
    const char* interfaceName;  /* JNI form, e.g. "org/hamelinports/ims/sip/CallSessionListener" */
    const char* name;
    const char* signature;
    jmethodID ListenerMethodIds::*id;
};

/** The table resolveListenerMethods() works from. */
const ListenerMethodSpec* listenerMethodSpecs(size_t* count);

/**
 * Resolves every method of listenerMethodSpecs() from its interface with
 * FindClass + GetMethodID. Must run where FindClass sees the app's classes
 * (JNI_OnLoad). Leaves no exception pending. Returns the number of methods
 * that could not be resolved (their IDs stay null).
 */
using MissingIdLogger = void (*)(const char* owner, const char* name, const char* signature);
int resolveListenerMethods(JNIEnv* env, ListenerMethodIds* ids, MissingIdLogger log = nullptr);

struct AkaResultIds {
    jclass resultClass = nullptr;
    jfieldID res = nullptr;
    jfieldID ck = nullptr;
    jfieldID ik = nullptr;
    jfieldID auts = nullptr;
};

/** Resolves AKA result fields at load time; clears lookup exceptions and reports every missing ID. */
int resolveAkaResult(JNIEnv* env, AkaResultIds* ids, MissingIdLogger log = nullptr);

/** JNI_OnLoad gate: every required method and field must resolve before the library is usable. */
jint initializeJni(JavaVM* vm, ListenerMethodIds* methods, AkaResultIds* aka, MissingIdLogger log);

/** One Java listener, replaced from Java threads and called from native threads. */
class ListenerSlot {
public:
    ListenerSlot() = default;
    ListenerSlot(const ListenerSlot&) = delete;
    ListenerSlot& operator=(const ListenerSlot&) = delete;

    /** Installs {@code listener} (null clears). The previous global reference is deleted after the swap. */
    void set(JNIEnv* env, jobject listener);

    /** A new local reference to the current listener, or null. The caller deletes it. */
    jobject newLocalRef(JNIEnv* env) const;

    bool isSet() const;

private:
    mutable std::mutex mMu;
    jobject mGlobal = nullptr;
};

/** The slot's listener for the duration of one upcall; empty if none is set. */
class ScopedListener {
public:
    ScopedListener(JNIEnv* env, const ListenerSlot& slot)
        : mEnv(env), mRef(env ? slot.newLocalRef(env) : nullptr) {}
    ~ScopedListener() {
        if (mRef) mEnv->DeleteLocalRef(mRef);
    }
    ScopedListener(const ScopedListener&) = delete;
    ScopedListener& operator=(const ScopedListener&) = delete;

    jobject get() const { return mRef; }
    explicit operator bool() const { return mRef != nullptr; }

private:
    JNIEnv* mEnv;
    jobject mRef;
};

}  // namespace hamelinports
