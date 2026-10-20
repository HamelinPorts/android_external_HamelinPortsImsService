#include "call_listener_slot.h"

namespace hamelinports {

namespace {

constexpr const char* kCall = "org/hamelinports/ims/sip/CallSessionListener";
constexpr const char* kIncoming = "org/hamelinports/ims/sip/IncomingCallListener";
constexpr const char* kSms = "org/hamelinports/ims/sip/SmsSessionListener";
constexpr const char* kRegistration = "org/hamelinports/ims/sip/RegistrationListener";
constexpr const char* kAka = "org/hamelinports/ims/sip/AkaProvider";

constexpr const char* kAnswerSig =
        "(Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;)V";

const ListenerMethodSpec kSpecs[] = {
    {kCall, "onProvisional", "(ILjava/lang/String;)V", &ListenerMethodIds::callProvisional},
    {kCall, "onConnected", "(ILjava/lang/String;)V", &ListenerMethodIds::callConnected},
    {kCall, "onTerminated", "(ILjava/lang/String;)V", &ListenerMethodIds::callTerminated},
    {kCall, "onFailure", "(ILjava/lang/String;)V", &ListenerMethodIds::callFailure},
    {kCall, "onAnswer", kAnswerSig, &ListenerMethodIds::callAnswer},
    {kCall, "onAnswerVideo", kAnswerSig, &ListenerMethodIds::callAnswerVideo},
    /* (audioIp, audioPort, audioRtcp, audioPt, audioRate, audioCodec, audioFmtp,
     *  videoIp, videoPort, videoRtcp, videoPt, videoRate, videoCodec, videoFmtp) */
    {kCall, "onRemoteReinvite",
     "(Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;"
     "Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;)V",
     &ListenerMethodIds::callRemoteReinvite},
    /* (callId, fromUri, remoteIp, remotePort, rtcpPort, pt, clockRate, codecName, fmtp) */
    {kIncoming, "onIncomingInvite",
     "(Ljava/lang/String;Ljava/lang/String;"
     "Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;)V",
     &ListenerMethodIds::incomingInvite},
    {kIncoming, "onIncomingInviteVideo",
     "(Ljava/lang/String;Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;)V",
     &ListenerMethodIds::incomingInviteVideo},
    {kIncoming, "onIncomingCancelled", "(Ljava/lang/String;I)V",
     &ListenerMethodIds::incomingCancelled},
    {kSms, "onSendSuccess", "(ILjava/lang/String;)V", &ListenerMethodIds::smsSendSuccess},
    {kSms, "onSendFailure", "(ILjava/lang/String;)V", &ListenerMethodIds::smsSendFailure},
    {kSms, "onIncomingSms", "([B)V", &ListenerMethodIds::smsIncoming},
    {kRegistration, "onRegistered", "(Ljava/lang/String;)V", &ListenerMethodIds::registered},
    {kRegistration, "onDeregistered", "(ILjava/lang/String;IZ)V",
     &ListenerMethodIds::deregistered},
    {kRegistration, "onExpiresReported", "(I)V", &ListenerMethodIds::expiresReported},
    {kAka, "onAuthChallenge",
     "([B[BJJIILjava/lang/String;)Lorg/hamelinports/ims/sip/AkaResult;",
     &ListenerMethodIds::authChallenge},
};

}  // namespace

const ListenerMethodSpec* listenerMethodSpecs(size_t* count) {
    *count = sizeof(kSpecs) / sizeof(kSpecs[0]);
    return kSpecs;
}

int resolveListenerMethods(JNIEnv* env, ListenerMethodIds* ids) {
    int missing = 0;
    for (const ListenerMethodSpec& spec : kSpecs) {
        jclass iface = env->FindClass(spec.interfaceName);
        if (iface == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            ids->*spec.id = nullptr;
            missing++;
            continue;
        }
        jmethodID id = env->GetMethodID(iface, spec.name, spec.signature);
        if (id == nullptr && env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(iface);
        ids->*spec.id = id;
        if (id == nullptr) missing++;
    }
    return missing;
}

void ListenerSlot::set(JNIEnv* env, jobject listener) {
    jobject next = listener ? env->NewGlobalRef(listener) : nullptr;
    jobject previous;
    {
        std::lock_guard<std::mutex> g(mMu);
        previous = mGlobal;
        mGlobal = next;
    }
    /* An upcall that read the previous listener holds its own local reference. */
    if (previous) env->DeleteGlobalRef(previous);
}

jobject ListenerSlot::newLocalRef(JNIEnv* env) const {
    std::lock_guard<std::mutex> g(mMu);
    return mGlobal ? env->NewLocalRef(mGlobal) : nullptr;
}

bool ListenerSlot::isSet() const {
    std::lock_guard<std::mutex> g(mMu);
    return mGlobal != nullptr;
}

}  // namespace hamelinports
