/*
 * HamelinPortsImsService JNI bridge.
 *
 * Lifecycle: owns a long-lived resip::SipStack + DialogUsageManager
 * and runs the stack thread. Sockets are bound by addSipTransports.
 * IPsec is provided by the kernel xfrm policies that ims_xfrm
 * installs (TS 33.203 §7.4) — reSIProcate's TcpTransport speaks plain
 * TCP and the kernel transparently wraps in ESP based on port-pair
 * selectors. No FD hand-off needed.
 *
 * IMS REGISTER cycle (TS 33.203 §7.2):
 *   - REGISTER 1 cleartext to (pcscf, 5060) — outboundProxy initial
 *     value. Decorator adds sec-agree headers + Security-Client
 *     (UE's SPIs/ports/algorithms offer).
 *   - 401 lands → HamelinPortsImsAuthManager::handle:
 *       a) parses Security-Server (P-CSCF's chosen SPIs/ports/alg) +
 *          base64-decodes the AKA challenge nonce → RAND, AUTN
 *       b) JNI-upcalls AkaProvider.onAuthChallenge(...) — Java runs
 *          USIM AKA via TelephonyManager.getIccAuthentication AND
 *          installs the four kernel xfrm SAs via EspRoutingFix
 *       c) computes the AKAv1-MD5 digest from RES (Helper::make...)
 *       d) adds Authorization + Security-Verify to origRequest
 *       e) replaces the Route header with (pcscf, port-s-pcscf) so
 *          DUM auto-retries REGISTER 2 onto the IPsec-protected
 *          port pair (kernel ESP-wraps via SA1)
 *   - 200 OK → ClientRegistrationHandler::onSuccess
 */

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "rutil/Data.hxx"
#include "rutil/Log.hxx"
#include "rutil/AndroidLogger.hxx"
#include "rutil/TransportType.hxx"
#include "rutil/Coders.hxx"
#include "resip/stack/SipStack.hxx"
#include "resip/stack/Transport.hxx"
#include "resip/stack/TcpBaseTransport.hxx"
#include "resip/stack/Helper.hxx"
#include "resip/stack/Auth.hxx"
#include "resip/stack/Headers.hxx"
#include "resip/stack/MessageDecorator.hxx"
#include "resip/stack/Tuple.hxx"
#include "resip/stack/NameAddr.hxx"
#include "resip/stack/Uri.hxx"
#include "resip/stack/Token.hxx"
#include "resip/stack/MethodTypes.hxx"
#include "resip/stack/ExtensionParameter.hxx"
#include "resip/stack/SdpContents.hxx"
#include "resip/stack/PlainContents.hxx"
#include "resip/stack/OctetContents.hxx"
#include "resip/dum/DialogUsageManager.hxx"
#include "resip/dum/MasterProfile.hxx"
#include "resip/dum/UserProfile.hxx"
#include "resip/dum/ClientAuthManager.hxx"
#include "resip/dum/RegistrationHandler.hxx"
#include "resip/dum/ClientRegistration.hxx"
#include "resip/dum/InviteSessionHandler.hxx"
#include "resip/dum/ClientInviteSession.hxx"
#include "resip/dum/ServerInviteSession.hxx"
#include "resip/dum/AppDialogSetFactory.hxx"
#include "resip/dum/PagerMessageHandler.hxx"
#include "resip/dum/ClientPagerMessage.hxx"
#include "resip/dum/ServerPagerMessage.hxx"
#include "resip/dum/SubscriptionHandler.hxx"
#include "resip/dum/ClientSubscription.hxx"

#define LOG_TAG "HamelinPortsIms-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/* --------------------------------------------------------------------- *
 * JNI globals (set in JNI_OnLoad / nativeSetAkaProvider)
 * --------------------------------------------------------------------- */

JavaVM*   gJvm                  = nullptr;
jobject   gAkaProvider          = nullptr;   /* global ref */
jmethodID gOnAuthChallengeMethod = nullptr;
jclass    gAkaResultClass       = nullptr;   /* global ref */
jfieldID  gFieldRes             = nullptr;
jfieldID  gFieldCk              = nullptr;
jfieldID  gFieldIk              = nullptr;
jfieldID  gFieldAuts            = nullptr;
jobject   gRegListener              = nullptr;   /* global ref */
jmethodID gOnRegisteredMethod       = nullptr;
jmethodID gOnDeregisteredMethod     = nullptr;
jmethodID gOnRegisterExpiresReported = nullptr;
jobject   gCallListener         = nullptr;   /* global ref */
jmethodID gOnCallProvisional    = nullptr;
jmethodID gOnCallConnected      = nullptr;
jmethodID gOnCallTerminated     = nullptr;
jmethodID gOnCallFailure        = nullptr;
jmethodID gOnCallAnswer         = nullptr;
jmethodID gOnCallAnswerVideo    = nullptr;
jmethodID gOnRemoteReinvite     = nullptr;
/* MT (server-side) INVITE listener — separate from gCallListener
 * (which is set by whichever CallSession is currently active) so that
 * the MmTelFeature can stay subscribed to incoming-call events even
 * between sessions. Set by nativeSetIncomingCallListener. */
jobject   gIncomingCallListener = nullptr;
jmethodID gOnIncomingInvite      = nullptr;
jmethodID gOnIncomingInviteVideo = nullptr;
jmethodID gOnIncomingCancelled   = nullptr;
jobject   gSmsListener          = nullptr;   /* global ref */
jmethodID gOnSmsSendSuccess     = nullptr;
jmethodID gOnSmsSendFailure     = nullptr;
jmethodID gOnSmsIncoming        = nullptr;

/* P-Asserted-Identity of the most recently received MT MESSAGE.
 * Written by ServerPagerMessageHandler::onMessageArrived, read by
 * Java (via nativeGetLastMtPai) when it wants to send a separate
 * RP-ACK MESSAGE back to that specific SMSC instance per
 * TS 24.229 §5.3.1.3.4. Guarded by gLastMtPaiMu. */
std::mutex  gLastMtPaiMu;
std::string gLastMtPai;

/* Call-ID of the most recently received MT MESSAGE. Stamped on the
 * outbound RP-ACK as `In-Reply-To: <call-id>` so Mavenir's IP-SM-GW
 * can correlate the RP-ACK to the original MT RP-DATA transaction.
 * Without this correlator the IP-SM-GW returns 481 Call/Transaction
 * Does Not Exist on the second and subsequent UE-originated RP-ACK
 * MESSAGEs in the same registration. Guarded by gLastMtPaiMu. */
std::string gLastMtCallId;

/* Cached state from the most recent successful AKA round, used by
 * later requests (Security-Verify on INVITE/MESSAGE etc.). */
struct AkaCache {
    resip::Data res;
    resip::Data securityVerifyValue;  /* pre-formatted for Security-Verify */
    int         pcscfPortS = 0;       /* P-CSCF's port-s, for Route on REGISTER 2 */
};

/* Per-call/per-request data that Java pushes down to the decorator.
 * Held by reference; lifetime tied to the Bridge singleton. */
struct PaniCache {
    /* P-Access-Network-Info: 3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=...
     * Java refreshes this before each outbound INVITE/MESSAGE on
     * cellular IMS. */
    resip::Data utranCellId3gpp;
    /* P-Access-Network-Info: IEEE-802.11;i-wlan-node-id=<MAC-no-colons>
     * Java refreshes this before each outbound INVITE/MESSAGE on
     * Wi-Fi Calling (TS 24.229 §7.2A.4). Mutually exclusive with
     * utranCellId3gpp — setting one clears the other so the
     * decorator never emits a PANI that doesn't match the bound
     * underlying access. */
    resip::Data iWlanNodeId;
};

/* Wrap an IPv6 literal in brackets for use inside a SIP URI. reSIProcate's
 * URI parser splits on the first ':' to find the port, so a bare
 * "2a02:3018:0:25fc::3" becomes host="2a02" + garbage. RFC 3986 §3.2.2
 * requires "[...]" around an IPv6 literal in a URI authority. */
inline std::string formatHostForUri(const std::string& host) {
    if (host.find(':') == std::string::npos) return host;
    if (!host.empty() && host.front() == '[') return host;
    return "[" + host + "]";
}

/** Returns a JNIEnv* attached to the calling thread. */
JNIEnv* attachJni(bool* outAttached) {
    if (outAttached) *outAttached = false;
    if (!gJvm) return nullptr;
    JNIEnv* env = nullptr;
    int s = gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (s == JNI_OK) return env;
    if (s == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            if (outAttached) *outAttached = true;
            return env;
        }
    }
    return nullptr;
}

/* --------------------------------------------------------------------- *
 * Outbound message decorator.
 *
 * Per TS 33.203 / RFC 3329, every REGISTER carries:
 *   Proxy-Require: sec-agree
 *   Require:       sec-agree
 *   Supported:     sec-agree, gruu, path
 *   Security-Client: ipsec-3gpp; <UE's SPIs/ports/algorithms>
 *
 * The Security-Client value is configured at startRegister() time;
 * Java owns the SPI/port choices (it allocated them) so the value is
 * pre-formatted on the Java side and we just splat it in.
 *
 * Security-Verify is added by HamelinPortsImsAuthManager on REGISTER 2 (it
 * mutates origRequest directly); on subsequent IPsec-protected requests
 * (INVITE, MESSAGE, SUBSCRIBE), the decorator stamps it from the
 * AkaCache.
 *
 * NOTE: the Java SipClient evolved a long tail of additional headers
 * (PANI utran-cell-id, +g.3gpp.icsi-ref / mid-call / srvcc-* / video
 * Contact tags) that were added iteratively until calls worked. The
 * PoC never went back to verify which were actually required. Per
 * agreed plan, this decorator carries only the spec-mandated set; we
 * re-evaluate each additional header against a fresh wire diff.
 * --------------------------------------------------------------------- */

class ImsDecorator : public resip::MessageDecorator {
public:
    ImsDecorator(resip::Data securityClient, AkaCache& akaCache, PaniCache& paniCache,
                 unsigned int portCTransportKey, int portS)
        : mSecurityClient(std::move(securityClient)),
          mAkaCache(akaCache), mPaniCache(paniCache),
          mPortCTransportKey(portCTransportKey), mPortS(portS) {}

