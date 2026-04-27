package org.hamelinports.ims;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.telephony.PreciseCallState;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.SrvccCall;
import android.telephony.ims.feature.CapabilityChangeRequest;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.util.Log;

import org.hamelinports.ims.sip.IncomingCallListener;
import org.hamelinports.ims.sip.HamelinPortsSipStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class HamelinPortsMmTelFeature extends MmTelFeature implements IncomingCallListener {
    private static final String TAG = HamelinPortsImsService.TAG;

    private final Context mContext;
    private final int mSlotId;
    private final ImsRegistrationController mRegController;
    private final HamelinPortsSmsImpl mSmsImpl;
    private final SrvccMonitor mSrvcc;

    HamelinPortsMmTelFeature(Context context, int slotId,
                        ImsRegistrationController regController) {
        mContext = context;
        mSlotId = slotId;
        mRegController = regController;
        mSmsImpl = new HamelinPortsSmsImpl(regController);
        /* SrvccMonitor is observer-only now (Phase I.3): the framework
         * drives the authoritative SRVCC handling through the four
         * notifySrvcc* overrides on MmTelFeature, not through the
         * TelephonyCallback.SrvccStateListener path. The monitor stays
         * so we log the observer-side transitions too — useful for
         * correlating "when did the framework's state cache update"
         * against "when did the framework call our notifySrvcc*"
         * during a field post-mortem. */
        mSrvcc = new SrvccMonitor(context, slotId);
        /* MT INVITE listener is bound lazily in onRegistered() — the
         * native setter is process-global and last-writer-wins, so
         * binding here would make the later-created slot steal MT
         * from the slot that actually holds the SIM + registration.
         * Observed 2026-04-23: both slot 0 and slot 1 MmTelFeature
         * ran setIncomingCallListener in their ctor; slot 0 (no SIM,
         * subId -1) won and ImsPhoneCallTracker[0]'s takeCall() fell
         * back to "ImsCallSession is not alive" — phone never rang. */
        setFeatureState(STATE_READY);
        Log.i(TAG, "MmTelFeature created, STATE_READY set, SMS wired (MT bound lazily on register)");
    }

    @Override
    public void onFeatureReady() {
        Log.i(TAG, "onFeatureReady slotId=" + mSlotId);
        mRegController.start();
        /* Delay SRVCC subscription: the SubscriptionManager slot→sub
         * mapping isn't guaranteed until after SIM provisioning, which
         * may land slightly after onFeatureReady. If the subscription
         * isn't ready yet, SrvccMonitor logs and bails; revisit with
         * a retry on SubscriptionManager.OnSubscriptionsChangedListener
         * if we see missed registrations in practice. */
        mSrvcc.start();
    }

    @Override
    public void onFeatureRemoved() {
        Log.i(TAG, "onFeatureRemoved slotId=" + mSlotId);
        mSrvcc.stop();
        mRegController.stop();
    }

    @Override
    public void changeEnabledCapabilities(CapabilityChangeRequest request,
                                          CapabilityCallbackProxy c) {
        Log.i(TAG, "changeEnabledCapabilities: " + request);
        for (CapabilityChangeRequest.CapabilityPair pair : request.getCapabilitiesToEnable()) {
            Log.i(TAG, "enabling capability " + pair.getCapability()
                    + " on radioTech " + pair.getRadioTech());
        }
        /* No error signalled — framework treats any non-errored
         * request as effective. There's no success callback in
         * the @SystemApi CapabilityCallbackProxy, only error. */
    }

    @Override
    public boolean queryCapabilityConfiguration(int capability, int radioTech) {
        return capability == MmTelCapabilities.CAPABILITY_TYPE_VOICE
                || capability == MmTelCapabilities.CAPABILITY_TYPE_SMS
                || capability == MmTelCapabilities.CAPABILITY_TYPE_VIDEO;
    }

    @Override
    public ImsSmsImplBase getSmsImplementation() {
        return mSmsImpl;
    }

    @Override
    public ImsCallProfile createCallProfile(int callSessionType, int callType) {
        Log.i(TAG, "createCallProfile type=" + callSessionType + " callType=" + callType);
        return new ImsCallProfile(callSessionType, callType);
    }

    private volatile HamelinPortsCallSession mActiveCall;
    /** MT counterpart to {@link #mActiveCall}. Single-call-at-a-time
     *  is enforced across MO/MT: if an MT arrives while MO is live,
     *  we reject it 486. */
    private volatile HamelinPortsIncomingCallSession mActiveIncoming;

    @Override
    public void onIncomingInvite(String callId, String fromUri,
                                 String remoteIp, int remotePort, int rtcpPort,
                                 int payloadType, int clockRate,
                                 String codecName, String fmtp) {
        Log.i(TAG, "MT onIncomingInvite callId=" + callId + " from=" + fromUri
                + " rtp=" + remoteIp + ":" + remotePort + " pt=" + payloadType
                + " codec=" + codecName + "/" + clockRate);
        /* Single-call guard. An MT while MO or a prior MT is live is
         * rare (dialer normally doesn't second-ring) but happens on
         * a second caller during active call; reject 486. */
        if (mActiveCall != null || mActiveIncoming != null) {
            Log.w(TAG, "MT reject: another session active — 486 Busy Here");
            HamelinPortsSipStack.rejectIncomingCall(callId, 486);
            return;
        }
        HamelinPortsIncomingCallSession.RemoteAudio remote =
                new HamelinPortsIncomingCallSession.RemoteAudio(
                        remoteIp, remotePort, rtcpPort,
                        payloadType, clockRate, codecName, fmtp);
        /* Profile: voice-only audio service. The framework passes
         * this to the in-call UI; video MT is a Phase C follow-up.
         *
         * Extras required by ImsPhoneConnection at ring time:
         *  - EXTRA_OI: tel user-part → Connection.mAddress (else null,
         *    some dialers / CallScreening filters drop anonymous MT
         *    before the UI).
         *  - EXTRA_OIR: number presentation; NOT_RESTRICTED is the
         *    default carrier semantic for a non-anonymous caller.
         *  - EXTRA_CALL_RAT_TYPE: tells metrics + video stack the
         *    inbound RAT; LTE for IMS-over-EUTRAN. */
        ImsCallProfile profile = new ImsCallProfile(
                ImsCallProfile.SERVICE_TYPE_NORMAL,
                ImsCallProfile.CALL_TYPE_VOICE);
        String telUser = extractTelUser(fromUri);
        if (telUser != null) {
            profile.setCallExtra(ImsCallProfile.EXTRA_OI, telUser);
        }
        profile.setCallExtraInt(ImsCallProfile.EXTRA_OIR,
                ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED);
        profile.setCallExtraInt(ImsCallProfile.EXTRA_CALL_RAT_TYPE,
                TelephonyManager.NETWORK_TYPE_LTE);
        HamelinPortsIncomingCallSession s = new HamelinPortsIncomingCallSession(
                mRegController, profile, callId, fromUri, remote,
                this::onIncomingSessionGone);
        mActiveIncoming = s;
        /* notifyIncomingCall is the AOSP hook that tells the
         * framework "there's a call — present it to the user". The
         * dialer starts ringing; when user taps answer, framework
         * calls s.accept(...). */
        try {
            notifyIncomingCall(s, callId, new Bundle());
            Log.i(TAG, "MT notifyIncomingCall fired callId=" + callId);
        } catch (Throwable t) {
            Log.e(TAG, "notifyIncomingCall failed", t);
            mActiveIncoming = null;
            HamelinPortsSipStack.rejectIncomingCall(callId, 500);
        }
    }

    /** Extract tel user-part from a SIP From URI like
     *  {@code sip:+491573...@10.233.181.41;user=phone}. Returns the
     *  raw E.164 string (e.g. {@code +491573...}) or null if unparseable. */
    private static String extractTelUser(String fromUri) {
        if (fromUri == null) return null;
        int colon = fromUri.indexOf(':');
        if (colon < 0) return fromUri;
        int at = fromUri.indexOf('@', colon + 1);
        String user = at > 0 ? fromUri.substring(colon + 1, at)
                              : fromUri.substring(colon + 1);
        int semi = user.indexOf(';');
        if (semi >= 0) user = user.substring(0, semi);
        return user.isEmpty() ? null : user;
    }

    void onIncomingSessionGone(HamelinPortsIncomingCallSession s) {
        if (mActiveIncoming == s) {
            mActiveIncoming = null;
        }
    }

    /** Fired by the native stack when a server-side dialog terminates
     *  before our {@link ImsCallSessionImplBase#accept} runs — remote
     *  CANCEL, BYE, network dialog-timeout. Framework doesn't learn
     *  about the termination via any existing path (the MO call-session
     *  listener isn't installed yet on MT), so the dialer keeps
     *  ringing until we route the terminate ourselves. */
    /** Fired by the native stack when the incoming SDP offer included
     *  an accepted m=video block. Delivered AFTER
     *  {@link #onIncomingInvite}; the session is already created and
     *  tracked in {@code mActiveIncoming}. We annotate it with the
     *  remote video params so a later {@link ImsCallSessionImplBase#accept}
     *  can build a matching SDP answer and open a video imsmedia
     *  session alongside audio. */
    @Override
    public void onIncomingInviteVideo(String callId, String remoteIp,
                                      int remotePort, int rtcpPort,
                                      int payloadType, int clockRate,
                                      String codecName, String fmtp) {
        Log.i(TAG, "MT onIncomingInviteVideo callId=" + callId
                + " rtp=" + remoteIp + ":" + remotePort
                + " pt=" + payloadType + " " + codecName + "/" + clockRate);
        HamelinPortsIncomingCallSession s = mActiveIncoming;
        if (s == null || !callId.equals(s.getCallId())) {
            Log.w(TAG, "MT video annex for unknown/stale callId — ignoring");
            return;
        }
        s.setRemoteVideo(new HamelinPortsIncomingCallSession.RemoteAudio(
                remoteIp, remotePort, rtcpPort,
                payloadType, clockRate, codecName, fmtp));
    }

    @Override
    public void onIncomingCancelled(String callId, int reason) {
        Log.i(TAG, "MT onIncomingCancelled callId=" + callId
                + " reason=" + reason);
        HamelinPortsIncomingCallSession s = mActiveIncoming;
        if (s == null || !callId.equals(s.getCallId())) {
            Log.w(TAG, "MT cancel for unknown/stale callId — ignoring");
            return;
        }
        s.handleRemoteCancel(reason);
    }

    @Override
    public ImsCallSessionImplBase createCallSession(ImsCallProfile profile) {
        Log.i(TAG, "createCallSession");
        /* Force-terminate any prior session that hasn't been cleaned up
         * so its reSIProcate InviteSession, RTP socket, and audio mode
         * don't leak into the new call. Native HamelinPortsInviteHandler
         * tracks only a single mActive handle; without this guard, two
         * rapid createCallSession calls produce two reSIProcate
         * ClientInviteSessions and delayed 200-OK / BYE events for the
         * older session arrive at the handler after mActive has been
         * clobbered — observed as spurious "onConnected but RTP socket
         * is closed" log lines and BYE retransmission storms from the
         * network. Proper multi-call support needs a handle map keyed
         * by Call-ID on the native side; until then, one call at a
         * time. */
        HamelinPortsCallSession prior = mActiveCall;
        if (prior != null) {
            Log.w(TAG, "createCallSession: tearing down prior session");
            try { prior.terminate(0); } catch (Exception ignored) {}
            mActiveCall = null;
        }
        HamelinPortsCallSession s = new HamelinPortsCallSession(mRegController, profile,
                this::onCallSessionGone);
        mActiveCall = s;
        return s;
    }

    /** Invoked by {@link HamelinPortsCallSession} on terminal events
     *  ({@code onTerminated} / {@code onFailure}) so we drop the
     *  reference promptly instead of waiting for the next
     *  {@link #createCallSession} to do garbage-collection. */
    void onCallSessionGone(HamelinPortsCallSession s) {
        if (mActiveCall == s) {
            mActiveCall = null;
        }
    }

    /** Called by {@link ImsRegistrationController} when the IMS
     *  dedicated bearer disappears mid-call — typically on LTE→2G
     *  handover where the EDGE domain doesn't carry QCI=5. Without
     *  this, the dialer stays in "ACTIVE" state while our
     *  reSIProcate sockets quietly abort: user hears silence for
     *  minutes, has to manually hang up, and the hangup then crashes
     *  because mActive references a destroyed dialog. Fire a
     *  {@code callSessionTerminated} with a reason Telecom can
     *  render ("Lost service") so the call UI closes cleanly and
     *  the standard disconnect tone plays. */
    void onImsPdnLost() {
        HamelinPortsCallSession mo = mActiveCall;
        HamelinPortsIncomingCallSession mt = mActiveIncoming;
        if (mo == null && mt == null) {
            Log.i(TAG, "onImsPdnLost: no active call");
            return;
        }
        Log.w(TAG, "onImsPdnLost: terminating active call(s)"
                + " mo=" + (mo != null) + " mt=" + (mt != null));
        if (mo != null) mo.handleImsPdnLost();
        if (mt != null) mt.handleImsPdnLost();
    }

    /** True iff there is an active MO or MT call session attached
     *  to this feature. Used by ImsRegistrationController on iface-swap
     *  detection: a mid-call WWAN↔WLAN handover takes a different code
     *  path (preserve dialog handles, drive a re-INVITE on the new
     *  transport) than an idle one (full SIP-stack tearDown + fresh
     *  trial). */
    boolean hasActiveCall() {
        return mActiveCall != null || mActiveIncoming != null;
    }

    /** Called by ImsRegistrationController after successful 200 OK. */
    void onRegistered() {
        MmTelCapabilities caps = new MmTelCapabilities();
        caps.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VOICE);
        caps.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_SMS);
        /* Advertise video so the dialer's "Video call" action is
         * enabled. The full Phase C path (C.1–C.5) handles the
         * resulting CALL_TYPE_VT profile end-to-end. */
        caps.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
        notifyCapabilitiesStatusChanged(caps);
        /* Bind MT listener now that we know this is the active
         * registered slot. The process-global listener setter is
         * last-writer-wins; whichever slot registers last owns MT
         * routing — which is correct because the active-SIM slot is
         * the only one that will ever register. */
        HamelinPortsSipStack.setIncomingCallListener(this);
        Log.i(TAG, "notified capabilities: VOICE + SMS; MT listener bound slot=" + mSlotId);
    }

    /** Called on deregistration. */
    void onDeregistered() {
        notifyCapabilitiesStatusChanged(new MmTelCapabilities());
        /* Only drop the listener if we are currently the bound one —
         * otherwise a race during slot handover could clear another
         * slot's active binding. */
        HamelinPortsSipStack.clearIncomingCallListenerIfSelf(this);
        Log.i(TAG, "notified capabilities: none; MT listener released slot=" + mSlotId);
    }

    /* ----- SRVCC Phase I.3 handlers -----
     *
     * Behaviour derived from 3GPP TS 23.216 / TS 24.237.
     *
     * STARTED:
     *   media: keep RTP/RTCP flowing — do NOT pause here, so FAILED
     *          can resume seamlessly.
     *   sip:   freeze dialog — no BYE / UPDATE / re-INVITE. Set
     *          CallSession.mSrvccStarted=true so any terminate()
     *          while HO is in-flight is suppressed.
     *   up:    no Telecom callback — call is still active on IMS.
     *          Schedule a 15 s defensive watchdog; if no terminal
     *          transition arrives by then, treat as FAILED locally
     *          so the call isn't permanently frozen.
     *
     * COMPLETED:
     *   media: tear down RTP/RTCP + ImsMedia session.
     *   sip:   silently dispose the reSIProcate InviteSession — NO
     *          BYE on the wire (SCC-AS has released the IMS leg; a
     *          UE-originated BYE would race session transfer and can
     *          drop the freshly-handed-over CS call). Framework
     *          re-parents the Connection to GsmCdmaCallTracker.
     *   up:    fire callSessionTerminated(CODE_LOCAL_HO_NOT_FEASIBLE)
     *          via srvccSilentTerminate() so our state machine
     *          reaches a sink.
     *
     * FAILED / CANCELED:
     *   media: no-op (media was never paused in STARTED).
     *   sip:   clear CallSession.mSrvccStarted. TS 24.237 §12.2.4.2
     *          prescribes a SIP UPDATE with Reason: cause=487 +
     *          specific reason text to re-synchronise the IMS-AS;
     *          not implemented yet — needs a new JNI bridge to
     *          reSIProcate's InviteSession::targetRefresh(). Tracked
     *          as a TODO; the IMS session still recovers via the
     *          reg-refresh + natural re-dial if this UPDATE is
     *          absent.
     *   up:    no callback — call still active on IMS.
     * ---- */

    private static final String SRVCC_TAG = "SRVCC";
    private static final long SRVCC_STARTED_WATCHDOG_MS = 15_000L;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile long mSrvccStartedNs = 0;
    private final Runnable mSrvccWatchdog = () -> {
        Log.w(SRVCC_TAG, "SRVCC_STARTED_TIMEOUT " + srvccDiagLine("TIMEOUT"));
        /* Treat as FAILED locally so the per-session flag clears and
         * a subsequent framework terminate() can send BYE normally.
         * If the modem eventually reports COMPLETED after this, the
         * COMPLETED handler still runs (idempotent cleanup) — we just
         * may double-log. */
        notifySrvccFailed();
    };

    /** Build a single key=value log line with the diagnostic fields
     *  an SRVCC post-mortem needs. Tagged at the caller's level; this
     *  only builds the suffix. */
    private String srvccDiagLine(String event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("event=").append(event);
        sb.append(" ts_ns=").append(System.nanoTime());
        sb.append(" slot=").append(mSlotId);
        long startedNs = mSrvccStartedNs;
        long deltaMs = (startedNs > 0)
                ? (System.nanoTime() - startedNs) / 1_000_000L : -1;
        sb.append(" ms_since_started=").append(deltaMs);
        HamelinPortsCallSession s = mActiveCall;
        if (s != null) {
            sb.append(' ').append(s.srvccDiagSnapshot());
        } else {
            sb.append(" active_call=none");
        }
        /* Registration / IPsec context from the property namespace set
         * by ims_xfrm. Useful to correlate SRVCC events against
         * P-CSCF IPsec lifetime. */
        sb.append(" pcscf=").append(SystemProperties.get("lineage.ims.xfrm.pcscf", ""));
        sb.append(" portC=").append(SystemProperties.get("lineage.ims.xfrm.ue_portc", ""));
        sb.append(" portS=").append(SystemProperties.get("lineage.ims.xfrm.ue_ports", ""));
        /* Radio context. Wrapped because TelephonyManager can throw on
         * stale sub-ids during teardown. */
        try {
            TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
            if (tm != null) {
                sb.append(" mcc_mnc=").append(tm.getNetworkOperator());
                sb.append(" rat=").append(tm.getDataNetworkType());
            }
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    /**
     * SRVCC entry-point from the telephony framework (not from our own
     * SrvccMonitor). Fires when the modem sends
     * {@code RIL_UNSOL_SRVCC_STATE_NOTIFY(STARTED)}; framework forwards
     * to us with a callback {@code consumer} that we MUST invoke
     * synchronously — the returned list is what the framework hands to
     * RIL via {@code IRadioIms#notifySrvccCall}, which the modem puts
     * into the NAS SRVCC Handover Request towards the MSC. Without
     * this list the modem has nothing to tell the MSC about which
     * IMS calls to transfer; the CS leg would be set up with no call
     * reference and the handover would fail at Sv. 3GPP TS 23.216 §7.2.
     * AOSP doc: "Service needs to call the provided callback
     * Consumer&lt;List&lt;SrvccCall&gt;&gt; with active call list for SRVCC."
     */
    @Override
    public void notifySrvccStarted(Consumer<List<SrvccCall>> consumer) {
        mSrvccStartedNs = System.nanoTime();
        HamelinPortsCallSession s = mActiveCall;
        if (s != null) s.setSrvccStarted(true);

        /* Build the SrvccCall list from the active session set. Today
         * we only track one call at a time (mActiveCall), so the list
         * is 0 or 1 element; multi-call SRVCC (mid-call + held) will
         * require extending this once we support held calls. */
        List<SrvccCall> list;
        if (s != null) {
            String callId = s.getSrvccCallId();
            int preciseState = s.getCallStartNs() > 0
                    ? PreciseCallState.PRECISE_CALL_STATE_ACTIVE
                    : PreciseCallState.PRECISE_CALL_STATE_DIALING;
            try {
                list = new ArrayList<>(1);
                list.add(new SrvccCall(callId, preciseState, s.getCallProfile()));
            } catch (Throwable t) {
                Log.w(SRVCC_TAG, "SrvccCall build failed", t);
                list = Collections.emptyList();
            }
        } else {
            list = Collections.emptyList();
        }

        Log.i(SRVCC_TAG, "SRVCC_HO_STARTED "
                + "srvcc_list_size=" + list.size() + " "
                + srvccDiagLine("STARTED"));

        /* Must fire synchronously even on empty list — not calling
         * consumer.accept hangs the framework which in turn hangs the
         * modem's handover decision (Agent A note). */
        try {
            consumer.accept(list);
        } catch (Throwable t) {
            Log.e(SRVCC_TAG, "consumer.accept failed", t);
        }

        /* Re-arm watchdog. removeCallbacks is a no-op on no-match, so
         * back-to-back STARTED events (retry after an internal failure
         * that didn't reach FAILED) simply restart the 15 s window. */
        mMainHandler.removeCallbacks(mSrvccWatchdog);
        mMainHandler.postDelayed(mSrvccWatchdog, SRVCC_STARTED_WATCHDOG_MS);
    }

    @Override
    public void notifySrvccCompleted() {
        mMainHandler.removeCallbacks(mSrvccWatchdog);
        Log.i(SRVCC_TAG, "SRVCC_HO_SUCCESS " + srvccDiagLine("COMPLETED"));
        HamelinPortsCallSession s = mActiveCall;
        if (s != null) {
            /* Clear the guard BEFORE tearing the session down so our
             * own cleanup path (which may invoke terminate()) doesn't
             * short-circuit on the SRVCC check. Silent terminate does
             * the work without BYE either way. */
            s.setSrvccStarted(false);
            s.srvccSilentTerminate();
            /* mActiveCall clears via the Consumer<HamelinPortsCallSession>
             * hook in createCallSession — srvccSilentTerminate
             * triggers cleanup → fireGoneOnce → onCallSessionGone. */
        }
        mSrvccStartedNs = 0;
    }

    @Override
    public void notifySrvccFailed() {
        mMainHandler.removeCallbacks(mSrvccWatchdog);
        Log.w(SRVCC_TAG, "SRVCC_HO_FAILED " + srvccDiagLine("FAILED"));
        HamelinPortsCallSession s = mActiveCall;
        if (s != null) s.setSrvccStarted(false);
        /* TODO Phase I.3.x: emit SIP UPDATE with
         *   Reason: SIP;cause=487;text="failure to transition to CS domain"
         * per TS 24.237 §12.2.4.2 to re-synchronise the IMS-AS. Needs
         * a new JNI entry into reSIProcate InviteSession::targetRefresh
         * / update. */
        mSrvccStartedNs = 0;
    }

    @Override
    public void notifySrvccCanceled() {
        mMainHandler.removeCallbacks(mSrvccWatchdog);
        Log.i(SRVCC_TAG, "SRVCC_HO_CANCELED " + srvccDiagLine("CANCELED"));
        HamelinPortsCallSession s = mActiveCall;
        if (s != null) s.setSrvccStarted(false);
        /* TODO Phase I.3.x: same as FAILED but with reason text
         * "handover cancelled". Deferred alongside the FAILED case. */
        mSrvccStartedNs = 0;
    }
}
