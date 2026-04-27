package org.hamelinports.ims.sip;

import android.util.Log;

/**
 * Thin wrapper around the native reSIProcate SIP stack.
 *
 * Drives the full IMS REGISTER cycle through reSIProcate's DUM:
 *   1. {@link #start()} — native SipStack + DialogUsageManager up
 *   2. {@link #addSipTransports} — TCP transports bound to UE port-c
 *      and port-s (kernel xfrm SAs are NOT installed yet — they get
 *      installed by the AkaProvider during step 4)
 *   3. {@link #setAkaProvider} — Java callback for AKA + xfrm install
 *   4. {@link #startRegister} — native sends REGISTER 1 cleartext to
 *      pcscf:5060; on 401 the auth manager AKAs, installs SAs (via the
 *      Java provider), retargets REGISTER 2 to pcscf:port-s
 *
 * This is the ONLY SIP path. The previous Java SipClient.buildRegister
 * code path is being removed alongside this migration.
 */
public final class HamelinPortsSipStack {

    private static final String TAG = "HamelinPortsIms:Stack";
    private static boolean sNativeAvailable;

    static {
        try {
            System.loadLibrary("hamelinports_ims_jni");
            sNativeAvailable = true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "native bridge unavailable: " + e.getMessage());
            sNativeAvailable = false;
        }
    }

    private HamelinPortsSipStack() {}

    private static native String  nativeGetStackVersion();
    private static native boolean nativeStart();
    private static native void    nativeStop();
    private static native String  nativeGetStatus();
    private static native boolean nativeAddSipTransports(String localIp, int portC, int portS);
    private static native void    nativeSetAkaProvider(AkaProvider provider);
    private static native void    nativeSetRegistrationListener(RegistrationListener listener);
    private static native void    nativeSetCallSessionListener(CallSessionListener listener);
    private static native void    nativeSetSmsListener(SmsSessionListener listener);
    private static native boolean nativeSendSms(String targetUri, String contentType, byte[] body);
    private static native String  nativeGetLastMtPai();
    private static native boolean nativeStartRegister(
            String impi, String impu, String domain,
            int expirySec, String instanceId,
            String pcscfHost, int pcscfCleartextPort,
            String securityClient);
    private static native boolean nativeRefreshRegister();
    private static native void    nativeSetCellIdForPani(String value);
    private static native void    nativeSetIwlanNodeIdForPani(String value);
    private static native boolean nativeStartCall(String targetUri, String sdpOffer);
    private static native void    nativeEndCall();
    private static native boolean nativeEndCallByCallId(String callId);
    private static native boolean nativeReinvite(String sdpOffer);
    private static native boolean nativeProvideReinviteAnswer(String sdpAnswer);
    private static native void    nativeSetIncomingCallListener(IncomingCallListener listener);
    private static native boolean nativeProgressRinging(String callId);
    private static native boolean nativeAcceptIncomingCall(String callId, String sdpAnswer);
    private static native boolean nativeRejectIncomingCall(String callId, int sipCode);

    /** True if libnative_ims_jni.so loaded successfully. */
    public static boolean isAvailable() {
        return sNativeAvailable;
    }

    /** Banner string from the native stack, or null on failure. */
    public static String getStackVersion() {
        if (!sNativeAvailable) return null;
        try {
            return nativeGetStackVersion();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Bring the native stack up (no transport yet). Idempotent. */
    public static boolean start() {
        if (!sNativeAvailable) return false;
        try {
            return nativeStart();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Tear the native stack down. */
    public static void stop() {
        if (!sNativeAvailable) return;
        try {
            nativeStop();
        } catch (UnsatisfiedLinkError e) {
            // ignore
        }
    }

    /** Returns "stopped" or "started; transports=N" */
    public static String getStatus() {
        if (!sNativeAvailable) return "unavailable";
        try {
            return nativeGetStatus();
        } catch (UnsatisfiedLinkError e) {
            return "unavailable";
        }
    }

    /**
     * Add the two TS 33.203 §7.4 SIP transports — TCP listeners bound
     * to (localIp, portC) and (localIp, portS). The kernel xfrm
     * policies installed by ims_xfrm before this call must already
     * cover both directions for both ports; reSIProcate's TcpTransport
     * speaks plain TCP and the kernel transparently wraps it in ESP.
     *
     * Caller must have invoked {@link #start()} first. Idempotent
     * is NOT guaranteed — calling twice will create duplicate
     * transports; tear down with {@link #stop()} between attempts.
     *
     * @return true on success, false if the bridge isn't started or
     *         the bind failed.
     */
    public static boolean addSipTransports(String localIp, int portC, int portS) {
        if (!sNativeAvailable) return false;
        try {
            return nativeAddSipTransports(localIp, portC, portS);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /**
     * Register the {@link AkaProvider} the native AKAv1-MD5 extension
     * upcalls into when an IMS challenge arrives. Pass {@code null}
     * to clear.
     *
     * Must be called before {@link #start()} returns 200 OK on a
     * REGISTER, otherwise the first auth challenge will fail.
     */
    public static void setAkaProvider(AkaProvider provider) {
        if (!sNativeAvailable) return;
        try {
            nativeSetAkaProvider(provider);
        } catch (UnsatisfiedLinkError e) {
            // ignore
        }
    }

    /** Register the listener that native fires on REGISTER success/failure. */
    public static void setRegistrationListener(RegistrationListener listener) {
        if (!sNativeAvailable) return;
        try {
            nativeSetRegistrationListener(listener);
        } catch (UnsatisfiedLinkError e) {
            // ignore
        }
    }

    /** Register the listener that native fires on call lifecycle events.
     *  Only one active outbound call is supported at a time. */
    public static void setCallSessionListener(CallSessionListener listener) {
        if (!sNativeAvailable) return;
        try { nativeSetCallSessionListener(listener); }
        catch (UnsatisfiedLinkError e) {}
    }

    /** Update the P-Access-Network-Info utran-cell-id-3gpp value for
     *  subsequent IPsec-protected non-REGISTER outbound requests
     *  (INVITE, MESSAGE, SUBSCRIBE). MMTel TAS routes MO calls based
     *  on this — set it to the current LTE cell before
     *  {@link #startCall} or the call will land on the announcement
     *  TAS as UNALLOCATED_NUMBER. Format: MCC+MNC+TAC(4 hex)+CI(7 hex). */
    public static void setCellIdForPani(String value) {
        if (!sNativeAvailable) return;
        try { nativeSetCellIdForPani(value); }
        catch (UnsatisfiedLinkError e) {}
    }

    /** Update the P-Access-Network-Info i-wlan-node-id value (Wi-Fi
     *  Calling path, TS 24.229 §7.2A.4). The decorator emits an
     *  IEEE-802.11 PANI token on the next non-REGISTER outbound
     *  request when this is set. {@code value} is the AP BSSID with
     *  colons stripped and uppercase (e.g. "B6FC7D11A6B0"). Mutually
     *  exclusive with {@link #setCellIdForPani(String)} — the native
     *  side clears the other when this is set, so callers should
     *  pick the right setter for the bound underlying access type and
     *  not try to keep both warm. */
    public static void setIwlanNodeIdForPani(String value) {
        if (!sNativeAvailable) return;
        try { nativeSetIwlanNodeIdForPani(value); }
        catch (UnsatisfiedLinkError e) {}
    }

    /**
     * Place an outbound call.
     *
     * @param targetUri full SIP Request-URI as a string. For Telefonica DE
     *                  the TAS demands NATIONAL form + phone-context, e.g.
     *                  "sip:015732220078;phone-context=telefonica.de@telefonica.de;user=phone".
     * @param sdpOffer  the SDP body Java has constructed for the m=audio line.
     * @return true if the INVITE was queued; outcome arrives via the
     *         registered {@link CallSessionListener}.
     */
    public static boolean startCall(String targetUri, String sdpOffer) {
        if (!sNativeAvailable) return false;
        try { return nativeStartCall(targetUri, sdpOffer); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** End the active outbound call (BYE if connected, CANCEL if early). */
    public static void endCall() {
        if (!sNativeAvailable) return;
        try { nativeEndCall(); }
        catch (UnsatisfiedLinkError e) {}
    }

    /** End the MT server-side call keyed on Call-ID. Required for the
     *  local-hangup path on an answered MT call because {@link #endCall}
     *  only acts on the MO {@code ClientInviteSessionHandle}. */
    public static boolean endCallByCallId(String callId) {
        if (!sNativeAvailable) return false;
        try { return nativeEndCallByCallId(callId); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Send a mid-call re-INVITE on the active InviteSession with a
     *  new SDP offer — used for video upgrade/downgrade (C.4). Must
     *  only be called while the session is in the Connected state;
     *  the native reSIProcate layer throws if not, and this returns
     *  false. Response arrives via
     *  {@link CallSessionListener#onAnswer} /
     *  {@link CallSessionListener#onAnswerVideo}. */
    public static boolean reinvite(String sdpOffer) {
        if (!sNativeAvailable) return false;
        try { return nativeReinvite(sdpOffer); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Phase C.4.3 — respond 200 OK with {@code sdpAnswer} to a
     *  remote-initiated re-INVITE that arrived via
     *  {@link CallSessionListener#onRemoteReinvite}. */
    public static boolean provideReinviteAnswer(String sdpAnswer) {
        if (!sNativeAvailable) return false;
        try { return nativeProvideReinviteAnswer(sdpAnswer); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Register the listener the native stack fires when an MT INVITE
     *  arrives. Separate from {@link #setCallSessionListener} because
     *  the MT path needs a service-lifetime subscriber (set by
     *  {@code HamelinPortsMmTelFeature}) that can create a fresh
     *  {@code HamelinPortsIncomingCallSession} per incoming call.
     *
     *  <p>Last-writer-wins: the native side holds a single global
     *  reference. In a dual-slot process the {@code HamelinPortsMmTelFeature}
     *  for the active-SIM slot binds from {@code onRegistered()} so
     *  MT routes to the correct slot's ImsPhoneCallTracker.</p> */
    public static void setIncomingCallListener(IncomingCallListener listener) {
        if (!sNativeAvailable) return;
        sIncomingCallListener = listener;
        try { nativeSetIncomingCallListener(listener); }
        catch (UnsatisfiedLinkError e) {}
    }

    private static volatile IncomingCallListener sIncomingCallListener;

    /** Drop the MT listener only if {@code self} is currently bound.
     *  Called from {@code onDeregistered()} to avoid clobbering a
     *  listener that another slot may have just bound during handover. */
    public static void clearIncomingCallListenerIfSelf(IncomingCallListener self) {
        if (!sNativeAvailable) return;
        if (sIncomingCallListener != self) return;
        sIncomingCallListener = null;
        try { nativeSetIncomingCallListener(null); }
        catch (UnsatisfiedLinkError e) {}
    }

    /** Send {@code 180 Ringing} on the MT dialog matching
     *  {@code callId}. Reliable (100rel + RSeq + PRACK handshake)
     *  when the incoming INVITE required it, which Mavenir on o2-de
     *  does. */
    public static boolean progressRinging(String callId) {
        if (!sNativeAvailable) return false;
        try { return nativeProgressRinging(callId); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Accept the MT INVITE matching {@code callId} with a final
     *  200 OK carrying {@code sdpAnswer}. The SDP answer's m-line
     *  count + order MUST match the offer (RFC 3264 §5.1), with
     *  local RTP/RTCP ports bound to the IMS PDN's IPv6. */
    public static boolean acceptIncomingCall(String callId, String sdpAnswer) {
        if (!sNativeAvailable) return false;
        try { return nativeAcceptIncomingCall(callId, sdpAnswer); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Reject the MT INVITE matching {@code callId} with a SIP error
     *  code. Typical values: 486 Busy Here, 480 Temporarily
     *  Unavailable, 603 Decline (3GPP TS 24.229 §5.1.3). */
    public static boolean rejectIncomingCall(String callId, int sipCode) {
        if (!sNativeAvailable) return false;
        try { return nativeRejectIncomingCall(callId, sipCode); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /**
     * Kick off an IMS REGISTER cycle via reSIProcate's DialogUsageManager.
     *
     * Caller MUST have:
     *   - invoked {@link #start()}
     *   - invoked {@link #addSipTransports} for both UE port-c and port-s
     *     (kernel xfrm SAs are NOT installed yet — that happens
     *     mid-cycle via the AkaProvider)
     *   - registered an {@link AkaProvider} via {@link #setAkaProvider}
     *
     * The {@code securityClient} string is the full
     * {@code Security-Client} header value (algorithm + parameters) as
     * Java has computed it for the IPsec offer; the decorator splats
     * it onto every outbound REGISTER. After the 401, the native auth
     * manager parses the P-CSCF's Security-Server, calls the provider
     * (which runs USIM AKA AND installs the four xfrm SAs), then
     * mutates the REGISTER's Route to (pcscfHost, port-s-pcscf) so
     * DUM auto-retries onto the IPsec-protected port pair.
     *
     * @param pcscfCleartextPort the P-CSCF port for REGISTER 1
     *                           (typically 5060)
     * @return true if REGISTER 1 was queued; the outcome arrives via
     *         the registration handler in native logs (logcat tag
     *         LineageIms-JNI).
     */
    public static boolean startRegister(String impi, String impu, String domain,
                                        int expirySec, String instanceId,
                                        String pcscfHost, int pcscfCleartextPort,
                                        String securityClient) {
        if (!sNativeAvailable) return false;
        try {
            return nativeStartRegister(impi, impu, domain, expirySec,
                                       instanceId, pcscfHost, pcscfCleartextPort,
                                       securityClient);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Explicit refresh against the already-registered P-CSCF. Calls
     *  reSIProcate's {@code ClientRegistration::requestRefresh} on the
     *  stored handle — required because DUM's internal auto-refresh
     *  has been observed to silently stop firing after the initial
     *  200 OK on Mavenir, causing MT voice to fall back to CSFB once
     *  the modem's NAS "VoPS" preference decays without a fresh
     *  REGISTER keeping the registration visible to the TAS. */
    public static boolean refreshRegister() {
        if (!sNativeAvailable) return false;
        try { return nativeRefreshRegister(); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Register the listener that native fires for inbound SIP MESSAGE
     *  and for the result of {@link #sendSms}. */
    public static void setSmsListener(SmsSessionListener listener) {
        if (!sNativeAvailable) return;
        try { nativeSetSmsListener(listener); }
        catch (UnsatisfiedLinkError e) {}
    }

    /**
     * Send a SIP MESSAGE (MO SMS-over-IMS).
     *
     * @param targetUri full Request-URI; for Telefonica DE the o2 SMSC,
     *                  e.g. "sip:+491770610000@telefonica.de"
     * @param contentType MIME content-type, typically
     *                    "application/vnd.3gpp.sms"
     * @param body opaque body bytes (typically RP-DATA wrapping the TPDU)
     * @return true if queued; the outcome arrives via {@link SmsSessionListener}.
     */
    public static boolean sendSms(String targetUri, String contentType, byte[] body) {
        if (!sNativeAvailable) return false;
        try { return nativeSendSms(targetUri, contentType, body); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** P-Asserted-Identity of the most recent incoming MT MESSAGE.
     *  Used by the MT handler to route the separate RP-ACK back to
     *  the exact SMSC instance that sent us the MT. Returns "" if
     *  no MT has been received yet or no PAI was present. */
    public static String getLastMtPai() {
        if (!sNativeAvailable) return "";
        try { return nativeGetLastMtPai(); }
        catch (UnsatisfiedLinkError e) { return ""; }
    }
}