    void decorateMessage(resip::SipMessage& msg,
                         const resip::Tuple& /*src*/,
                         const resip::Tuple& /*dst*/,
                         const resip::Data&  /*sigcompId*/) override {
        const bool isRequest = msg.isRequest();
        /* Proxy-Require, Require and Supported are REQUEST-ONLY
         * negotiation headers (RFC 3261 §20.29/20.32/20.37). Mavenir
         * SMSC's OMA-CPM stack treats their presence on a response as
         * a malformed ack — MT SMS retry queue never drains when they
         * leak onto a response. Guard each header on isRequest. */
        if (isRequest) {
            msg.header(resip::h_ProxyRequires).push_back(resip::Token("sec-agree"));
            msg.header(resip::h_Requires).push_back(resip::Token("sec-agree"));
            msg.header(resip::h_Supporteds).push_back(resip::Token("sec-agree"));
            msg.header(resip::h_Supporteds).push_back(resip::Token("gruu"));
            msg.header(resip::h_Supporteds).push_back(resip::Token("path"));
        }

        /* Stamp Max-Forwards: 70 on 200 OK responses to MT MESSAGE.
         * Per RFC 3261 §20.22 Max-Forwards is a request-only header,
         * but Mavenir apparently relies on it being echoed. Without
         * it our 200 OK to an MT MESSAGE is ignored for retry-queue
         * purposes. Add it unconditionally on responses. */
        if (!isRequest) {
            msg.remove(resip::h_MaxForwards);
            msg.header(resip::h_MaxForwards).value() = 70;
        }

        /* IMS convention: topmost Via sent-by port must be UE portS (the
         * listen port, e.g. 6200), NOT the outbound source port (portC,
         * 6201). Mavenir P-CSCF matches this against the registered
         * Contact port and returns 404 on mismatch. reSIProcate sets
         * Via from the transport's bound port — since our portC
         * transport is the outbound one, Via defaults to portC. Patch
         * the topmost Via's port here, after reSIProcate has composed
         * the message. */
        if (isRequest && mPortS > 0 && !msg.header(resip::h_Vias).empty()) {
            msg.header(resip::h_Vias).front().sentPort() = mPortS;
        }

        /* REGISTER Contact feature tags (RFC 5727 / RFC 3840 /
         * TS 24.229):
         *
         *   +g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"
         *   video
         *   +g.3gpp.smsip
         *
         * Purposes:
         *  - icsi-ref advertises MMTel (RFC 5727 / TS 24.229) — without
         *    it some TAS deployments will not route INVITE to this UE.
         *  - video advertises video-media capability per RFC 3840;
         *    required for Phase C (video calling) to be picked.
         *  - smsip binds SMS-over-IP capability to this registration.
         *    Without it Mavenir drops MO MESSAGE to 404.
         *
         * Notably absent: srvcc-alerting / ps2cs-srvcc-orig-pre-alerting.
         * Those tags apply to in-dialog requests only (TS 24.237) and
         * are carried on INVITE Contact below, not REGISTER. */
        if (isRequest && msg.method() == resip::REGISTER &&
            msg.exists(resip::h_Contacts) &&
            !msg.header(resip::h_Contacts).empty()) {
            resip::NameAddr& contact = msg.header(resip::h_Contacts).front();
            const resip::ExtensionParameter pIcsi("+g.3gpp.icsi-ref");
            const resip::ExtensionParameter pVideo("video");
            const resip::ExtensionParameter pSmsip("+g.3gpp.smsip");
            if (!contact.exists(pIcsi)) {
                contact.param(pIcsi) =
                        "\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"";
            }
            if (!contact.exists(pVideo)) contact.param(pVideo) = "";
            if (!contact.exists(pSmsip)) contact.param(pSmsip) = "";
        }

        /* INVITE Contact: advertise MMTel + SRVCC capability per
         * RFC 5727 / RFC 3840 / TS 24.229 / TS 24.237:
         *   +g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"
         *   +g.3gpp.mid-call
         *   +g.3gpp.srvcc-alerting
         *   +g.3gpp.ps2cs-srvcc-orig-pre-alerting
         * Without the srvcc-* tags the IMS-AS does not allocate an
         * Access Transfer Update Function reservation, so even though
         * the modem can do the LTE→GSM/UMTS radio handover, the IMS
         * leg is never prepared and SRVCC silently fails — see
         * 3GPP TS 23.216 §5.3, TS 24.237. Phase I.1 of the migration
         * plan. */
        if (isRequest && msg.method() == resip::INVITE &&
            msg.exists(resip::h_Contacts) &&
            !msg.header(resip::h_Contacts).empty()) {
            resip::NameAddr& contact = msg.header(resip::h_Contacts).front();
            const resip::ExtensionParameter pIcsi("+g.3gpp.icsi-ref");
            const resip::ExtensionParameter pMidCall("+g.3gpp.mid-call");
            const resip::ExtensionParameter pSrvccAlert("+g.3gpp.srvcc-alerting");
            const resip::ExtensionParameter pSrvccPre(
                    "+g.3gpp.ps2cs-srvcc-orig-pre-alerting");
            if (!contact.exists(pIcsi)) {
                /* RFC 5727: ICSI carried as quoted-string with a
                 * URN-encoded value. urn%3A = URL-encoded ":" */
                contact.param(pIcsi) =
                        "\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"";
            }
            if (!contact.exists(pMidCall))    contact.param(pMidCall) = "";
            if (!contact.exists(pSrvccAlert)) contact.param(pSrvccAlert) = "";
            if (!contact.exists(pSrvccPre))   contact.param(pSrvccPre) = "";
        }

        if (isRequest && msg.method() == resip::REGISTER &&
            !mSecurityClient.empty()) {
            /* Security-Client is a comma-separated list of mechanisms.
             * Each mechanism is one Token. Push each separately so the
             * wire format is "mech1, mech2" rather than a single
             * malformed value. */
            std::string s(mSecurityClient.data(), mSecurityClient.size());
            size_t start = 0;
            while (start < s.size()) {
                size_t comma = s.find(',', start);
                size_t len = (comma == std::string::npos)
                        ? s.size() - start : comma - start;
                std::string oneMech = s.substr(start, len);
                size_t lo = oneMech.find_first_not_of(" \t");
                size_t hi = oneMech.find_last_not_of(" \t");
                if (lo != std::string::npos) {
                    oneMech = oneMech.substr(lo, hi - lo + 1);
                    resip::Token t;
                    resip::ParseBuffer pb(oneMech.data(), oneMech.size());
                    t.parse(pb);
                    msg.header(resip::h_SecurityClients).push_back(t);
                }
                if (comma == std::string::npos) break;
                start = comma + 1;
            }
        }

        /* Security-Verify on every IPsec-protected outbound request
         * EXCEPT REGISTER (the auth manager owns Security-Verify on
         * REGISTER 2 — it must match the request's Authorization). */
        if (isRequest &&
            msg.method() != resip::REGISTER &&
            !mAkaCache.securityVerifyValue.empty()) {
            resip::Token t;
            resip::ParseBuffer pb(mAkaCache.securityVerifyValue.data(),
                                  mAkaCache.securityVerifyValue.size());
            t.parse(pb);
            msg.header(resip::h_SecurityVerifies).push_back(t);
        }

        /* P-Access-Network-Info per TS 24.229. MMTel TAS uses this
         * for service-area authorization (proven required on
         * Telefonica DE — without it, MO calls route to the
         * announcement TAS as UNALLOCATED_NUMBER on cellular; on
         * Wi-Fi Calling the same INVITE silently drops if the access
         * type advertised here doesn't match the underlying network).
         * Java refreshes the cache before each outbound non-REGISTER.
         *
         * PANI must also be stamped on non-REGISTER RESPONSES
         * (e.g. the 200 OK returned to the SMSC's MT MESSAGE).
         * Without it on our 200 OK, the Mavenir/OMA-CPM SMSC does
         * not mark the MT as delivered and re-queues it endlessly.
         * Stamp on responses too, guarded on the same non-REGISTER
         * method filter.
         *
         * iWlanNodeId is checked first so callers that flipped to
         * Wi-Fi Calling get the IEEE-802.11 token even if the cellular
         * cache is still warm; setIwlanNodeIdForPani clears the
         * E-UTRAN field, but defensive priority here costs nothing. */
        if (msg.method() != resip::REGISTER) {
            if (!mPaniCache.iWlanNodeId.empty()) {
                resip::Token t("IEEE-802.11");
                const resip::ExtensionParameter pNodeId("i-wlan-node-id");
                t.param(pNodeId) = mPaniCache.iWlanNodeId;
                msg.header(resip::h_PAccessNetworkInfos).push_back(t);
            } else if (!mPaniCache.utranCellId3gpp.empty()) {
                resip::Token t("3GPP-E-UTRAN-FDD");
                const resip::ExtensionParameter pUtran("utran-cell-id-3gpp");
                t.param(pUtran) = mPaniCache.utranCellId3gpp;
                msg.header(resip::h_PAccessNetworkInfos).push_back(t);
            }
        }

        /* Stamp In-Reply-To on outbound RP-ACK MESSAGEs (recognised
         * by the request being a MESSAGE going to a captured
         * IP-SM-GW PAI). The body of an RP-ACK is just 2 bytes
         * (MTI=0x02 + RP-MR), so we use the routing target as the
         * cleanest disambiguator: any MESSAGE whose Request-URI
         * matches the most recent inbound MT's PAI is by definition
         * the SMS-layer ack of that MT and must reference its
         * Call-ID for IP-SM-GW state correlation. */
        if (isRequest && msg.method() == resip::MESSAGE) {
            std::string ruri;
            std::string mtCallId, mtPai;
            {
                std::lock_guard<std::mutex> g(gLastMtPaiMu);
                mtPai   = gLastMtPai;
                mtCallId = gLastMtCallId;
            }
            if (!mtPai.empty() && !mtCallId.empty()) {
                std::ostringstream oss;
                oss << msg.header(resip::h_RequestLine).uri();
                ruri = oss.str();
                if (ruri == mtPai) {
                    msg.header(resip::h_InReplyTo).value() = resip::Data(mtCallId);
                }
            }
        }
    }

    void rollbackMessage(resip::SipMessage& msg) override {
        msg.remove(resip::h_ProxyRequires);
        msg.remove(resip::h_Requires);
        msg.remove(resip::h_Supporteds);
        msg.remove(resip::h_SecurityClients);
        msg.remove(resip::h_SecurityVerifies);
        msg.remove(resip::h_PAccessNetworkInfos);
    }

    resip::MessageDecorator* clone() const override {
        return new ImsDecorator(*this);
    }

private:
    resip::Data  mSecurityClient;
    AkaCache&    mAkaCache;
    PaniCache&   mPaniCache;
    unsigned int mPortCTransportKey = 0;
    int          mPortS = 0;
};

/* --------------------------------------------------------------------- *
 * IMS auth manager. Handles AKAv1-MD5 challenges + REGISTER 2 retarget.
 * --------------------------------------------------------------------- */

class HamelinPortsImsAuthManager : public resip::ClientAuthManager {
public:
    HamelinPortsImsAuthManager(AkaCache& cache,
                          const std::string& pcscfHost,
                          unsigned int portCTransportKey)
        : mCache(cache), mPcscfHost(pcscfHost),
          mPortCTransportKey(portCTransportKey) {}

    bool handle(resip::UserProfile& userProfile,
                resip::SipMessage&  origRequest,
                const resip::SipMessage& response) override {
        const int code = response.header(resip::h_StatusLine).statusCode();
        LOGI("AKA handle: entry code=%d hasWWW=%d", code,
             response.exists(resip::h_WWWAuthenticates) ? 1 : 0);
        if (code != 401 && code != 407) {
            LOGI("AKA handle: not 401/407, deferring to base");
            return resip::ClientAuthManager::handle(userProfile, origRequest, response);
        }
        if (!response.exists(resip::h_WWWAuthenticates)) {
            LOGI("AKA handle: no WWW-Authenticate, deferring to base");
            return resip::ClientAuthManager::handle(userProfile, origRequest, response);
        }

        const auto& www = response.header(resip::h_WWWAuthenticates).front();
        const bool hasAlg = www.exists(resip::p_algorithm);
        const std::string algStr = hasAlg ? www.param(resip::p_algorithm).c_str() : "";
        LOGI("AKA handle: hasAlg=%d alg=%s", hasAlg, algStr.c_str());
        if (!hasAlg || www.param(resip::p_algorithm) != "AKAv1-MD5") {
            LOGI("AKA handle: alg not AKAv1-MD5, deferring to base");
            return resip::ClientAuthManager::handle(userProfile, origRequest, response);
        }

        /* One-shot trial: refuse to run AKA twice per Bridge lifetime.
         * One AKA round = REGISTER 1 → 401 → REGISTER 2. If a second
         * 401 arrives we bail instead of looping a new AKA cycle (which
         * would spam the P-CSCF and burn carrier-side rate limits). */
        if (mAkaRounds.fetch_add(1) >= 1) {
            LOGE("AKA: refusing second AKA round (one-shot trial guard)");
            return false;
        }

        try {
            return doAkaAuth(userProfile, origRequest, response, www);
        } catch (const std::exception& e) {
            LOGE("AKA auth threw: %s", e.what());
            return false;
        } catch (...) {
            LOGE("AKA auth threw unknown");
            return false;
        }
    }

private:
    bool doAkaAuth(resip::UserProfile& userProfile,
                   resip::SipMessage&  origRequest,
                   const resip::SipMessage& response,
                   const resip::Auth& www) {
        if (!www.exists(resip::p_nonce)) {
            LOGE("AKA: WWW-Authenticate missing nonce");
            return false;
        }
        const resip::Data nonceRaw = resip::Base64Coder::decode(
                www.param(resip::p_nonce));
        if (nonceRaw.size() < 32) {
            LOGE("AKA: nonce too short (%u)", (unsigned)nonceRaw.size());
            return false;
        }
        /* TS 33.203: nonce = base64(RAND || AUTN || serverdata) */

        /* Pull Security-Server params so Java can install xfrm SAs. */
        long serverSpiC = 0, serverSpiS = 0;
        int  serverPortC = 0, serverPortS = 0;
        std::string alg;
        if (response.exists(resip::h_SecurityServers) &&
            !response.header(resip::h_SecurityServers).empty()) {
            const auto& sec = response.header(resip::h_SecurityServers).front();
            const resip::ExtensionParameter pAlg("alg");
            const resip::ExtensionParameter pSpiC("spi-c");
            const resip::ExtensionParameter pSpiS("spi-s");
            const resip::ExtensionParameter pPortC("port-c");
            const resip::ExtensionParameter pPortS("port-s");
            if (sec.exists(pAlg))   alg = sec.param(pAlg).c_str();
            if (sec.exists(pSpiC))  serverSpiC = std::stol(sec.param(pSpiC).c_str());
            if (sec.exists(pSpiS))  serverSpiS = std::stol(sec.param(pSpiS).c_str());
            if (sec.exists(pPortC)) serverPortC = std::stoi(sec.param(pPortC).c_str());
            if (sec.exists(pPortS)) serverPortS = std::stoi(sec.param(pPortS).c_str());
        } else {
            LOGE("AKA: 401 missing Security-Server");
            return false;
        }
        LOGI("Security-Server: alg=%s spi-c=%ld spi-s=%ld port-c=%d port-s=%d",
             alg.c_str(), serverSpiC, serverSpiS, serverPortC, serverPortS);

        /* JNI upcall: Java runs USIM AKA AND installs the kernel xfrm
         * policies for the four SAs (TS 33.203 §7.4) before returning. */
        if (!gAkaProvider || !gOnAuthChallengeMethod) {
            LOGE("AKA: no provider registered");
            return false;
        }
        bool attached = false;
        JNIEnv* env = attachJni(&attached);
        if (!env) {
            LOGE("AKA: failed to attach JNI");
            return false;
        }

        jbyteArray jRand = env->NewByteArray(16);
        jbyteArray jAutn = env->NewByteArray(16);
        env->SetByteArrayRegion(jRand, 0, 16, (const jbyte*)nonceRaw.data());
        env->SetByteArrayRegion(jAutn, 0, 16, (const jbyte*)(nonceRaw.data() + 16));
        jstring jAlg = env->NewStringUTF(alg.c_str());

        jobject jResult = env->CallObjectMethod(
                gAkaProvider, gOnAuthChallengeMethod,
                jRand, jAutn,
                (jlong)serverSpiC, (jlong)serverSpiS,
                (jint)serverPortC, (jint)serverPortS,
                jAlg);

        env->DeleteLocalRef(jRand);
        env->DeleteLocalRef(jAutn);
        env->DeleteLocalRef(jAlg);

        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            if (attached) gJvm->DetachCurrentThread();
            LOGE("AKA: provider threw");
            return false;
        }
        if (!jResult) {
            if (attached) gJvm->DetachCurrentThread();
            LOGE("AKA: provider returned null");
            return false;
        }

        jbyteArray jRes = (jbyteArray)env->GetObjectField(jResult, gFieldRes);
        env->DeleteLocalRef(jResult);
        if (!jRes) {
            if (attached) gJvm->DetachCurrentThread();
            LOGE("AKA: result missing res");
            return false;
        }
        jsize resLen = env->GetArrayLength(jRes);
        std::vector<char> resBuf(resLen);
        env->GetByteArrayRegion(jRes, 0, resLen, (jbyte*)resBuf.data());
        env->DeleteLocalRef(jRes);
        if (attached) gJvm->DetachCurrentThread();

        const resip::Data res(resBuf.data(), resBuf.size());

        /* Compute the digest with RES as password. AKAv1-MD5 doesn't
         * carry qop or nonce-count per RFC 3310 §3.1, so cnonce/qop/nc
         * stay empty. */
        resip::Auth authHeader;
        const resip::Data& impi =
                userProfile.getDigestCredential(www.param(resip::p_realm)).user;
        resip::Helper::makeChallengeResponseAuth(
                origRequest,
                impi, res, www,
                resip::Data::Empty,        /* cnonce */
                resip::Data::Empty,        /* qop */
                resip::Data::Empty,        /* ncString */
                authHeader);
        authHeader.param(resip::p_algorithm) = "AKAv1-MD5";

        origRequest.header(resip::h_Authorizations).clear();
        origRequest.header(resip::h_Authorizations).push_back(authHeader);

        /* Security-Verify echoes Security-Server byte-for-byte (TS 33.203 §7.2.2).
         * Stash for non-REGISTER decoration too. */
        resip::Data secVerify(
                resip::Data("ipsec-3gpp;q=0.088;alg=") + resip::Data(alg) +
                ";mod=trans" +
                ";spi-c=" + resip::Data(std::to_string(serverSpiC)) +
                ";spi-s=" + resip::Data(std::to_string(serverSpiS)) +
                ";port-c=" + resip::Data(std::to_string(serverPortC)) +
                ";port-s=" + resip::Data(std::to_string(serverPortS)));
        {
            resip::Token t;
            resip::ParseBuffer pb(secVerify.data(), secVerify.size());
            t.parse(pb);
            origRequest.header(resip::h_SecurityVerifies).clear();
            origRequest.header(resip::h_SecurityVerifies).push_back(t);
        }
        mCache.res = res;
        mCache.securityVerifyValue = secVerify;
        mCache.pcscfPortS = serverPortS;

        /* Bump CSeq for REGISTER 2. reSIProcate's base
         * ClientAuthManager::handle() auto-increments CSeq on
         * challenge-response. Our override does the AKA work but
         * doesn't call the base, so we must do this explicitly —
         * otherwise the server sees two REGISTERs with identical CSeq
         * and rejects with 403. */
        origRequest.header(resip::h_CSeq).sequence()++;

