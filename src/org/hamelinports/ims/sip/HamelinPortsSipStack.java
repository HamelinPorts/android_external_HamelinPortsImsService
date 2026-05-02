package org.hamelinports.ims.sip;

import android.util.Log;

/**
 * Per-slot wrapper around the native reSIProcate SIP stack.
 *
 * One instance per SIM slot; {@code ImsRegistrationController} owns it
 * and threads it through {@code HamelinPortsMmTelFeature},
 * {@code HamelinPortsCallSession}, {@code HamelinPortsIncomingCallSession}
 * and {@code HamelinPortsSmsImpl}. Every native method takes a slot id
 * and the JNI side keeps a per-slot Bridge (SipStack + DUM + handlers +
 * Java listener jobjects) so two slots can register, place calls and
 * exchange SMS independently without sharing state at the C++ singleton
 * level.
 *
 * <p>Drives the full IMS REGISTER cycle through reSIProcate's DUM:
 *   1. {@link #start()} — native SipStack + DialogUsageManager up
 *   2. {@link #addSipTransports} — TCP transports bound to UE port-c
 *      and port-s (kernel xfrm SAs are NOT installed yet — they get
 *      installed by the AkaProvider during step 4)
 *   3. {@link #setAkaProvider} — Java callback for AKA + xfrm install
 *   4. {@link #startRegister} — native sends REGISTER 1 cleartext to
 *      pcscf:5060; on 401 the auth manager AKAs, installs SAs (via the
 *      Java provider), retargets REGISTER 2 to pcscf:port-s
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

    private final int mSlotId;

    public HamelinPortsSipStack(int slotId) {
        mSlotId = slotId;
    }

    public int getSlotId() {
        return mSlotId;
    }

    private static native String  nativeGetStackVersion();
    private static native boolean nativeStart(int slotId);
    private static native void    nativeStop(int slotId);
    private static native String  nativeGetStatus(int slotId);
    private static native boolean nativeAddSipTransports(int slotId, String localIp, int portC, int portS);
    private static native void    nativeSetAkaProvider(int slotId, AkaProvider provider);
    private static native void    nativeSetRegistrationListener(int slotId, RegistrationListener listener);
    private static native void    nativeSetCallSessionListener(int slotId, CallSessionListener listener);
    private static native void    nativeSetSmsListener(int slotId, SmsSessionListener listener);
    private static native boolean nativeSendSms(int slotId, String targetUri, String contentType, byte[] body);
    private static native String  nativeGetLastMtPai(int slotId);
    private static native boolean nativeStartRegister(int slotId,
            String impi, String impu, String domain,
            int expirySec, String instanceId,
            String pcscfHost, int pcscfCleartextPort,
            String securityClient);
    private static native boolean nativeRefreshRegister(int slotId);
    private static native void    nativeSetCellIdForPani(int slotId, String value);
    private static native void    nativeSetIwlanNodeIdForPani(int slotId, String value);
    private static native boolean nativeStartCall(int slotId, String targetUri, String sdpOffer);
    private static native void    nativeEndCall(int slotId);
    private static native boolean nativeEndCallByCallId(int slotId, String callId);
    private static native boolean nativeReinvite(int slotId, String sdpOffer);
    private static native boolean nativeProvideReinviteAnswer(int slotId, String sdpAnswer);
    private static native void    nativeSetIncomingCallListener(int slotId, IncomingCallListener listener);
    private static native boolean nativeProgressRinging(int slotId, String callId);
    private static native boolean nativeAcceptIncomingCall(int slotId, String callId, String sdpAnswer);
    private static native boolean nativeRejectIncomingCall(int slotId, String callId, int sipCode);

    /** True if libnative_ims_jni.so loaded successfully. Process-global. */
    public static boolean isAvailable() {
        return sNativeAvailable;
    }

    /** Banner string from the native stack, or null on failure.
     *  Process-global — the banner is the resiprocate library version. */
    public static String getStackVersion() {
        if (!sNativeAvailable) return null;
        try {
            return nativeGetStackVersion();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Bring the native stack up (no transport yet). Idempotent. */
    public boolean start() {
        if (!sNativeAvailable) return false;
        try {
            return nativeStart(mSlotId);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Tear the native stack down. */
    public void stop() {
        if (!sNativeAvailable) return;
        try {
            nativeStop(mSlotId);
        } catch (UnsatisfiedLinkError e) {
            // ignore
        }
    }

    /** Returns "stopped" or "started; transports=N" */
    public String getStatus() {
        if (!sNativeAvailable) return "unavailable";
        try {
            return nativeGetStatus(mSlotId);
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
    public boolean addSipTransports(String localIp, int portC, int portS) {
        if (!sNativeAvailable) return false;
        try {
            return nativeAddSipTransports(mSlotId, localIp, portC, portS);
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
    public void setAkaProvider(AkaProvider provider) {
        if (!sNativeAvailable) return;
        try {
            nativeSetAkaProvider(mSlotId, provider);
        } catch (UnsatisfiedLinkError e) {
            // ignore
        }
    }

    /** Register the listener that native fires on REGISTER success/failure. */
    public void setRegistrationListener(RegistrationListener listener) {
        if (!sNativeAvailable) return;
        try {
            nativeSetRegistrationListener(mSlotId, listener);
        } catch (UnsatisfiedLinkError e) {
            // ignore
        }
    }

    /** Register the listener that native fires on call lifecycle events.
     *  Only one active outbound call is supported at a time. */
    public void setCallSessionListener(CallSessionListener listener) {
        if (!sNativeAvailable) return;
        try { nativeSetCallSessionListener(mSlotId, listener); }
        catch (UnsatisfiedLinkError e) {}
    }

    /** Update the P-Access-Network-Info utran-cell-id-3gpp value for
     *  subsequent IPsec-protected non-REGISTER outbound requests
     *  (INVITE, MESSAGE, SUBSCRIBE). MMTel TAS routes MO calls based
     *  on this — set it to the current LTE cell before
     *  {@link #startCall} or the call will land on the announcement
     *  TAS as UNALLOCATED_NUMBER. Format: MCC+MNC+TAC(4 hex)+CI(7 hex). */
    public void setCellIdForPani(String value) {
        if (!sNativeAvailable) return;
        try { nativeSetCellIdForPani(mSlotId, value); }
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
    public void setIwlanNodeIdForPani(String value) {
        if (!sNativeAvailable) return;
        try { nativeSetIwlanNodeIdForPani(mSlotId, value); }
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
    public boolean startCall(String targetUri, String sdpOffer) {
        if (!sNativeAvailable) return false;
        try { return nativeStartCall(mSlotId, targetUri, sdpOffer); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** End the active outbound call (BYE if connected, CANCEL if early). */
    public void endCall() {
        if (!sNativeAvailable) return;
        try { nativeEndCall(mSlotId); }
        catch (UnsatisfiedLinkError e) {}
    }

    /** End the MT server-side call keyed on Call-ID. Required for the
     *  local-hangup path on an answered MT call because {@link #endCall}
     *  only acts on the MO {@code ClientInviteSessionHandle}. */
    public boolean endCallByCallId(String callId) {
        if (!sNativeAvailable) return false;
        try { return nativeEndCallByCallId(mSlotId, callId); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Send a mid-call re-INVITE on the active InviteSession with a
     *  new SDP offer — used for video upgrade/downgrade (C.4). Must
     *  only be called while the session is in the Connected state;
     *  the native reSIProcate layer throws if not, and this returns
     *  false. Response arrives via
     *  {@link CallSessionListener#onAnswer} /
     *  {@link CallSessionListener#onAnswerVideo}. */
    public boolean reinvite(String sdpOffer) {
        if (!sNativeAvailable) return false;
        try { return nativeReinvite(mSlotId, sdpOffer); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Phase C.4.3 — respond 200 OK with {@code sdpAnswer} to a
     *  remote-initiated re-INVITE that arrived via
     *  {@link CallSessionListener#onRemoteReinvite}. */
    public boolean provideReinviteAnswer(String sdpAnswer) {
        if (!sNativeAvailable) return false;
        try { return nativeProvideReinviteAnswer(mSlotId, sdpAnswer); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Register the listener the native stack fires when an MT INVITE
     *  arrives. Per-slot — phase 4B fanned this out so each slot's
     *  MmTelFeature can stay subscribed to its own slot's MT events
     *  without colliding with the other slot's listener. */
    public void setIncomingCallListener(IncomingCallListener listener) {
        if (!sNativeAvailable) return;
        try { nativeSetIncomingCallListener(mSlotId, listener); }
        catch (UnsatisfiedLinkError e) {}
    }

    /** Drop the MT listener regardless of who set it. The native side
     *  is per-slot, so this only affects the calling slot's listener
     *  and never disturbs the other slot's registration. The {@code self}
     *  parameter is retained for source compatibility with pre-4B
     *  callers who used it as a "don't clobber another slot's listener"
     *  guard; it's now redundant. */
    public void clearIncomingCallListenerIfSelf(IncomingCallListener self) {
        setIncomingCallListener(null);
    }

    /** Send {@code 180 Ringing} on the MT dialog matching
     *  {@code callId}. Reliable (100rel + RSeq + PRACK handshake)
     *  when the incoming INVITE required it, which Mavenir on o2-de
     *  does. */
    public boolean progressRinging(String callId) {
        if (!sNativeAvailable) return false;
        try { return nativeProgressRinging(mSlotId, callId); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Accept the MT INVITE matching {@code callId} with a final
     *  200 OK carrying {@code sdpAnswer}. The SDP answer's m-line
     *  count + order MUST match the offer (RFC 3264 §5.1), with
     *  local RTP/RTCP ports bound to the IMS PDN's IPv6. */
    public boolean acceptIncomingCall(String callId, String sdpAnswer) {
        if (!sNativeAvailable) return false;
        try { return nativeAcceptIncomingCall(mSlotId, callId, sdpAnswer); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Reject the MT INVITE matching {@code callId} with a SIP error
     *  code. Typical values: 486 Busy Here, 480 Temporarily
     *  Unavailable, 603 Decline (3GPP TS 24.229 §5.1.3). */
    public boolean rejectIncomingCall(String callId, int sipCode) {
        if (!sNativeAvailable) return false;
        try { return nativeRejectIncomingCall(mSlotId, callId, sipCode); }
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
    public boolean startRegister(String impi, String impu, String domain,
                                 int expirySec, String instanceId,
                                 String pcscfHost, int pcscfCleartextPort,
                                 String securityClient) {
        if (!sNativeAvailable) return false;
        try {
            return nativeStartRegister(mSlotId, impi, impu, domain, expirySec,
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
    public boolean refreshRegister() {
        if (!sNativeAvailable) return false;
        try { return nativeRefreshRegister(mSlotId); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Register the listener that native fires for inbound SIP MESSAGE
     *  and for the result of {@link #sendSms}. */
    public void setSmsListener(SmsSessionListener listener) {
        if (!sNativeAvailable) return;
        try { nativeSetSmsListener(mSlotId, listener); }
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
    public boolean sendSms(String targetUri, String contentType, byte[] body) {
        if (!sNativeAvailable) return false;
        try { return nativeSendSms(mSlotId, targetUri, contentType, body); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** P-Asserted-Identity of the most recent incoming MT MESSAGE.
     *  Used by the MT handler to route the separate RP-ACK back to
     *  the exact SMSC instance that sent us the MT. Returns "" if
     *  no MT has been received yet or no PAI was present. */
    public String getLastMtPai() {
        if (!sNativeAvailable) return "";
        try { return nativeGetLastMtPai(mSlotId); }
        catch (UnsatisfiedLinkError e) { return ""; }
    }
}