        /* Re-target ROUND 2 onto the IPsec-protected port-pair —
         * REGISTER ONLY. INVITE/MESSAGE are already routed via the
         * IPsec port pair (the Service-Route from the REGISTER 200
         * already goes through pcscf:port-s); for those, just leave
         * the existing route alone and let DUM retry on the same
         * connection. */
        if (origRequest.method() == resip::REGISTER) {
            /* transport=tcp pins all in-dialog routing to TCP; see the
             * setOutboundProxy comment in start() for the full reason. */
            std::string pcscfUri = std::string("sip:") + formatHostForUri(mPcscfHost) + ":" +
                                   std::to_string(serverPortS) + ";transport=tcp;lr";
            resip::NameAddr newRoute{resip::Data(pcscfUri)};
            origRequest.header(resip::h_Routes).clear();
            origRequest.header(resip::h_Routes).push_front(newRoute);

            /* DUM's sendUsingOutboundIfAppropriate uses the profile's
             * outbound proxy URI as the actual TCP destination (line
             * ~1165 in DialogUsageManager.cxx), BYPASSING any Route
             * header. Our profile currently points at :5060 from
             * startRegister. Update it to the IPsec port so REGISTER 2
             * opens a connection to port-s, matching the xfrm out-policy. */
            userProfile.setOutboundProxy(resip::Uri(resip::Data(pcscfUri)));

            /* Pin REGISTER 2 to the portC transport so the source port
             * is UE portC (not ephemeral) and the xfrm (portC↔port-s)
             * policy matches, producing an ESP-wrapped TCP SYN. */
            if (mPortCTransportKey != 0) {
                resip::Tuple dst(resip::Data(mPcscfHost), serverPortS, resip::TCP);
                dst.mTransportKey = mPortCTransportKey;
                origRequest.setDestination(dst);
                LOGI("AKA: REGISTER 2 destination pinned to portC transport key=%u "
                     "dst=[%s]:%d", mPortCTransportKey, mPcscfHost.c_str(), serverPortS);
            }
            LOGI("AKA: REGISTER round 2 retargeted to %s (outbound-proxy updated)",
                 pcscfUri.c_str());
        } else {
            LOGI("AKA: re-auth on %s — no route change",
                 resip::getMethodName(origRequest.method()).c_str());
        }
        return true;
    }

    AkaCache&        mCache;
    std::string      mPcscfHost;
    unsigned int     mPortCTransportKey = 0;
    std::atomic<int> mAkaRounds{0};
};

/* --------------------------------------------------------------------- *
 * Minimal ClientRegistrationHandler — logs lifecycle to logcat.
 * DUM throws from makeRegistration if no handler is set.
 * --------------------------------------------------------------------- */

class HamelinPortsRegHandler : public resip::ClientRegistrationHandler {
public:
    /** Bridge sets this to receive the public identity on each 200 OK. */
    std::function<void(const std::string&)> onPublicIdentity;
    /** Bridge sets this so {@code nativeRefreshRegister} has a handle to
     *  call {@code requestRefresh} on. Valid between first 200 OK and
     *  onRemoved/onFailure. */
    std::function<void(resip::ClientRegistrationHandle)> onHandle;
    /** Bridge sets this to report the Expires we negotiated with the
     *  P-CSCF so Java can schedule its own refresh timer at T-60 s. */
    std::function<void(int)> onExpiresReported;

    void onSuccess(resip::ClientRegistrationHandle h,
                   const resip::SipMessage& response) override {
        const int code = response.header(resip::h_StatusLine).statusCode();
        LOGI("REGISTER onSuccess: %d %s", code,
             response.header(resip::h_StatusLine).reason().c_str());

        /* Capture the handle for requestRefresh. reSIProcate's DUM
         * should auto-refresh internally but has been observed to
         * silently skip it in the field (5 h without refresh despite
         * Expires=3600 requested); an explicit Java-driven timer on
         * top is belt-and-braces. */
        if (onHandle) onHandle(h);

        /* Negotiated Expires — Contact[0].expires param if present,
         * else the top-level Expires header, else our requested value.
         * Tell Java so it can schedule a refresh a minute before. */
        int negotiatedExpires = 0;
        try {
            if (response.exists(resip::h_Contacts)
                && !response.header(resip::h_Contacts).empty()) {
                const resip::NameAddr& c = response.header(resip::h_Contacts).front();
                if (c.exists(resip::p_expires)) {
                    negotiatedExpires = c.param(resip::p_expires);
                }
            }
            if (negotiatedExpires == 0
                && response.exists(resip::h_Expires)) {
                negotiatedExpires = response.header(resip::h_Expires).value();
            }
        } catch (...) {}
        LOGI("REGISTER negotiated Expires: %d s", negotiatedExpires);
        if (onExpiresReported) onExpiresReported(negotiatedExpires);

        /* Extract the first P-Associated-URI. Host part is the
         * carrier's public domain (e.g. telefonica.de) — different
         * from the home domain (ims.mnc003.mcc262.3gppnetwork.org).
         * MESSAGE / INVITE From + P-Preferred-Identity MUST be this
         * form (not the IMPU), otherwise the P-CSCF returns 404. */
        std::string associatedUri;
        if (response.exists(resip::h_PAssociatedUris) &&
            !response.header(resip::h_PAssociatedUris).empty()) {
            std::ostringstream ss;
            ss << response.header(resip::h_PAssociatedUris).front().uri();
            associatedUri = ss.str();
            if (onPublicIdentity) onPublicIdentity(associatedUri);
        }
        upcallRegistered(associatedUri);
    }
    void onFailure(resip::ClientRegistrationHandle h,
                   const resip::SipMessage& response) override {
        const int code = response.header(resip::h_StatusLine).statusCode();
        const std::string reason =
                response.header(resip::h_StatusLine).reason().c_str();
        /* Retry-After is an unsigned integer of seconds (RFC 3261
         * §20.33). RFC 3261 §21.5.4 describes its role on 503: the
         * server indicates when the request may be retried. Forward
         * the raw value; the Java controller applies the 3GPP TS
         * 24.229 §4.2A wait and §5.1.1.4 reregistration policy. */
        int retryAfterSec = 0;
        try {
            if (response.exists(resip::h_RetryAfter)) {
                retryAfterSec = (int)response.header(resip::h_RetryAfter).value();
            }
        } catch (...) {}
        LOGE("REGISTER onFailure: %d %s retryAfter=%d",
             code, reason.c_str(), retryAfterSec);
        upcallDeregistered(code, reason, retryAfterSec);
    }
    void onRemoved(resip::ClientRegistrationHandle h,
                   const resip::SipMessage& response) override {
        LOGI("REGISTER onRemoved");
        upcallDeregistered(0, "removed", 0);
    }
    int onRequestRetry(resip::ClientRegistrationHandle h,
                       int retrySeconds,
                       const resip::SipMessage& response) override {
        LOGI("REGISTER onRequestRetry in %d s", retrySeconds);
        return -1;
    }
    void onFlowTerminated(resip::ClientRegistrationHandle) override {
        /* The P-CSCF TCP flow carrying our registration died (NAT idle
         * GC, P-CSCF reboot, or just a long silence past the carrier's
         * socket timeout). reSIProcate won't auto-recover — future
         * INVITE/MESSAGE would try to send on the dead flow, fail, and
         * stay in limbo. Signal Java with a distinctive reason so the
         * ImsRegistrationController tears down and re-REGISTERs. */
        LOGI("REGISTER onFlowTerminated — signalling re-register");
        upcallDeregistered(0, "flow-terminated", 0);
    }

private:
    void upcallRegistered(const std::string& associatedUri) {
        if (!gRegListener || !gOnRegisteredMethod) return;
        bool attached = false;
        JNIEnv* env = attachJni(&attached);
        if (!env) return;
        jstring jAu = env->NewStringUTF(associatedUri.c_str());
        env->CallVoidMethod(gRegListener, gOnRegisteredMethod, jAu);
        env->DeleteLocalRef(jAu);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        if (attached) gJvm->DetachCurrentThread();
    }
    void upcallDeregistered(int code, const std::string& reason,
                            int retryAfterSec) {
        if (!gRegListener || !gOnDeregisteredMethod) return;
        bool attached = false;
        JNIEnv* env = attachJni(&attached);
        if (!env) return;
        jstring jr = env->NewStringUTF(reason.c_str());
        env->CallVoidMethod(gRegListener, gOnDeregisteredMethod,
                            (jint)code, jr, (jint)retryAfterSec);
        env->DeleteLocalRef(jr);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        if (attached) gJvm->DetachCurrentThread();
    }
};

/* --------------------------------------------------------------------- *
 * InviteSessionHandler — drives the MO call lifecycle. Most pure-virtual
 * overrides are no-ops; we only react to provisional/connected/failure/
 * terminated/answer (the path needed for an outbound call to ring,
 * connect, and either be ended by us or the remote).
 * --------------------------------------------------------------------- */

class HamelinPortsInviteHandler : public resip::InviteSessionHandler {
public:
    HamelinPortsInviteHandler() = default;

    resip::ClientInviteSessionHandle takeActive() {
        std::lock_guard<std::mutex> g(mMu);
        return mActive;
    }

    /** Drop the stored handle so a later takeActive() returns
     *  NotValid(). Called on Bridge teardown — the DialogUsageManager
     *  and its HandleManager are about to be destroyed, so any
     *  stored handle would dereference freed memory on isValid(). */
    void clearActive() {
        std::lock_guard<std::mutex> g(mMu);
        mActive = resip::ClientInviteSessionHandle::NotValid();
        mServerByCallId.clear();
    }

    /** Look up a live ServerInviteSession by Call-ID. Used by the
     *  Java-side accept/reject/progress paths to find the handle
     *  recorded in onNewSession(server). Returns NotValid() if no
     *  match — the upcalls must tolerate that (e.g. after a race
     *  where the remote CANCEL'd between notify and accept). */
    resip::ServerInviteSessionHandle takeServer(const std::string& callId) {
        std::lock_guard<std::mutex> g(mMu);
        auto it = mServerByCallId.find(callId);
        return (it != mServerByCallId.end())
                ? it->second : resip::ServerInviteSessionHandle::NotValid();
    }

    void dropServer(const std::string& callId) {
        std::lock_guard<std::mutex> g(mMu);
        mServerByCallId.erase(callId);
    }

private:
    void upcall(jmethodID m, int code, const std::string& reason) {
        if (!gCallListener || !m) return;
        bool attached = false;
        JNIEnv* env = attachJni(&attached);
        if (!env) return;
        jstring jr = env->NewStringUTF(reason.c_str());
        env->CallVoidMethod(gCallListener, m, (jint)code, jr);
        env->DeleteLocalRef(jr);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        if (attached) gJvm->DetachCurrentThread();
    }

public:
    void onNewSession(resip::ClientInviteSessionHandle h,
                      resip::InviteSession::OfferAnswerType, const resip::SipMessage&) override {
        std::lock_guard<std::mutex> g(mMu);
        mActive = h;
        LOGI("INVITE onNewSession (client)");
    }
    void onNewSession(resip::ServerInviteSessionHandle h,
                      resip::InviteSession::OfferAnswerType,
                      const resip::SipMessage& msg) override {
        /* Capture the ServerInviteSessionHandle keyed on Call-ID so
         * the Java side can later accept/reject/progress by Call-ID
         * without holding a C++ pointer. From/display extracted for
         * the framework's incoming-call UI. SDP parsing waits for
         * onOffer — at onNewSession time reSIProcate hasn't yet run
         * its SDP validation. */
        std::string callId;
        std::string fromUri;
        try {
            if (msg.exists(resip::h_CallId)) {
                callId = msg.header(resip::h_CallId).value().c_str();
            }
            if (msg.exists(resip::h_From)) {
                std::stringstream ss;
                msg.header(resip::h_From).uri().encode(ss);
                fromUri = ss.str();
            }
        } catch (...) {}
        LOGI("INVITE onNewSession (server) Call-ID=%s From=%s",
             callId.c_str(), fromUri.c_str());
        {
            std::lock_guard<std::mutex> g(mMu);
            if (!callId.empty()) mServerByCallId[callId] = h;
            mPendingIncomingCallId = callId;
            mPendingIncomingFromUri = fromUri;
        }
    }
    void onFailure(resip::ClientInviteSessionHandle, const resip::SipMessage& msg) override {
        const int code = msg.header(resip::h_StatusLine).statusCode();
        const std::string r = msg.header(resip::h_StatusLine).reason().c_str();
        LOGE("INVITE onFailure: %d %s", code, r.c_str());
        upcall(gOnCallFailure, code, r);
    }
    void onEarlyMedia(resip::ClientInviteSessionHandle, const resip::SipMessage&,
                      const resip::SdpContents&) override {
        LOGI("INVITE onEarlyMedia");
    }
    void onProvisional(resip::ClientInviteSessionHandle, const resip::SipMessage& msg) override {
        const int code = msg.header(resip::h_StatusLine).statusCode();
        const std::string r = msg.header(resip::h_StatusLine).reason().c_str();
        LOGI("INVITE onProvisional: %d %s", code, r.c_str());
        upcall(gOnCallProvisional, code, r);
    }
    void onConnected(resip::ClientInviteSessionHandle h, const resip::SipMessage& msg) override {
        const int code = msg.header(resip::h_StatusLine).statusCode();
        LOGI("INVITE onConnected (client): %d", code);
        /* Re-anchor mActive to the fork that actually won. When the
         * network forks our INVITE (o2-de Mavenir does on every MO),
         * onNewSession(ClientInviteSessionHandle) fires for EACH branch
         * and the last one clobbers mActive — which is frequently a
         * still-ringing branch, not the one that got 200 OK. Without
         * this re-anchor, endCall() sends BYE on the losing fork and
         * the winning (connected) dialog stays live until the network
         * session-timer BYEs us ~10 s later — observed as "audio kept
         * going for 10 s after user hangup". */
        {
            std::lock_guard<std::mutex> g(mMu);
            mActive = h;
        }
        try {
            if (msg.exists(resip::h_SessionExpires)) {
                std::stringstream ss;
                msg.header(resip::h_SessionExpires).encode(ss);
                LOGI("INVITE 200 OK Session-Expires: %s", ss.str().c_str());
            } else {
                LOGI("INVITE 200 OK has NO Session-Expires header");
            }
            if (msg.exists(resip::h_MinSE)) {
                LOGI("INVITE 200 OK Min-SE: %d",
                     (int)msg.header(resip::h_MinSE).value());
            }
            if (msg.exists(resip::h_Requires)) {
                std::stringstream ss;
                for (const auto& t : msg.header(resip::h_Requires)) {
                    ss << t.value().c_str() << " ";
                }
                LOGI("INVITE 200 OK Require: %s", ss.str().c_str());
            }
            if (msg.exists(resip::h_Supporteds)) {
                std::stringstream ss;
                for (const auto& t : msg.header(resip::h_Supporteds)) {
                    ss << t.value().c_str() << " ";
                }
                LOGI("INVITE 200 OK Supported: %s", ss.str().c_str());
            }
        } catch (const std::exception& e) {
            LOGE("onConnected dump failed: %s", e.what());
        }
        upcall(gOnCallConnected, code, "");
    }
    void onConnected(resip::InviteSessionHandle h, const resip::SipMessage&) override {
        /* Fires on SERVER side when the remote ACK confirms our 200
         * OK (dialog now established). The MO side uses the
         * ClientInviteSession overload; here we cover MT and route
         * the "call-is-up" event to the same upcall so the Java
         * MT session can transition to ACTIVE and start media. */
        LOGI("INVITE onConnected (server-side ACK received)");
        upcall(gOnCallConnected, 200, "");
    }
    void onTerminated(resip::InviteSessionHandle,
                      resip::InviteSessionHandler::TerminatedReason reason,
                      const resip::SipMessage* msg) override {
        LOGI("INVITE onTerminated reason=%d", (int)reason);
        if (msg != nullptr) {
            try {
                std::stringstream ss;
                msg->encodeBrief(ss);
                LOGI("INVITE onTerminated msg brief: %s", ss.str().c_str());
                if (msg->exists(resip::h_Reasons)) {
                    for (const auto& r : msg->header(resip::h_Reasons)) {
                        std::stringstream rs;
                        r.encode(rs);
                        LOGI("INVITE onTerminated Reason: %s", rs.str().c_str());
                    }
                }
            } catch (const std::exception& e) {
                LOGE("INVITE onTerminated dump failed: %s", e.what());
            }
        }
        std::string cancelledCallId;
        bool wasMt = false;
        {
            std::lock_guard<std::mutex> g(mMu);
            mActive = resip::ClientInviteSessionHandle::NotValid();
            /* MT side: on terminate, drop the Call-ID → server-handle
             * entry so a subsequent accept/reject on a stale Call-ID
             * returns NotValid cleanly. If msg is non-null we can
             * pull the Call-ID from it; otherwise clear the pending
             * incoming state since no accept will come now. */
            if (msg != nullptr) {
                try {
                    if (msg->exists(resip::h_CallId)) {
                        std::string cid =
                                msg->header(resip::h_CallId).value().c_str();
                        if (mServerByCallId.count(cid) > 0) {
                            wasMt = true;
                            cancelledCallId = cid;
                        }
                        mServerByCallId.erase(cid);
                        if (mPendingIncomingCallId == cid) {
                            mPendingIncomingCallId.clear();
                            mPendingIncomingFromUri.clear();
                        }
                    }
                } catch (...) {}
            } else {
                /* No msg means a local teardown without a wire event
                 * — safest is to drop all server entries (we track
                 *   only one MT at a time anyway). */
                if (!mServerByCallId.empty()) {
                    wasMt = true;
                    cancelledCallId = mServerByCallId.begin()->first;
                }
                mServerByCallId.clear();
                mPendingIncomingCallId.clear();
                mPendingIncomingFromUri.clear();
            }
        }
        /* MT pre-accept CANCEL / BYE: framework still thinks the
         * session is ringing. Fire onIncomingCancelled so Java can
         * callSessionTerminated, releasing the ring and updating
         * Telecom state. Attaching to the MO listener upcall
         * (gOnCallTerminated) would not route because MT doesn't
         * install a CallSessionListener until accept(). */
        if (wasMt && gIncomingCallListener && gOnIncomingCancelled) {
            bool attached = false;
            JNIEnv* env = attachJni(&attached);
            if (env) {
                jstring jCid = env->NewStringUTF(cancelledCallId.c_str());
                env->CallVoidMethod(gIncomingCallListener,
                        gOnIncomingCancelled, jCid, (jint)reason);
                env->DeleteLocalRef(jCid);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                }
                if (attached) gJvm->DetachCurrentThread();
            }
        }
        upcall(gOnCallTerminated, (int)reason, "");
    }
    void onForkDestroyed(resip::ClientInviteSessionHandle) override {}
    void onRedirected(resip::ClientInviteSessionHandle, const resip::SipMessage&) override {}
    void onAnswer(resip::InviteSessionHandle, const resip::SipMessage&,
                  const resip::SdpContents& sdp) override {
        LOGI("INVITE onAnswer (remote SDP received)");

        /* Walk the session's m-lines once. Audio fields drive the
         * AMR-WB session (C.1 baseline); video fields — if present
         * and accepted (port > 0) — drive a second SESSION_TYPE_VIDEO
         * imsmedia session (C.2). Everything is logged so tcpdump
         * captures can be cross-checked against our parser. */
        std::string audioIp, videoIp;
        int audioPort = 0, audioRtcpPort = 0, audioPt = -1, audioRate = 0;
        int videoPort = 0, videoRtcpPort = 0, videoPt = -1, videoRate = 0;
        std::string audioCodec, audioFmtp;
        std::string videoCodec, videoFmtp;

        const auto& session = sdp.session();
        for (const auto& m : session.media()) {
            const std::string mname(m.name().c_str());
            int mport = m.port();
            int mrtcp = m.firstRtcpPort();
            std::string mip;
            const auto conns = m.getConnections();
            if (!conns.empty()) mip = conns.front().getAddress().c_str();
            if (mname == "audio") {
                audioIp = mip; audioPort = mport; audioRtcpPort = mrtcp;
                for (const auto& c : m.codecs()) {
                    const std::string n(c.getName().c_str());
                    if (n == "telephone-event") continue;
                    audioPt    = c.payloadType();
                    audioRate  = c.getRate();
                    audioCodec = n;
                    audioFmtp  = c.parameters().c_str();
                    break;
                }
                LOGI("onAnswer m=audio port=%d rtcp=%d pt=%d %s/%d fmtp=[%s]",
                     audioPort, audioRtcpPort, audioPt,
                     audioCodec.c_str(), audioRate, audioFmtp.c_str());
            } else if (mname == "video") {
                videoIp = mip; videoPort = mport; videoRtcpPort = mrtcp;
                const auto& codecs = m.codecs();
                if (!codecs.empty()) {
                    const auto& c = codecs.front();
                    videoPt    = c.payloadType();
                    videoRate  = c.getRate();
                    videoCodec = c.getName().c_str();
                    videoFmtp  = c.parameters().c_str();
                }
                /* port=0 means "video rejected by remote" per
                 * RFC 3264 §6.1. Keep the parsed values so Java
                 * can log "video rejected" distinctively. */
                LOGI("onAnswer m=video port=%d rtcp=%d pt=%d %s/%d fmtp=[%s]%s",
                     videoPort, videoRtcpPort, videoPt,
                     videoCodec.c_str(), videoRate, videoFmtp.c_str(),
                     (videoPort == 0) ? " (REJECTED)" : "");
            } else {
                LOGI("onAnswer m=%s (ignored, port=%d)", mname.c_str(), mport);
            }
        }

        if (audioPt < 0 || audioPort == 0 || audioIp.empty()) {
            LOGE("onAnswer: SDP missing audio info (pt=%d port=%d ip=%s)",
                 audioPt, audioPort, audioIp.c_str());
            return;
        }

        if (!gCallListener || !gOnCallAnswer) return;
        bool attached = false;
        JNIEnv* env = attachJni(&attached);
        if (!env) return;

        /* Audio upcall — unchanged signature for compatibility. */
        {
            jstring jIp    = env->NewStringUTF(audioIp.c_str());
            jstring jName  = env->NewStringUTF(audioCodec.c_str());
            jstring jFmtp  = env->NewStringUTF(audioFmtp.c_str());
            env->CallVoidMethod(gCallListener, gOnCallAnswer,
                                jIp, (jint)audioPort, (jint)audioRtcpPort,
                                (jint)audioPt, (jint)audioRate, jName, jFmtp);
            env->DeleteLocalRef(jIp);
            env->DeleteLocalRef(jName);
            env->DeleteLocalRef(jFmtp);
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        }

        /* Video upcall — only when a video m-line was offered AND
         * accepted. gOnCallAnswerVideo resolved via GetMethodID at
         * setCallSessionListener time; OK to no-op if not present. */
        if (videoPort > 0 && gOnCallAnswerVideo) {
            const std::string& vip = videoIp.empty() ? audioIp : videoIp;
            jstring jIp    = env->NewStringUTF(vip.c_str());
            jstring jName  = env->NewStringUTF(videoCodec.c_str());
            jstring jFmtp  = env->NewStringUTF(videoFmtp.c_str());
            env->CallVoidMethod(gCallListener, gOnCallAnswerVideo,
                                jIp, (jint)videoPort, (jint)videoRtcpPort,
                                (jint)videoPt, (jint)videoRate, jName, jFmtp);
            env->DeleteLocalRef(jIp);
            env->DeleteLocalRef(jName);
            env->DeleteLocalRef(jFmtp);
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        }

        if (attached) gJvm->DetachCurrentThread();
    }
    void onOffer(resip::InviteSessionHandle h, const resip::SipMessage& msg,
                 const resip::SdpContents& sdp) override {
        /* Two callers: (a) initial MT INVITE with SDP offer (UAS,
         * mPendingIncomingCallId set); (b) mid-call re-INVITE with
         * new SDP offer (UAC, pending slot empty, existing session
         * in ReceivedReinvite state). Distinguish by the pending
         * field. */
        std::string callId, fromUri;
        bool isReinvite = false;
        {
            std::lock_guard<std::mutex> g(mMu);
            callId = mPendingIncomingCallId;
            fromUri = mPendingIncomingFromUri;
            /* Leave the pending fields populated — accept/reject
             * still need them for logging. Cleared on terminate. */
        }
        if (callId.empty()) {
            /* Client-side session receiving an offer = mid-call
             * re-INVITE. Extract Call-ID from the SIP message for
             * logging; proceed to parse + upcall as a session-
             * modify. */
            isReinvite = true;
            try {
                if (msg.exists(resip::h_CallId))
                    callId = msg.header(resip::h_CallId).value().c_str();
            } catch (...) {}
            LOGI("onOffer: mid-call re-INVITE on Call-ID=%s", callId.c_str());
        }

        /* Audio and (optionally) video extracted from the incoming
         * SDP offer. We prefer AMR-WB on audio; video is whatever
         * codec the remote listed first (typically H.264 Baseline
         * per GSMA PRD IR.94) — we mirror it in the SDP answer. */
        std::string audioIp, videoIp;
        int audioPort = 0, audioRtcpPort = 0, audioPt = -1, audioRate = 0;
        int videoPort = 0, videoRtcpPort = 0, videoPt = -1, videoRate = 0;
        std::string audioCodec, audioFmtp;
        std::string videoCodec, videoFmtp;
        int fbPt = -1, fbRate = 0;       /* audio fallback if no AMR-WB */
        std::string fbName, fbFmtp;

        const auto& session = sdp.session();
        for (const auto& m : session.media()) {
            const std::string mname(m.name().c_str());
            int mport = m.port();
            int mrtcp = m.firstRtcpPort();
            std::string mip;
            const auto conns = m.getConnections();
            if (!conns.empty()) mip = conns.front().getAddress().c_str();

            if (mname == "audio") {
                audioIp = mip; audioPort = mport; audioRtcpPort = mrtcp;
                for (const auto& c : m.codecs()) {
                    const std::string n(c.getName().c_str());
                    if (n == "telephone-event") continue;
                    if (n == "AMR-WB") {
                        audioPt    = c.payloadType();
                        audioRate  = c.getRate();
                        audioCodec = n;
                        audioFmtp  = c.parameters().c_str();
                        break;
                    }
                    if (fbPt < 0) {
                        fbPt   = c.payloadType();
                        fbRate = c.getRate();
                        fbName = n;
                        fbFmtp = c.parameters().c_str();
                    }
                }
                if (audioPt < 0 && fbPt >= 0) {
                    audioPt    = fbPt;
                    audioRate  = fbRate;
                    audioCodec = fbName;
                    audioFmtp  = fbFmtp;
                }
                LOGI("MT onOffer m=audio port=%d rtcp=%d pt=%d %s/%d fmtp=[%s]",
                     audioPort, audioRtcpPort, audioPt,
                     audioCodec.c_str(), audioRate, audioFmtp.c_str());
            } else if (mname == "video") {
                videoIp = mip; videoPort = mport; videoRtcpPort = mrtcp;
                const auto& codecs = m.codecs();
                if (!codecs.empty()) {
                    const auto& c = codecs.front();
                    videoPt    = c.payloadType();
                    videoRate  = c.getRate();
                    videoCodec = c.getName().c_str();
                    videoFmtp  = c.parameters().c_str();
                }
                LOGI("MT onOffer m=video port=%d rtcp=%d pt=%d %s/%d fmtp=[%s]",
                     videoPort, videoRtcpPort, videoPt,
                     videoCodec.c_str(), videoRate, videoFmtp.c_str());
            } else {
                LOGI("MT onOffer m=%s (ignored, port=%d)", mname.c_str(), mport);
            }
        }

        if (audioPt < 0 || audioPort == 0 || audioIp.empty()) {
            LOGE("onOffer: SDP missing audio info (pt=%d port=%d ip=%s) reinvite=%d",
                 audioPt, audioPort, audioIp.c_str(), (int)isReinvite);
            return;
        }
        LOGI("%s onOffer: callId=%s from=%s audio=%s:%d video=%s:%d",
             isReinvite ? "RE-INVITE" : "MT",
             callId.c_str(), fromUri.c_str(),
             audioIp.c_str(), audioPort,
             videoIp.empty() ? audioIp.c_str() : videoIp.c_str(), videoPort);

        /* Re-INVITE dispatch: notify the MO call-session listener via
         * a dedicated upcall; it'll build an SDP answer and call
         * provideReinviteAnswer back into us. We pass BOTH audio and
         * video params in one call so Java has the complete new
         * offer shape in one hop. */
        if (isReinvite) {
            if (!gCallListener || !gOnRemoteReinvite) {
                LOGW("re-INVITE: no listener wired — rejecting implicitly (ignored)");
                return;
            }
            bool reattached = false;
            JNIEnv* re = attachJni(&reattached);
            if (!re) return;
            jstring jAIp     = re->NewStringUTF(audioIp.c_str());
            jstring jACodec  = re->NewStringUTF(audioCodec.c_str());
            jstring jAFmtp   = re->NewStringUTF(audioFmtp.c_str());
            const std::string& vip = videoIp.empty() ? audioIp : videoIp;
            jstring jVIp     = re->NewStringUTF(vip.c_str());
            jstring jVCodec  = re->NewStringUTF(videoCodec.c_str());
            jstring jVFmtp   = re->NewStringUTF(videoFmtp.c_str());
            re->CallVoidMethod(gCallListener, gOnRemoteReinvite,
                               jAIp, (jint)audioPort, (jint)audioRtcpPort,
                               (jint)audioPt, (jint)audioRate, jACodec, jAFmtp,
                               jVIp, (jint)videoPort, (jint)videoRtcpPort,
                               (jint)videoPt, (jint)videoRate, jVCodec, jVFmtp);
            re->DeleteLocalRef(jAIp);
            re->DeleteLocalRef(jACodec);
            re->DeleteLocalRef(jAFmtp);
            re->DeleteLocalRef(jVIp);
            re->DeleteLocalRef(jVCodec);
            re->DeleteLocalRef(jVFmtp);
            if (re->ExceptionCheck()) { re->ExceptionDescribe(); re->ExceptionClear(); }
            if (reattached) gJvm->DetachCurrentThread();
            return;
        }

        if (!gIncomingCallListener || !gOnIncomingInvite) return;
        bool attached = false;
        JNIEnv* env = attachJni(&attached);
        if (!env) return;

        /* Audio upcall — unchanged signature. */
        {
            jstring jCallId = env->NewStringUTF(callId.c_str());
            jstring jFrom   = env->NewStringUTF(fromUri.c_str());
            jstring jIp     = env->NewStringUTF(audioIp.c_str());
            jstring jCodec  = env->NewStringUTF(audioCodec.c_str());
            jstring jFmtp   = env->NewStringUTF(audioFmtp.c_str());
            env->CallVoidMethod(gIncomingCallListener, gOnIncomingInvite,
                                jCallId, jFrom, jIp,
                                (jint)audioPort, (jint)audioRtcpPort,
                                (jint)audioPt, (jint)audioRate,
                                jCodec, jFmtp);
            env->DeleteLocalRef(jCallId);
            env->DeleteLocalRef(jFrom);
            env->DeleteLocalRef(jIp);
            env->DeleteLocalRef(jCodec);
            env->DeleteLocalRef(jFmtp);
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        }

        /* Video upcall — only fires when a video m-line with port>0
         * was offered. Sent *after* the audio upcall so Java has the
         * session already created via onIncomingInvite before
         * receiving the video annex. */
        if (videoPort > 0 && gOnIncomingInviteVideo) {
            const std::string& vip = videoIp.empty() ? audioIp : videoIp;
            jstring jCallId = env->NewStringUTF(callId.c_str());
            jstring jIp     = env->NewStringUTF(vip.c_str());
            jstring jCodec  = env->NewStringUTF(videoCodec.c_str());
            jstring jFmtp   = env->NewStringUTF(videoFmtp.c_str());
            env->CallVoidMethod(gIncomingCallListener, gOnIncomingInviteVideo,
                                jCallId, jIp,
                                (jint)videoPort, (jint)videoRtcpPort,
                                (jint)videoPt, (jint)videoRate,
                                jCodec, jFmtp);
            env->DeleteLocalRef(jCallId);
            env->DeleteLocalRef(jIp);
            env->DeleteLocalRef(jCodec);
            env->DeleteLocalRef(jFmtp);
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        }

        if (attached) gJvm->DetachCurrentThread();
    }
    void onOfferRequired(resip::InviteSessionHandle, const resip::SipMessage&) override {}
    void onOfferRejected(resip::InviteSessionHandle, const resip::SipMessage*) override {}
    void onInfo(resip::InviteSessionHandle, const resip::SipMessage&) override {}
    void onInfoSuccess(resip::InviteSessionHandle, const resip::SipMessage&) override {}
    void onInfoFailure(resip::InviteSessionHandle, const resip::SipMessage&) override {}
    void onMessage(resip::InviteSessionHandle, const resip::SipMessage&) override {}
    void onMessageSuccess(resip::InviteSessionHandle, const resip::SipMessage&) override {}
    void onMessageFailure(resip::InviteSessionHandle, const resip::SipMessage&) override {}
    void onRefer(resip::InviteSessionHandle, resip::ServerSubscriptionHandle,
                 const resip::SipMessage&) override {}
    void onReferNoSub(resip::InviteSessionHandle, const resip::SipMessage&) override {}
    void onReferRejected(resip::InviteSessionHandle, const resip::SipMessage&) override {}
    void onReferAccepted(resip::InviteSessionHandle, resip::ClientSubscriptionHandle,
                         const resip::SipMessage&) override {}

private:
    std::mutex                       mMu;
    resip::ClientInviteSessionHandle mActive;
    /* Active server-side sessions, keyed by Call-ID. Writen in
     * onNewSession(server), read by Java accept/reject/progress
     * via takeServer(). Erased in onTerminated when the session
     * ends. */
    std::unordered_map<std::string,
            resip::ServerInviteSessionHandle> mServerByCallId;
    /* Call-ID + From of the most-recent INVITE for which
     * onOffer hasn't fired yet — used to correlate the deferred
     * SDP parsing in onOffer with the session identity captured
     * at onNewSession. Only one MT can be pending-offer at a time
     * (single-call assumption). */
    std::string                      mPendingIncomingCallId;
    std::string                      mPendingIncomingFromUri;
};

/* --------------------------------------------------------------------- *
 * SMS-over-IMS handler. SIP MESSAGE both directions:
 *   MO: ClientPagerMessage::page() with OctetContents body, fires
 *       onSuccess on 202 Accepted (and our follow-on RP-ACK in the
 *       inbound MESSAGE) or onFailure.
 *   MT: ServerPagerMessage::onMessageArrived — body bytes get pushed
 *       up to Java via the SmsListener for RP-DATA unwrapping.
 * --------------------------------------------------------------------- */

/* --------------------------------------------------------------------- *
 * reg-event subscription handler. We don't consume the reginfo payload;
 * the point of the SUBSCRIBE is to mark the UE as fully "online" with
 * the S-CSCF so MO MESSAGE / INVITE stop returning 404 on Mavenir
 * networks. Minimal stubs; just log lifecycle transitions.
 * --------------------------------------------------------------------- */
class HamelinPortsSubHandler : public resip::ClientSubscriptionHandler {
public:
    void onNewSubscription(resip::ClientSubscriptionHandle,
                           const resip::SipMessage& notify) override {
        LOGI("SUBSCRIBE reg: first NOTIFY received");
    }
    /* reSIProcate contract (SubscriptionHandler.hxx:18): every
     * onUpdateFoo MUST be followed by acceptUpdate() or rejectUpdate()
     * on the handle — until that call, the NOTIFY 200 OK is NOT
     * emitted. Without the ack the S-CSCF considers the reg-event
     * subscription half-open and can reject subsequent service
     * requests (suspected cause of MO MESSAGE 404 on this network). */
    void onUpdatePending(resip::ClientSubscriptionHandle h,
                        const resip::SipMessage& notify, bool /*outOfOrder*/) override {
        LOGI("SUBSCRIBE reg: update pending -> acceptUpdate");
        h->acceptUpdate();
    }
    void onUpdateActive(resip::ClientSubscriptionHandle h,
                       const resip::SipMessage& notify, bool /*outOfOrder*/) override {
        LOGI("SUBSCRIBE reg: update active -> acceptUpdate");
        h->acceptUpdate();
    }
    void onUpdateExtension(resip::ClientSubscriptionHandle h,
                          const resip::SipMessage& notify, bool /*outOfOrder*/) override {
        LOGI("SUBSCRIBE reg: update extension -> acceptUpdate");
        h->acceptUpdate();
    }
    int onRequestRetry(resip::ClientSubscriptionHandle,
                      int retrySeconds, const resip::SipMessage& /*notify*/) override {
        LOGI("SUBSCRIBE reg: retry in %d s", retrySeconds);
        return -1;  /* don't retry */
    }
    void onTerminated(resip::ClientSubscriptionHandle,
                     const resip::SipMessage* msg) override {
        int code = 0;
        if (msg && msg->isResponse()) {
            code = msg->header(resip::h_StatusLine).statusCode();
        }
        LOGI("SUBSCRIBE reg: terminated (code=%d)", code);
    }
};

class HamelinPortsSmsHandler : public resip::ClientPagerMessageHandler,
                          public resip::ServerPagerMessageHandler {
public:
    void onSuccess(resip::ClientPagerMessageHandle, const resip::SipMessage& msg) override {
        const int code = msg.header(resip::h_StatusLine).statusCode();
        LOGI("SMS onSuccess: %d", code);
        upcall(gOnSmsSendSuccess, code, "");
    }
    void onFailure(resip::ClientPagerMessageHandle, const resip::SipMessage& msg,
                   std::unique_ptr<resip::Contents>) override {
        const int code = msg.header(resip::h_StatusLine).statusCode();
        const std::string r = msg.header(resip::h_StatusLine).reason().c_str();
        LOGE("SMS onFailure: %d %s", code, r.c_str());
        upcall(gOnSmsSendFailure, code, r);
    }
    void onMessageArrived(resip::ServerPagerMessageHandle h,
                          const resip::SipMessage& msg) override {
        LOGI("SMS onMessageArrived");

        /* Capture P-Asserted-Identity + Call-ID so Java's RP-ACK can
         * (a) target the exact SMSC instance and (b) carry an
         * In-Reply-To header referencing the inbound MT MESSAGE.
         * Mavenir's IP-SM-GW correlates UE-originated RP-ACKs to the
         * original MT transaction via In-Reply-To; without it the
         * IP-SM-GW returns 481 Call/Transaction Does Not Exist on
         * the second and subsequent RP-ACKs in the same registration
         * (the first one sometimes squeaks through against a cold
         * cache). With the correlator stamped, every RP-ACK is
         * unambiguous and the SMSC drains the queue cleanly. */
        {
            std::lock_guard<std::mutex> g(gLastMtPaiMu);
            gLastMtPai.clear();
            gLastMtCallId.clear();
            if (msg.exists(resip::h_PAssertedIdentities) &&
                !msg.header(resip::h_PAssertedIdentities).empty()) {
                std::ostringstream oss;
                oss << msg.header(resip::h_PAssertedIdentities).front().uri();
                gLastMtPai = oss.str();
            }
            if (msg.exists(resip::h_CallId)) {
                gLastMtCallId = msg.header(resip::h_CallId).value().c_str();
            }
            LOGI("MT PAI=%s Call-ID=%s",
                 gLastMtPai.c_str(), gLastMtCallId.c_str());
        }

        const resip::Contents* body = msg.getContents();
        if (!body) {
            LOGE("incoming MESSAGE without body");
            h->acceptCommand(200);
            return;
        }
        const auto* oc = dynamic_cast<const resip::OctetContents*>(body);
        const resip::Data& bytes = oc ? oc->octets() : body->getBodyData();

        if (gSmsListener && gOnSmsIncoming) {
            bool attached = false;
            JNIEnv* env = attachJni(&attached);
            if (env) {
                jbyteArray jArr = env->NewByteArray((jsize)bytes.size());
                env->SetByteArrayRegion(jArr, 0, (jsize)bytes.size(),
                                        (const jbyte*)bytes.data());
                env->CallVoidMethod(gSmsListener, gOnSmsIncoming, jArr);
                env->DeleteLocalRef(jArr);
                if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
                if (attached) gJvm->DetachCurrentThread();
            }
        }

        /* Acknowledge the inbound MT MESSAGE at the SIP transport
         * layer. The separate RP-ACK at the SMS-over-IMS mapping
         * layer is sent from Java (HamelinPortsSmsImpl.handleMtRpData)
         * as a new out-of-dialog MESSAGE to the PAI. */
        h->acceptCommand(200);
    }

private:
    void upcall(jmethodID m, int code, const std::string& reason) {
        if (!gSmsListener || !m) return;
        bool attached = false;
        JNIEnv* env = attachJni(&attached);
        if (!env) return;
        jstring jr = env->NewStringUTF(reason.c_str());
        env->CallVoidMethod(gSmsListener, m, (jint)code, jr);
        env->DeleteLocalRef(jr);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        if (attached) gJvm->DetachCurrentThread();
    }
};

/* --------------------------------------------------------------------- *
 * Bridge — the singleton tying everything together.
 * --------------------------------------------------------------------- */

class Bridge {
public:
    static Bridge& instance() {
        static Bridge inst;
        return inst;
    }

    bool start() {
        std::lock_guard<std::mutex> g(mMu);
        if (mStarted) return true;

        try {
            resip::Log::initialize(resip::Log::Cout,
                                   resip::Log::Info,
                                   "HamelinPortsIms:resip",
                                   *mAndroidLogger);

            mStack = std::make_unique<resip::SipStack>();
            mDum   = std::make_unique<resip::DialogUsageManager>(*mStack);
            mProf  = std::make_shared<resip::MasterProfile>();
            mDum->setMasterProfile(mProf);

            /* RFC 4028 Session Timer. Offer `Session-Expires: 1800`
             * and `Supported: timer` on INVITE — without this Mavenir
             * TAS BYEs the call at ~30 s once media is up. Verified
             * end-to-end on 2026-04-22: hand-roll AND imsmedia engines
             * both lose the call at exactly 32 s with `BYE / 2 from
             * (wire)`, audio clean both ways up to the BYE — proving
             * the network's no-refresh timer is the culprit, not
             * anything in the media plane. setDefaultSessionTime() is
             * gated on >=90 s in InviteSessionCreator so 1800
             * satisfies that. */
            mProf->setDefaultSessionTime(1800);
            mProf->addSupportedOptionTag(resip::Token("timer"));

            /* Always wire the invite + SMS handlers — DUM throws from
             * makeInviteSession / makePagerMessage otherwise. */
            mDum->setInviteSessionHandler(&mInviteHandler);
            mDum->setClientPagerMessageHandler(&mSmsHandler);
            mDum->setServerPagerMessageHandler(&mSmsHandler);
            mDum->addClientSubscriptionHandler(resip::Data("reg"), &mSubHandler);

            /* Capture the public identity from REGISTER 200 OK's
             * P-Associated-URI, swap the profile's default From so
             * subsequent MESSAGE / INVITE carry the public identity,
             * and fire a reg-event SUBSCRIBE. The S-CSCF gates MO
             * MESSAGE / INVITE on the UE being subscribed to the
             * reg-event package (RFC 3680 / TS 24.229); without this
             * the Mavenir P-CSCF returns 404 for MO MESSAGE. */
            mRegHandler.onHandle = [this](resip::ClientRegistrationHandle h) {
                std::lock_guard<std::mutex> g(mMu);
                mRegHandleValid = h.isValid();
                mRegHandle = h;
            };
            mRegHandler.onExpiresReported = [this](int expires) {
                upcallExpiresReported(expires);
            };
            mRegHandler.onPublicIdentity = [this](const std::string& uri) {
                if (uri.empty()) return;
                mPublicIdentity = uri;
                try {
                    std::lock_guard<std::mutex> g(mMu);
                    if (mProf) {
                        mProf->setDefaultFrom(resip::NameAddr(resip::Data(uri)));
                        LOGI("public identity set for outbound From: %s",
                             uri.c_str());
                    }
                    if (mDum && !mSubscribeFired) {
                        resip::NameAddr target{resip::Data(uri)};
                        auto sub = mDum->makeSubscription(
                                target, mProf, resip::Data("reg"));
                        if (sub) {
                            /* Large Expires so the reg-event
                             * subscription does not require frequent
                             * refreshes (RFC 3680 §3.3). */
                            sub->header(resip::h_Expires).value() = 600000;
                            /* Accept: application/reginfo+xml — per
                             * RFC 3680 what reg-event carries. */
                            resip::Mime mime("application", "reginfo+xml");
                            sub->header(resip::h_Accepts).clear();
                            sub->header(resip::h_Accepts).push_back(mime);

                            /* Pin to the portC transport so the TCP
                             * source port is UE portC and the xfrm
                             * out-policy ESP-wraps the SUBSCRIBE. Same
                             * pattern as sendSms. */
                            if (mPortCTransportKey != 0 && mAkaCache.pcscfPortS != 0) {
                                resip::Tuple dst(
                                        resip::Data(mPcscfHostForSends),
                                        mAkaCache.pcscfPortS, resip::TCP);
                                dst.mTransportKey = mPortCTransportKey;
                                sub->setDestination(dst);
                            }

                            mDum->send(sub);
                            mSubscribeFired = true;
                            LOGI("SUBSCRIBE reg fired for %s (portC-pinned)",
                                 uri.c_str());
                        }
                    }
                } catch (const std::exception& e) {
                    LOGE("onPublicIdentity exception: %s", e.what());
                }
            };

            mStack->run();

            /* DUM needs its own processing loop. SipStack::run() only
             * drives the transport/transaction layers; the TU queue
             * (where responses land before being dispatched to
             * ClientRegistration/AuthManager/Dialog handlers) is drained
             * by DialogUsageManager::process(). Without this the 401
             * sits in the TU queue and our auth manager never runs. */
            mDumRun = true;
            mDumThread = std::thread([this]() {
                while (mDumRun.load()) {
                    try { mDum->process(100); }
                    catch (const std::exception& e) {
                        LOGE("DUM process threw: %s", e.what());
                    } catch (...) {
                        LOGE("DUM process threw unknown");
                    }
                }
            });

            mStarted = true;
            LOGI("Bridge started (no transport)");
            return true;
        } catch (const std::exception& e) {
            LOGE("Bridge::start threw: %s", e.what());
            tearDown();
            return false;
        } catch (...) {
            LOGE("Bridge::start threw unknown");
            tearDown();
            return false;
        }
    }

    void stop() {
        {
            std::lock_guard<std::mutex> g(mMu);
            if (!mStarted) return;
            mDumRun = false;
        }
        /* Join the DUM thread OUTSIDE the lock — process() may be
         * holding our mutex indirectly via decorator callbacks. */
        if (mDumThread.joinable()) {
            try { mDumThread.join(); } catch (...) {}
        }
        std::lock_guard<std::mutex> g(mMu);
        try {
            if (mStack) mStack->shutdownAndJoinThreads();
        } catch (...) {}
        tearDown();
        LOGI("Bridge stopped");
    }

    std::string status() const {
        std::lock_guard<std::mutex> g(mMu);
        if (!mStarted) return "stopped";
        std::string s = "started; transports=";
        s += std::to_string(mTransports.size());
        return s;
    }

    bool addSipTransports(const std::string& localIp, int portC, int portS) {
        std::lock_guard<std::mutex> g(mMu);
        if (!mStarted) {
            LOGE("addSipTransports: bridge not started");
            return false;
        }
        try {
            const resip::Data ipIface(localIp);
            const resip::IpVersion ipv =
                    (localIp.find(':') != std::string::npos) ? resip::V6 : resip::V4;

            resip::Transport* tC = mStack->addTransport(
                    resip::TCP, portC, ipv, resip::StunDisabled, ipIface);
            if (!tC) { LOGE("portC bind failed"); return false; }
            /* portC is our UE client port: every outbound TCP (REGISTER 2,
             * INVITE, MESSAGE over IPsec) must leave with source port =
             * portC so the kernel xfrm out-policy on (portC→port-s)
             * matches and ESP-wraps the packet. Without this, outbound
             * TCPs use ephemeral source ports and miss the xfrm policy. */
            if (auto* tcpC = dynamic_cast<resip::TcpBaseTransport*>(tC)) {
                tcpC->setOutgoingBindPort(true);
            }
            mTransports.push_back(tC->getKey());
            mPortCTransportKey = tC->getKey();
            LOGI("transport portC %s:%d key=%u (outgoing-bind)",
                 localIp.c_str(), portC, tC->getKey());

            resip::Transport* tS = mStack->addTransport(
                    resip::TCP, portS, ipv, resip::StunDisabled, ipIface);
            if (!tS) { LOGE("portS bind failed"); return false; }
            mTransports.push_back(tS->getKey());
            mUePortS = portS;
            mLocalIpForCallId = localIp;
            LOGI("transport portS %s:%d key=%u", localIp.c_str(), portS, tS->getKey());

            /* IMS Via convention: sent-by host:port in Via and Contact
             * must be the UE's PortS (listen port), regardless of which
             * port the outbound TCP came from. Otherwise the P-CSCF
             * can't correlate MT traffic back to us (it's also a
             * fingerprint some P-CSCFs reject on). Set via the profile's
             * OverrideHostAndPort so reSIProcate composes Via/Contact
             * from it instead of the outbound transport's bound port. */
            if (mProf) {
                std::string hp = std::string("sip:") + formatHostForUri(localIp) +
                                 ":" + std::to_string(portS);
                mProf->setOverrideHostAndPort(resip::Uri(resip::Data(hp)));
                LOGI("Via/Contact host:port overridden to %s", hp.c_str());
            }
            return true;
        } catch (const std::exception& e) {
            LOGE("addSipTransports threw: %s", e.what());
            return false;
        } catch (...) {
            LOGE("addSipTransports threw unknown");
            return false;
        }
    }

    bool startRegister(const std::string& impi,
                       const std::string& impuUri,
                       const std::string& domain,
                       int    expirySec,
                       const std::string& instanceId,
                       const std::string& pcscfHost,
                       int    pcscfCleartextPort,   /* 5060 for round 1 */
                       const std::string& securityClient) {
        std::lock_guard<std::mutex> g(mMu);
        if (!mStarted || !mDum) {
            LOGE("startRegister: bridge not started");
            return false;
        }
        try {
            mProf->setDefaultFrom(resip::NameAddr(resip::Data(impuUri)));
            mProf->setDigestCredential(resip::Data(domain),
                                       resip::Data(impi),
                                       resip::Data("AKA")); /* placeholder */
            mProf->setDefaultRegistrationTime(expirySec);
            mProf->setInstanceId(resip::Data(instanceId));
            /* Set a User-Agent the Mavenir P-CSCF accepts. Some
             * P-CSCFs gatekeep on the User-Agent string and reject
             * unknown ones with 404 on MESSAGE / INVITE. Until we
             * know exactly which substring the policy matches, use
             * a known-accepted value. */
            mProf->setUserAgent(resip::Data("SM-A515F-WI1 Samsung IMS 6.0"));

            /* TCP CRLF keepalive (TS 24.229 §5.1.1.4 / RFC 6223):
             * a 2-byte "\r\n\r\n" ping every 30 s to keep the
             * IPsec-protected P-CSCF TCP flow alive. Without it the
             * P-CSCF drops the idle connection after ~5 min, we lose
             * the ability to receive MT traffic or send subsequent
             * MO requests, and the IMS PDN eventually tears down.
             * 30 s is the 3GPP-recommended interval. */
            mProf->setKeepAliveTimeForStream(30);

            /* Advertise the SIP methods we actually handle. Default is
             * "all methods" which emits an Allow list that omits MESSAGE
             * entirely (reSIProcate's default set: INVITE/ACK/CANCEL/
             * OPTIONS/BYE/UPDATE). The Mavenir P-CSCF returns 404 for
             * MESSAGE requests from a UA whose Allow doesn't advertise
             * MESSAGE. Clear + add only what we support. */
            mProf->clearSupportedMethods();
            mProf->addSupportedMethod(resip::REGISTER);
            mProf->addSupportedMethod(resip::INVITE);
            mProf->addSupportedMethod(resip::ACK);
            mProf->addSupportedMethod(resip::CANCEL);
            mProf->addSupportedMethod(resip::BYE);
            mProf->addSupportedMethod(resip::UPDATE);
            mProf->addSupportedMethod(resip::OPTIONS);
            mProf->addSupportedMethod(resip::MESSAGE);
            mProf->addSupportedMethod(resip::NOTIFY);
            mProf->addSupportedMethod(resip::SUBSCRIBE);
            mProf->addSupportedMethod(resip::PRACK);
            mProf->addSupportedMethod(resip::REFER);
            mProf->addSupportedMethod(resip::INFO);

            /* reg-event NOTIFY carries application/reginfo+xml. Without
             * this MIME in the profile's supported list, DUM rejects
             * the inbound NOTIFY with "Received an unsupported mime
             * type" and never dispatches onUpdateActive — which leaves
             * the reg-event subscription un-accepted and the S-CSCF
             * treats the UE as not-fully-subscribed (blocks MO SMS). */
            mProf->addSupportedMimeType(resip::NOTIFY,
                    resip::Mime("application", "reginfo+xml"));

            /* Incoming MESSAGE from the SMSC carries RP-DATA (MT SMS)
             * or the sm-submit-report that acknowledges our MO. Both
             * use Content-Type application/vnd.3gpp.sms. Without this
             * entry DUM returns 415 Unsupported Media Type and the
             * SMSC retries until it gives up. */
            mProf->addSupportedMimeType(resip::MESSAGE,
                    resip::Mime("application", "vnd.3gpp.sms"));

            /* REGISTER 1 cleartext target. transport=tcp is critical:
             * without it the URI defaults to UDP per RFC 3261 §19.1.1,
             * and reSIProcate's TransportSelector then can't find a
             * UDP transport (we only add TCP). It falls back to an
             * auto-created `port=0 TCP` transport with mOutgoing-
             * BindPort=0, opens a fresh ephemeral-source-port TCP
             * connection that misses the IPsec out-policy (keyed on
             * sport=portC), so ACK/UPDATE go cleartext. P-CSCF expects
             * ESP-wrapped on portC↔portS, drops them silently, and
             * BYEs the call at ~32 s with
             * `Reason: SIP;cause=504;text="Ctx Timeout(o)"`.
             * Verified end-to-end on 2026-04-22 across hand-roll AND
             * imsmedia engines — same 32 s BYE on both, exact same
             * SIP retransmit pattern in tcpdump. */
            std::string pcscfUri1 = std::string("sip:") + formatHostForUri(pcscfHost) + ":" +
                                    std::to_string(pcscfCleartextPort) + ";transport=tcp;lr";
            mProf->setOutboundProxy(resip::Uri(resip::Data(pcscfUri1)));
            /* DO NOT setForceOutboundProxyOnAllRequestsEnabled here:
             * with that on, EVERY in-dialog request (ACK/UPDATE/BYE)
             * gets our outbound-proxy URI prepended to its Route set
             * (via setExpressOutboundAsRouteSetEnabled). Mavenir
             * rejects the ACK silently — its dialog Route Set has
             * exactly one entry (the mavodi-route from Record-Route in
             * 200 OK) and an extra leading Route is treated as a
             * malformed handshake → 200 OK keeps retransmitting → 504
             * Ctx Timeout BYE at ~32 s.
             *
             * The Dialog::send patch (mEstablishedTarget in
             * external/resiprocate Dialog.cxx) already pins all in-
             * dialog requests to the IPsec-protected portC connection
             * the INVITE was sent on, so we no longer need the
             * outbound-proxy detour for routing. */
            mPcscfHostForSends = pcscfHost;

            /* Outbound proxy goes FIRST in the Route set (before the
             * Service-Route from REGISTER 200 OK). Mavenir P-CSCFs
             * reject MESSAGE with 404 when the first Route isn't the
             * P-CSCF itself (loose-routing check, RFC 3261 §16.4).
             * Final Route set: [<pcscf:6060;lr>, <service-route>]. */
            mProf->setExpressOutboundAsRouteSetEnabled(true);

            /* Decorator + auth manager — both share the same AkaCache so
             * the auth manager's stored Security-Verify is visible to the
             * decorator on later non-REGISTER requests. */
            mImsDecorator = std::make_shared<ImsDecorator>(
                    resip::Data(securityClient), mAkaCache, mPaniCache,
                    mPortCTransportKey, mUePortS);
            mProf->setOutboundDecorator(mImsDecorator);

            mDum->setClientAuthManager(
                    std::make_unique<HamelinPortsImsAuthManager>(
                            mAkaCache, pcscfHost, mPortCTransportKey));
            mDum->setClientRegistrationHandler(&mRegHandler);

            /* The AOR drives From and To in the REGISTER. Per 3GPP
             * TS 24.229 §5.1.1.2 these must carry the IMPU (sip:user@home),
             * not the bare home domain — DT's P-CSCF rejects the bare
             * domain with 400 Bad Request before any AKA challenge. The
             * Request-URI is auto-derived from the AOR's host part (which
             * is the home domain). */
            resip::NameAddr aor{resip::Data(impuUri)};
            auto reg = mDum->makeRegistration(aor, mProf);

            /* 3GPP TS 24.229 §5.1.1.2.1: REGISTER 1 carries an empty
             * digest challenge-response so the P-CSCF/S-CSCF know which
             * IMPI to challenge. reSIProcate's ClientRegistration normally
             * leaves Authorization off the initial REGISTER (standard
             * SIP), so we add it manually. The auth manager replaces it
             * with the real digest after the 401. */
            {
                resip::Auth empty;
                empty.scheme() = "Digest";
                empty.param(resip::p_username) = resip::Data(impi);
                empty.param(resip::p_realm) = resip::Data(domain);
                empty.param(resip::p_nonce) = resip::Data::Empty;
                empty.param(resip::p_uri) = resip::Data("sip:") + resip::Data(domain);
                empty.param(resip::p_response) = resip::Data::Empty;
                empty.param(resip::p_algorithm) = resip::Data("AKAv1-MD5");
                reg->header(resip::h_Authorizations).push_back(empty);
            }

            mDum->send(reg);

            LOGI("startRegister submitted: impu=%s domain=%s pcscf=%s:%d",
                 impuUri.c_str(), domain.c_str(),
                 pcscfHost.c_str(), pcscfCleartextPort);
            return true;
        } catch (const std::exception& e) {
            LOGE("startRegister threw: %s", e.what());
            return false;
        } catch (...) {
            LOGE("startRegister threw unknown");
            return false;
        }
    }

    /** Refresh the PANI utran-cell-id-3gpp value (cellular IMS path).
     *  Called from Java before each MO call so the decorator stamps
     *  the freshest cell. Also clears any cached IEEE-802.11 PANI so
     *  a stale Wi-Fi-Calling value can't leak onto a now-cellular
     *  request after a tunnel teardown. */
    void setCellIdForPani(const std::string& v) {
        std::lock_guard<std::mutex> g(mMu);
        mPaniCache.utranCellId3gpp = resip::Data(v);
        mPaniCache.iWlanNodeId    = resip::Data::Empty;
    }

    /** Refresh the PANI i-wlan-node-id value (Wi-Fi Calling path).
     *  Mirror of setCellIdForPani for the IEEE-802.11 access token
     *  per TS 24.229 §7.2A.4. Caller passes the AP BSSID with colons
     *  stripped and uppercase (e.g. "B6FC7D11A6B0"). Clears the
     *  E-UTRAN cache so the decorator never emits both. */
    void setIwlanNodeIdForPani(const std::string& v) {
        std::lock_guard<std::mutex> g(mMu);
        mPaniCache.iWlanNodeId    = resip::Data(v);
        mPaniCache.utranCellId3gpp = resip::Data::Empty;
    }

    /** Start an outbound call.
     *  @param targetUri full Request-URI as Java has formatted it
     *                   (e.g. "sip:015732220078;phone-context=telefonica.de@telefonica.de;user=phone").
     *                   This is the magic that makes Telefonica's TAS route MO calls
     *                   correctly — see project_a51_volte_root_cause memory.
     *  @param sdpOffer  SDP body (as a string) Java already built. */
    bool startCall(const std::string& targetUri, const std::string& sdpOffer) {
        std::lock_guard<std::mutex> g(mMu);
        if (!mStarted || !mDum) {
            LOGE("startCall: bridge not started");
            return false;
        }
        try {
            resip::NameAddr target{resip::Data(targetUri)};
            resip::HeaderFieldValue hfv(sdpOffer.data(), sdpOffer.size());
            resip::Mime sdpMime("application", "sdp");
            resip::SdpContents sdp(hfv, sdpMime);
            auto inv = mDum->makeInviteSession(target, mProf, &sdp);

            /* Pin the outbound INVITE to the portC transport, same as
             * sendSms() does for MESSAGE. Without this, reSIProcate
             * opens a fresh ephemeral-source-port TCP connection (e.g.
             * src=44267) that misses the xfrm out-policy (which keys
             * on sport=portC), so the SYN goes cleartext, P-CSCF
             * ignores it, and the INVITE times out 408. */
            if (mPortCTransportKey != 0 && mAkaCache.pcscfPortS != 0) {
                resip::Tuple dst(resip::Data(mPcscfHostForSends),
                                 mAkaCache.pcscfPortS,
                                 resip::TCP);
                dst.mTransportKey = mPortCTransportKey;
                inv->setDestination(dst);
            }

            mDum->send(inv);
            LOGI("INVITE submitted: target=%s sdp=%u bytes",
                 targetUri.c_str(), (unsigned)sdpOffer.size());
            return true;
        } catch (const std::exception& e) {
            LOGE("startCall threw: %s", e.what());
            return false;
        } catch (...) {
            LOGE("startCall threw unknown");
            return false;
        }
    }

    /** Send a SIP MESSAGE (MO SMS-over-IMS). The body is opaque
     *  octets — for 3GPP SMS the caller passes RP-DATA wrapping
     *  the TPDU; the content-type is typically
     *  application/vnd.3gpp.sms. */
    bool sendSms(const std::string& targetUri,
                 const std::string& contentType,
                 const std::vector<uint8_t>& body) {
        std::lock_guard<std::mutex> g(mMu);
        if (!mStarted || !mDum) {
            LOGE("sendSms: bridge not started");
            return false;
        }
        try {
            resip::NameAddr target{resip::Data(targetUri)};
            auto msg = mDum->makePagerMessage(target, mProf);

            /* Pin the outbound SipMessage to the portC transport so the
             * TCP source port is UE portC, matching the xfrm out-policy
             * (portC↔port-s). Set before page() — ClientPagerMessage
             * dispatches via a DumCommand which reads the already-
             * initialised SipMessage mDestination. */
            if (mPortCTransportKey != 0 && mAkaCache.pcscfPortS != 0) {
                resip::SipMessage& req = msg->getMessageRequest();
                resip::Tuple dst(resip::Data(mPcscfHostForSends),
                                 mAkaCache.pcscfPortS,
                                 resip::TCP);
                dst.mTransportKey = mPortCTransportKey;
                req.setDestination(dst);
            }

            /* Add IMS MESSAGE headers per TS 24.341 / RFC 3428:
             *  P-Preferred-Identity: <public-identity>   (req'd by P-CSCF)
             *  Accept-Contact: *;+g.3gpp.smsip           (feature tag)
             *  Request-Disposition: no-fork
             *  Allow: MESSAGE                            (MESSAGE-only UA)
             * From is driven by profile's DefaultFrom (now the public
             * identity, see onPublicIdentity callback). */
            if (!mPublicIdentity.empty()) {
                resip::SipMessage& req = msg->getMessageRequest();
                resip::NameAddr pid{resip::Data(mPublicIdentity)};
                req.header(resip::h_PPreferredIdentities).clear();
                req.header(resip::h_PPreferredIdentities).push_back(pid);

                /* NOTE: do NOT mutate Call-ID here. reSIProcate's DUM
                 * indexes DialogSets by Call-ID; if we append @<host>
                 * in the outbound decorator after DUM has registered
                 * the DialogSet with the bare Call-ID, the response
                 * echoed with the mutated Call-ID won't match and DUM
                 * logs "Throwing away stray response". Per RFC 3261
                 * §8.1.1.4 a bare Call-ID token is sufficient and the
                 * P-CSCF accepts it. */

                resip::NameAddr accept;
                resip::Data acceptStr("*;+g.3gpp.smsip");
                resip::ParseBuffer pb(acceptStr.data(), acceptStr.size());
                accept.parse(pb);
                req.header(resip::h_AcceptContacts).clear();
                req.header(resip::h_AcceptContacts).push_back(accept);

                /* Request-Disposition: no-fork (RFC 3841) — tells the
                 * P-CSCF not to fanout to multiple SMSCs. */
                resip::Token reqDisp("no-fork");
                req.header(resip::h_RequestDispositions).clear();
                req.header(resip::h_RequestDispositions).push_back(reqDisp);
            }

            const auto slash = contentType.find('/');
            const std::string typeStr = (slash != std::string::npos) ?
                    contentType.substr(0, slash) : "application";
            const std::string subStr  = (slash != std::string::npos) ?
                    contentType.substr(slash + 1) : "octet-stream";
            resip::Mime mime(typeStr.c_str(), subStr.c_str());

            resip::Data bodyData(reinterpret_cast<const char*>(body.data()),
                                 body.size());
            auto contents = std::make_unique<resip::OctetContents>(bodyData, mime);
            msg->page(std::move(contents));
            LOGI("MESSAGE submitted: target=%s body=%u bytes "
                 "(portC-pinned=%s)",
                 targetUri.c_str(), (unsigned)body.size(),
                 (mPortCTransportKey != 0) ? "yes" : "no");
            return true;
        } catch (const std::exception& e) {
            LOGE("sendSms threw: %s", e.what());
            return false;
        } catch (...) {
            LOGE("sendSms threw unknown");
            return false;
        }
    }

    /** Tear down the active outbound call (BYE if connected, CANCEL
     *  if early, otherwise no-op). */
    void endCall() {
        std::lock_guard<std::mutex> g(mMu);
        /* Safety: if the whole Bridge was torn down (e.g. IMS PDN
         * lost mid-call), mActive still references a handle whose
         * HandleManager is gone — isValid() would segfault. Skip
         * everything when mStarted is false. */
        if (!mStarted || !mDum) {
            LOGI("endCall: bridge not started, no-op");
            return;
        }
        try {
            auto h = mInviteHandler.takeActive();
            if (h.isValid()) {
                h->end();
                LOGI("endCall: end() invoked");
            }
        } catch (...) {}
    }

    /** Respond 200 OK with {@code sdpAnswer} to an incoming re-INVITE
     *  that arrived via {@link #onOffer}. The session must be in
     *  {@code ReceivedReinvite} state — reSIProcate transitions back
     *  to Connected once we call provideAnswer. No acceptOffer needed
     *  for InviteSession; provideAnswer sends the 200 OK directly. */
    bool provideReinviteAnswer(const std::string& sdpAnswer) {
        std::lock_guard<std::mutex> g(mMu);
        if (!mStarted || !mDum) return false;
        try {
            auto h = mInviteHandler.takeActive();
            if (!h.isValid()) {
                LOGW("provideReinviteAnswer: no active session");
                return false;
            }
            resip::HeaderFieldValue hfv(sdpAnswer.data(), sdpAnswer.size());
            resip::Mime sdpMime("application", "sdp");
            resip::SdpContents sdp(hfv, sdpMime);
            LOGI("provideReinviteAnswer: answering re-INVITE (sdp=%u bytes)",
                 (unsigned)sdpAnswer.size());
            h->provideAnswer(sdp);
            return true;
        } catch (const std::exception& e) {
            LOGE("provideReinviteAnswer threw: %s", e.what());
            return false;
        } catch (...) {
            LOGE("provideReinviteAnswer threw unknown");
            return false;
        }
    }

    /** Mid-call re-INVITE: send a new SDP offer on the established
     *  InviteSession to negotiate a stream change (add/remove/direction
     *  of video). Must be in Connected state — reSIProcate throws
     *  {@code DialogUsage::Exception} otherwise. The response arrives
     *  via the usual {@code onAnswer(InviteSessionHandle, SdpContents)}
     *  path; {@link HamelinPortsCallSession#onAnswer} already handles both
     *  audio and video m-lines (C.2 work), so all we need here is
     *  the trigger. */
    bool reinvite(const std::string& sdpOffer) {
        std::lock_guard<std::mutex> g(mMu);
        if (!mStarted || !mDum) return false;
        try {
            auto h = mInviteHandler.takeActive();
            if (!h.isValid()) {
                LOGW("reinvite: no active ClientInviteSession");
                return false;
            }
            resip::HeaderFieldValue hfv(sdpOffer.data(), sdpOffer.size());
            resip::Mime sdpMime("application", "sdp");
            resip::SdpContents sdp(hfv, sdpMime);
            LOGI("reinvite: provideOffer (sdp=%u bytes)",
                 (unsigned)sdpOffer.size());
            h->provideOffer(sdp);
            return true;
        } catch (const std::exception& e) {
            LOGE("reinvite threw: %s", e.what());
            return false;
        } catch (...) {
            LOGE("reinvite threw unknown");
            return false;
        }
    }

    /** Tear down the MT server-side call keyed on Call-ID. Needed
     *  because {@link #endCall} only covers the MO path
     *  ({@code mActive} is a {@code ClientInviteSessionHandle}) — on a
     *  connected MT dialog {@code mActive} is NotValid and the user's
     *  hang-up never reaches the wire, leaving the remote to time us
     *  out on RTCP silence (observed ~8 s). */
    bool endCallByCallId(const std::string& callId) {
        std::lock_guard<std::mutex> g(mMu);
        if (!mStarted || !mDum) return false;
        try {
            auto h = mInviteHandler.takeServer(callId);
            if (h.isValid()) {
                h->end();
                LOGI("endCallByCallId: end() invoked for Call-ID=%s",
                     callId.c_str());
                return true;
            }
            LOGW("endCallByCallId: no server session for Call-ID=%s",
                 callId.c_str());
            return false;
        } catch (const std::exception& e) {
            LOGE("endCallByCallId threw: %s", e.what());
            return false;
        } catch (...) { return false; }
    }

    /** Send 180 Ringing (reliable if remote required 100rel) for the
     *  pending MT INVITE keyed on {@code callId}. Called from Java as
     *  soon as the framework presents the incoming-call UI to the
     *  user so the far end hears ring-back. ReSIProcate's
     *  ServerInviteSession::provisional(180, earlyFlag=false) handles
     *  the PRACK handshake automatically if the INVITE required
     *  100rel (Mavenir on o2-de does). */
    bool progressRinging(const std::string& callId) {
        std::lock_guard<std::mutex> g(mMu);
        if (!mStarted || !mDum) return false;
        try {
            auto h = mInviteHandler.takeServer(callId);
            if (!h.isValid()) {
                LOGE("progressRinging: no server session for Call-ID=%s",
                     callId.c_str());
                return false;
            }
            /* provisional(180, false) = 180 Ringing with no SDP body.
             * reSIProcate emits Require:100rel + RSeq automatically
             * when the incoming INVITE required 100rel, and waits
             * for PRACK before sending 200. */
            h->provisional(180, /*earlyFlag=*/false);
            LOGI("progressRinging: 180 Ringing queued for Call-ID=%s",
                 callId.c_str());
            return true;
        } catch (const std::exception& e) {
            LOGE("progressRinging threw: %s", e.what());
            return false;
        } catch (...) {
            return false;
        }
    }

    /** Accept the pending MT INVITE with the supplied SDP answer
     *  and 200 OK. Caller (Java HamelinPortsIncomingCallSession.accept)
     *  must have allocated RTP/RTCP sockets + assembled the SDP
     *  answer body (m-line, local IP, ports, codec matching the
     *  offer). reSIProcate runs provideAnswer + accept(200) and then
     *  transitions to established on remote ACK (onConnected fires
     *  back on the handler). */
    bool acceptIncomingCall(const std::string& callId,
                            const std::string& sdpAnswer) {
        std::lock_guard<std::mutex> g(mMu);
        if (!mStarted || !mDum) return false;
        try {
            auto h = mInviteHandler.takeServer(callId);
            if (!h.isValid()) {
                LOGE("acceptIncomingCall: no server session for Call-ID=%s",
                     callId.c_str());
                return false;
            }
            resip::HeaderFieldValue hfv(sdpAnswer.data(), sdpAnswer.size());
            resip::Mime sdpMime("application", "sdp");
            resip::SdpContents ans(hfv, sdpMime);
            h->provideAnswer(ans);
            h->accept(200);
            LOGI("acceptIncomingCall: 200 OK queued for Call-ID=%s sdp=%u bytes",
                 callId.c_str(), (unsigned)sdpAnswer.size());
            return true;
        } catch (const std::exception& e) {
            LOGE("acceptIncomingCall threw: %s", e.what());
            return false;
        } catch (...) {
            return false;
        }
    }

    /** Reject the pending MT INVITE with a SIP error code (typical
     *  values: 486 Busy Here, 480 Temporarily Unavailable, 603
     *  Decline — per 3GPP TS 24.229 §5.1.3). Called from Java when
     *  the user taps reject in the incoming-call UI or the framework
     *  times out. */
    bool rejectIncomingCall(const std::string& callId, int sipCode) {
        std::lock_guard<std::mutex> g(mMu);
        if (!mStarted || !mDum) return false;
        try {
            auto h = mInviteHandler.takeServer(callId);
            if (!h.isValid()) {
                LOGE("rejectIncomingCall: no server session for Call-ID=%s",
                     callId.c_str());
                return false;
            }
            h->reject(sipCode);
            LOGI("rejectIncomingCall: %d queued for Call-ID=%s",
                 sipCode, callId.c_str());
            return true;
        } catch (const std::exception& e) {
            LOGE("rejectIncomingCall threw: %s", e.what());
            return false;
        } catch (...) {
            return false;
        }
    }

    std::string banner() {
        resip::SipStack tmp;
        (void)tmp;
        return "reSIProcate JNI bridge alive";
    }

private:
    Bridge() = default;
    ~Bridge() = default;
    Bridge(const Bridge&) = delete;
    Bridge& operator=(const Bridge&) = delete;

    void tearDown() {
        /* Invalidate any stored handles before the DialogUsageManager
         * is destroyed — otherwise a late endCall()/refreshRegister()
         * after IMS PDN loss dereferences a dead HandleManager and
         * crashes (SIGSEGV observed 2026-04-24 during SRVCC testing
         * when LTE dropped and the user hit hangup ~100 s later). */
        mInviteHandler.clearActive();
        mRegHandle = resip::ClientRegistrationHandle::NotValid();
        mRegHandleValid = false;
        mTransports.clear();
        mImsDecorator.reset();
        mDum.reset();
        mStack.reset();
        mProf.reset();
        mAkaCache = AkaCache{};
        mStarted = false;
    }

    mutable std::mutex mMu;
    std::atomic<bool>  mStarted{false};
    std::atomic<bool>  mDumRun{false};
    std::thread        mDumThread;

    std::unique_ptr<resip::AndroidLogger>      mAndroidLogger{std::make_unique<resip::AndroidLogger>()};
    std::unique_ptr<resip::SipStack>           mStack;
    std::unique_ptr<resip::DialogUsageManager> mDum;
    std::shared_ptr<resip::MasterProfile>      mProf;
    std::vector<unsigned int>                  mTransports;
    unsigned int                               mPortCTransportKey = 0;
    int                                        mUePortS = 0;
    std::string                                mPcscfHostForSends;
    std::string                                mLocalIpForCallId;
    /** Public identity from REGISTER 200 OK's P-Associated-URI. Used
     *  as From / P-Preferred-Identity on outbound non-REGISTER
     *  requests (MESSAGE, INVITE) per TS 24.229 §5.1.2A.1. A P-CSCF
     *  that receives the IMPU form returns 404 because it doesn't
     *  know that identity publicly. */
    std::string                                mPublicIdentity;
    bool                                       mSubscribeFired = false;
    std::shared_ptr<ImsDecorator>              mImsDecorator;
    HamelinPortsRegHandler                          mRegHandler;
    HamelinPortsInviteHandler                       mInviteHandler;
    HamelinPortsSmsHandler                          mSmsHandler;
    HamelinPortsSubHandler                          mSubHandler;
    AkaCache                                   mAkaCache;
    PaniCache                                  mPaniCache;

    /* Refresh state: reSIProcate owns the ClientRegistration but we
     * keep a handle so Java can explicitly call requestRefresh on a
     * timer, working around observed cases where DUM's internal
     * auto-refresh didn't fire (5 h gap with zero REGo beyond the
     * initial AKA pair). Access under mMu. */
    resip::ClientRegistrationHandle            mRegHandle;
    bool                                       mRegHandleValid = false;

public:
    bool refreshRegister() {
        std::lock_guard<std::mutex> g(mMu);
        if (!mStarted || !mDum) return false;
        if (!mRegHandleValid || !mRegHandle.isValid()) {
            LOGW("refreshRegister: no valid registration handle");
            return false;
        }
        try {
            LOGI("refreshRegister: requesting refresh via ClientRegistration");
            mRegHandle->requestRefresh();
            return true;
        } catch (const std::exception& e) {
            LOGE("refreshRegister threw: %s", e.what());
            return false;
        } catch (...) {
            LOGE("refreshRegister threw unknown");
            return false;
        }
    }

    void upcallExpiresReported(int expires) {
        if (!gRegListener || !gOnRegisterExpiresReported) return;
        bool attached = false;
        JNIEnv* env = attachJni(&attached);
        if (!env) return;
        env->CallVoidMethod(gRegListener, gOnRegisterExpiresReported,
                            (jint)expires);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (attached) gJvm->DetachCurrentThread();
    }
};

}  // namespace

/* --------------------------------------------------------------------- *
 * Java ↔ C++ entry points
 * --------------------------------------------------------------------- */

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/)
{
    gJvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeSetAkaProvider(
        JNIEnv* env, jclass /*clazz*/, jobject provider)
{
    if (gAkaProvider) {
        env->DeleteGlobalRef(gAkaProvider);
        gAkaProvider = nullptr;
        gOnAuthChallengeMethod = nullptr;
    }
    if (provider == nullptr) {
        LOGI("AKA provider cleared");
        return;
    }

    gAkaProvider = env->NewGlobalRef(provider);

    jclass providerCls = env->GetObjectClass(provider);
    gOnAuthChallengeMethod = env->GetMethodID(
            providerCls, "onAuthChallenge",
            "([B[BJJIILjava/lang/String;)Lorg/hamelinports/ims/sip/AkaResult;");
    env->DeleteLocalRef(providerCls);
    if (!gOnAuthChallengeMethod) {
        LOGE("AKA provider missing onAuthChallenge method");
        return;
    }

    if (!gAkaResultClass) {
        jclass cls = env->FindClass("org/hamelinports/ims/sip/AkaResult");
        if (!cls) {
            LOGE("AkaResult class not found");
            return;
        }
        gAkaResultClass = (jclass)env->NewGlobalRef(cls);
        gFieldRes  = env->GetFieldID(cls, "res",  "[B");
        gFieldCk   = env->GetFieldID(cls, "ck",   "[B");
        gFieldIk   = env->GetFieldID(cls, "ik",   "[B");
        gFieldAuts = env->GetFieldID(cls, "auts", "[B");
        env->DeleteLocalRef(cls);
    }
    LOGI("AKA provider registered");
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeGetStackVersion(
        JNIEnv* env, jclass /*clazz*/)
{
    try { return env->NewStringUTF(Bridge::instance().banner().c_str()); }
    catch (...) { return env->NewStringUTF("exception"); }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeStart(
        JNIEnv* /*env*/, jclass /*clazz*/)
{
    return Bridge::instance().start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeStop(
        JNIEnv* /*env*/, jclass /*clazz*/)
{
    Bridge::instance().stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeGetStatus(
        JNIEnv* env, jclass /*clazz*/)
{
    try { return env->NewStringUTF(Bridge::instance().status().c_str()); }
    catch (...) { return env->NewStringUTF("exception"); }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeAddSipTransports(
        JNIEnv* env, jclass /*clazz*/,
        jstring jLocalIp, jint portC, jint portS)
{
    if (jLocalIp == nullptr) return JNI_FALSE;
    const char* utf = env->GetStringUTFChars(jLocalIp, nullptr);
    if (!utf) return JNI_FALSE;
    std::string localIp(utf);
    env->ReleaseStringUTFChars(jLocalIp, utf);
    try {
        return Bridge::instance().addSipTransports(localIp, portC, portS)
                ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

namespace {
std::string jstrUtf(JNIEnv* env, jstring j) {
    if (!j) return {};
    const char* p = env->GetStringUTFChars(j, nullptr);
    if (!p) return {};
    std::string s(p);
    env->ReleaseStringUTFChars(j, p);
    return s;
}
}

extern "C" JNIEXPORT void JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeSetCallSessionListener(
        JNIEnv* env, jclass /*clazz*/, jobject listener)
{
    if (gCallListener) {
        env->DeleteGlobalRef(gCallListener);
        gCallListener = nullptr;
        gOnCallProvisional = nullptr;
        gOnCallConnected = nullptr;
        gOnCallTerminated = nullptr;
        gOnCallFailure = nullptr;
        gOnCallAnswer = nullptr;
        gOnCallAnswerVideo = nullptr;
        gOnRemoteReinvite = nullptr;
    }
    if (!listener) return;
    gCallListener = env->NewGlobalRef(listener);
    jclass cls = env->GetObjectClass(listener);
    gOnCallProvisional = env->GetMethodID(cls, "onProvisional", "(ILjava/lang/String;)V");
    gOnCallConnected   = env->GetMethodID(cls, "onConnected",   "(ILjava/lang/String;)V");
    gOnCallTerminated  = env->GetMethodID(cls, "onTerminated",  "(ILjava/lang/String;)V");
    gOnCallFailure     = env->GetMethodID(cls, "onFailure",     "(ILjava/lang/String;)V");
    gOnCallAnswer      = env->GetMethodID(cls, "onAnswer",
            "(Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;)V");
    gOnCallAnswerVideo = env->GetMethodID(cls, "onAnswerVideo",
            "(Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;)V");
    if (env->ExceptionCheck()) {
        /* onAnswerVideo is optional on listeners that pre-date C.2;
         * clear NoSuchMethodError so we don't leave a pending
         * exception when the Bridge returns to the VM. */
        env->ExceptionClear();
        gOnCallAnswerVideo = nullptr;
    }
    /* Signature: (audioIp, audioPort, audioRtcp, audioPt, audioRate,
     *             audioCodec, audioFmtp,
     *             videoIp, videoPort, videoRtcp, videoPt, videoRate,
     *             videoCodec, videoFmtp) — video fields carry port=0
     *             when the remote re-INVITE dropped video. */
    gOnRemoteReinvite = env->GetMethodID(cls, "onRemoteReinvite",
            "(Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;"
            "Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        gOnRemoteReinvite = nullptr;
    }
    env->DeleteLocalRef(cls);
}

extern "C" JNIEXPORT void JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeSetCellIdForPani(
        JNIEnv* env, jclass /*clazz*/, jstring jVal)
{
    try { Bridge::instance().setCellIdForPani(jstrUtf(env, jVal)); }
    catch (...) {}
}

extern "C" JNIEXPORT void JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeSetIwlanNodeIdForPani(
        JNIEnv* env, jclass /*clazz*/, jstring jVal)
{
    try { Bridge::instance().setIwlanNodeIdForPani(jstrUtf(env, jVal)); }
    catch (...) {}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeStartCall(
        JNIEnv* env, jclass /*clazz*/, jstring jTarget, jstring jSdp)
{
    try {
        return Bridge::instance().startCall(
                jstrUtf(env, jTarget), jstrUtf(env, jSdp))
                ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeReinvite(
        JNIEnv* env, jclass /*clazz*/, jstring jSdp)
{
    if (!jSdp) return JNI_FALSE;
    try {
        return Bridge::instance().reinvite(
                jstrUtf(env, jSdp)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeProvideReinviteAnswer(
        JNIEnv* env, jclass /*clazz*/, jstring jSdp)
{
    if (!jSdp) return JNI_FALSE;
    try {
        return Bridge::instance().provideReinviteAnswer(
                jstrUtf(env, jSdp)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeEndCallByCallId(
        JNIEnv* env, jclass /*clazz*/, jstring jCallId)
{
    if (!jCallId) return JNI_FALSE;
    try {
        return Bridge::instance().endCallByCallId(
                jstrUtf(env, jCallId)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT void JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeEndCall(
        JNIEnv* /*env*/, jclass /*clazz*/)
{
    try { Bridge::instance().endCall(); } catch (...) {}
}

extern "C" JNIEXPORT void JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeSetIncomingCallListener(
        JNIEnv* env, jclass /*clazz*/, jobject listener)
{
    if (gIncomingCallListener) {
        env->DeleteGlobalRef(gIncomingCallListener);
        gIncomingCallListener = nullptr;
        gOnIncomingInvite = nullptr;
        gOnIncomingInviteVideo = nullptr;
        gOnIncomingCancelled = nullptr;
    }
    if (!listener) return;
    gIncomingCallListener = env->NewGlobalRef(listener);
    jclass cls = env->GetObjectClass(listener);
    /* Signature: (callId, fromUri, remoteIp, remotePort, rtcpPort,
     *             pt, clockRate, codecName, fmtp) */
    gOnIncomingInvite = env->GetMethodID(cls, "onIncomingInvite",
            "(Ljava/lang/String;Ljava/lang/String;"
            "Ljava/lang/String;IIIILjava/lang/String;Ljava/lang/String;)V");
    gOnIncomingInviteVideo = env->GetMethodID(cls, "onIncomingInviteVideo",
            "(Ljava/lang/String;Ljava/lang/String;"
            "IIIILjava/lang/String;Ljava/lang/String;)V");
    if (env->ExceptionCheck()) {
        /* Optional — pre-C.5 listeners don't have this method. */
        env->ExceptionClear();
        gOnIncomingInviteVideo = nullptr;
    }
    gOnIncomingCancelled = env->GetMethodID(cls, "onIncomingCancelled",
            "(Ljava/lang/String;I)V");
    env->DeleteLocalRef(cls);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeProgressRinging(
        JNIEnv* env, jclass /*clazz*/, jstring jCallId)
{
    try {
        return Bridge::instance().progressRinging(jstrUtf(env, jCallId))
                ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeAcceptIncomingCall(
        JNIEnv* env, jclass /*clazz*/, jstring jCallId, jstring jSdp)
{
    try {
        return Bridge::instance().acceptIncomingCall(
                jstrUtf(env, jCallId), jstrUtf(env, jSdp))
                ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeRejectIncomingCall(
        JNIEnv* env, jclass /*clazz*/, jstring jCallId, jint sipCode)
{
    try {
        return Bridge::instance().rejectIncomingCall(
                jstrUtf(env, jCallId), (int)sipCode) ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT void JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeSetSmsListener(
        JNIEnv* env, jclass /*clazz*/, jobject listener)
{
    if (gSmsListener) {
        env->DeleteGlobalRef(gSmsListener);
        gSmsListener = nullptr;
        gOnSmsSendSuccess = nullptr;
        gOnSmsSendFailure = nullptr;
        gOnSmsIncoming = nullptr;
    }
    if (!listener) return;
    gSmsListener = env->NewGlobalRef(listener);
    jclass cls = env->GetObjectClass(listener);
    gOnSmsSendSuccess = env->GetMethodID(cls, "onSendSuccess", "(ILjava/lang/String;)V");
    gOnSmsSendFailure = env->GetMethodID(cls, "onSendFailure", "(ILjava/lang/String;)V");
    gOnSmsIncoming    = env->GetMethodID(cls, "onIncomingSms", "([B)V");
    env->DeleteLocalRef(cls);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeGetLastMtPai(
        JNIEnv* env, jclass /*clazz*/)
{
    std::lock_guard<std::mutex> g(gLastMtPaiMu);
    return env->NewStringUTF(gLastMtPai.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeSendSms(
        JNIEnv* env, jclass /*clazz*/,
        jstring jTarget, jstring jContentType, jbyteArray jBody)
{
    if (!jBody) return JNI_FALSE;
    try {
        std::vector<uint8_t> body(env->GetArrayLength(jBody));
        env->GetByteArrayRegion(jBody, 0, (jsize)body.size(), (jbyte*)body.data());
        return Bridge::instance().sendSms(
                jstrUtf(env, jTarget),
                jstrUtf(env, jContentType),
                body) ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT void JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeSetRegistrationListener(
        JNIEnv* env, jclass /*clazz*/, jobject listener)
{
    if (gRegListener) {
        env->DeleteGlobalRef(gRegListener);
        gRegListener = nullptr;
        gOnRegisteredMethod = nullptr;
        gOnDeregisteredMethod = nullptr;
        gOnRegisterExpiresReported = nullptr;
    }
    if (!listener) return;
    gRegListener = env->NewGlobalRef(listener);
    jclass cls = env->GetObjectClass(listener);
    gOnRegisteredMethod   = env->GetMethodID(cls, "onRegistered",   "(Ljava/lang/String;)V");
    gOnDeregisteredMethod = env->GetMethodID(cls, "onDeregistered", "(ILjava/lang/String;I)V");
    gOnRegisterExpiresReported = env->GetMethodID(cls, "onExpiresReported", "(I)V");
    env->DeleteLocalRef(cls);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeRefreshRegister(
        JNIEnv* /*env*/, jclass /*clazz*/)
{
    try {
        return Bridge::instance().refreshRegister() ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_hamelinports_ims_sip_HamelinPortsSipStack_nativeStartRegister(
        JNIEnv* env, jclass /*clazz*/,
        jstring jImpi, jstring jImpu, jstring jDomain,
        jint expirySec, jstring jInstanceId,
        jstring jPcscfHost, jint pcscfCleartextPort,
        jstring jSecurityClient)
{
    try {
        return Bridge::instance().startRegister(
                jstrUtf(env, jImpi),
                jstrUtf(env, jImpu),
                jstrUtf(env, jDomain),
                expirySec,
                jstrUtf(env, jInstanceId),
                jstrUtf(env, jPcscfHost),
                pcscfCleartextPort,
                jstrUtf(env, jSecurityClient))
                ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}
