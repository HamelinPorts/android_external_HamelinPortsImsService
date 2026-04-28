package org.hamelinports.ims;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;

import org.hamelinports.ims.modem.IImsModemBridge;
import org.hamelinports.ims.modem.ImsModemBridgeFactory;
import org.hamelinports.ims.sip.HamelinPortsAkaProviderImpl;
import org.hamelinports.ims.sip.HamelinPortsSipStack;
import org.hamelinports.ims.sip.RegistrationListener;
import org.hamelinports.ims.sip.SipClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Drives the IMS REGISTER lifecycle. The actual SIP work runs in the
 * native reSIProcate stack via HamelinPortsSipStack — this class supplies
 * the AKA provider, the IPsec offer, and the framework callbacks.
 */
public class ImsRegistrationController {
    private static final String TAG = HamelinPortsImsService.TAG;
    private static final int CONNECT_TIMEOUT_MS = 10000;

    private final Context mContext;
    private final int mSlotId;
    private final ImsRegistrationImplBase mRegImpl = new ImsRegistrationImplBase();

    private ConnectivityManager mCm;
    private ConnectivityManager.NetworkCallback mNetCallback;
    private Network mImsNetwork;
    private List<InetAddress> mPcscfs;
    private volatile boolean mRunning;
    private volatile boolean mRegistered;
    /** Negotiated Expires from the last REGISTER 200 OK. Used to
     *  schedule explicit refreshes ahead of DUM's internal auto-refresh
     *  (observed to silently stop firing on Mavenir — see project
     *  memory). Value in seconds; {@code <= 0} means use fallback. */
    private volatile int mNegotiatedExpiresSec;
    private final RegistrationDiag mDiag = new RegistrationDiag();
    private final Handler mRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefreshRunnable = () -> {
        /* Fire the refresh regardless of current mRegistered state —
         * reSIProcate will bail cleanly if the handle isn't valid any
         * more. We still rearm below because the service should keep
         * trying periodically; a failed refresh followed by onFailure
         * will tear the trial down and start a fresh REGISTER. */
        boolean ok = HamelinPortsSipStack.refreshRegister();
        mDiag.onRefreshTick(ok);
        Log.i(TAG, "REGISTER refresh tick ok=" + ok
                + " negotiatedExpiresSec=" + mNegotiatedExpiresSec);
        scheduleNextRefresh();
    };

    /** Diagnostic state for {@code dumpsys activity service
     *  org.hamelinports.ims/.HamelinPortsImsService}. Persists across log-buffer
     *  rollover (logcat only keeps ~MB), so overnight soaks remain
     *  inspectable after the fact. All fields updated on the main
     *  thread; reads are lock-free via {@code volatile}. */
    private static final class RegistrationDiag {
        volatile long mRegisteredAtMs;     /* last onRegistered 200 OK wall-clock ms */
        volatile int  mRegisteredCount;
        volatile int  mLastNegotiatedExpires;
        volatile long mDeregisteredAtMs;
        volatile int  mDeregisteredCount;
        volatile int  mLastDeregCode;
        volatile String mLastDeregReason = "";
        volatile long mLastRefreshTickAtMs;
        volatile int  mRefreshTicksFired;
        volatile int  mRefreshTicksOk;         /* requestRefresh returned true */
        volatile int  mRefreshTicksFailed;     /* requestRefresh returned false */
        volatile long mLastScheduledAtMs;
        volatile int  mLastScheduledDelaySec;

        void onRegistered(int expires) {
            mRegisteredAtMs = System.currentTimeMillis();
            mRegisteredCount++;
            mLastNegotiatedExpires = expires;
        }
        void onDeregistered(int code, String reason) {
            mDeregisteredAtMs = System.currentTimeMillis();
            mDeregisteredCount++;
            mLastDeregCode = code;
            mLastDeregReason = reason == null ? "" : reason;
        }
        void onRefreshTick(boolean ok) {
            mLastRefreshTickAtMs = System.currentTimeMillis();
            mRefreshTicksFired++;
            if (ok) mRefreshTicksOk++;
            else mRefreshTicksFailed++;
        }
        void onRefreshScheduled(int delaySec) {
            mLastScheduledAtMs = System.currentTimeMillis();
            mLastScheduledDelaySec = delaySec;
        }
    }

    /** Called by {@link HamelinPortsImsService#dump} — prints the
     *  diagnostic block for this slot. */
    void dump(java.io.PrintWriter pw) {
        long now = System.currentTimeMillis();
        pw.println("  slot=" + mSlotId
                + " running=" + mRunning
                + " registered=" + mRegistered);
        pw.println("  negotiatedExpiresSec=" + mNegotiatedExpiresSec);
        pw.println("  pcscfs=" + mPcscfs);
        pw.println("  lastRegistered: "
                + fmtAgo(now, mDiag.mRegisteredAtMs)
                + " count=" + mDiag.mRegisteredCount
                + " negotiatedExpires=" + mDiag.mLastNegotiatedExpires);
        pw.println("  lastDeregistered: "
                + fmtAgo(now, mDiag.mDeregisteredAtMs)
                + " count=" + mDiag.mDeregisteredCount
                + " code=" + mDiag.mLastDeregCode
                + " reason=" + mDiag.mLastDeregReason);
        pw.println("  refresh ticks: fired=" + mDiag.mRefreshTicksFired
                + " ok=" + mDiag.mRefreshTicksOk
                + " failed=" + mDiag.mRefreshTicksFailed
                + " lastAt=" + fmtAgo(now, mDiag.mLastRefreshTickAtMs));
        pw.println("  5xx retry: count=" + mRegRetryCount
                + "/" + REG_MAX_RETRIES);
        pw.println("  nextRefresh: scheduledAt="
                + fmtAgo(now, mDiag.mLastScheduledAtMs)
                + " delaySec=" + mDiag.mLastScheduledDelaySec
                + " fireInSec=" + fireInSec(now));
    }

    private long fireInSec(long nowMs) {
        long at = mDiag.mLastScheduledAtMs;
        if (at == 0) return -1;
        long due = at + mDiag.mLastScheduledDelaySec * 1000L;
        return (due - nowMs) / 1000L;
    }

    private static String fmtAgo(long nowMs, long thenMs) {
        if (thenMs == 0) return "never";
        long deltaSec = (nowMs - thenMs) / 1000L;
        return thenMs + "ms (" + deltaSec + "s ago)";
    }
    /** One-shot trial latch. Once set, attemptRegistration is a no-op;
     *  a reboot (or a rebuild + reinstall) is required to try again.
     *  Avoids spamming the P-CSCF with retries while we iterate on
     *  header/flow bugs. Reset by the P-CSCF failover path. */
    private volatile boolean mTrialAttempted;
    /** Set after the native stack has been torn down post-trial so we
     *  don't re-trigger it from the link-state callback. Reset by the
     *  P-CSCF failover path. */
    private volatile boolean mTrialTornDown;
    /** Index into mPcscfs for the next REGISTER attempt. Bumped on
     *  REGISTER 408 Request Timeout and retried if more P-CSCFs are
     *  available on the PDN. */
    private int mPcscfAttempt = 0;

    /** Last underlying-iface name seen via onLinkPropertiesChanged.
     *  Tracked to detect WWAN↔WLAN handover on the IMS APN: AOSP
     *  preserves the Network handle across the transport swap so we
     *  never see onLost, but the LinkProperties interface flips
     *  (rmnet5 → ipsec31 on Wi-Fi Calling activation, and back). On
     *  flip we treat the PDN as fresh — tear the trial down and reset
     *  the one-shot latches so a new REGISTER cycle fires on the new
     *  local IP / IPsec SA pair. */
    private volatile String mLastBoundIface;

    /** 5xx REGISTER retry bookkeeping. Per RFC 3261 §21.5.4 a 503
     *  Service Unavailable response advertises a transient condition:
     *  the UAC SHOULD honour any {@code Retry-After} header and either
     *  retry against the same server after waiting or move to the next
     *  entry from the RFC 3263 §4.3 server-location result. 3GPP TS
     *  24.229 §4.2A bounds the wait at "at least" Retry-After seconds;
     *  §5.1.1.4 requires a failed reregistration to be followed by a
     *  new initial registration (i.e. a full REGISTER 1 → 401 →
     *  REGISTER 2 cycle), not another in-dialog refresh.
     *
     *  These fields gate the retry: counter is reset on successful
     *  REGISTER; cap at {@link #REG_MAX_RETRIES} prevents an infinite
     *  loop against a persistently unavailable server. */
    private int mRegRetryCount = 0;
    private static final int REG_MAX_RETRIES = 3;
    /** Default wait when Retry-After is absent (RFC 3261 §21.5.4
     *  permits treating such a 503 like a 500; a sensible short wait
     *  still yields better behaviour than immediate dereg). */
    private static final int REG_DEFAULT_RETRY_SEC = 30;
    private static final int REG_MIN_RETRY_SEC = 5;
    /** Sanity cap on server-provided Retry-After. Anything longer than
     *  this (24 h) is indistinguishable from "server is permanently
     *  down"; we fall through to the normal dereg + fresh trial path
     *  and let the next IMS PDN event drive recovery. Inside this cap
     *  we honour the network-requested wait verbatim per RFC 3261
     *  §21.5.4 SHOULD-wait-Retry-After — anything else risks the
     *  P-CSCF treating us as misbehaving and escalating. */
    private static final int REG_MAX_RETRY_SEC = 86400;
    /** Threshold above which a long Retry-After is logged as a warning
     *  for diagnostics. Framework state is NOT touched — IMS stays in
     *  REGISTERED for the duration; a 503+Retry-After does not destroy
     *  the binding (RFC 3261 §21.5 transient semantics), so MT calls
     *  during the wait may still land via the live binding. */
    private static final int REG_QUIET_RETRY_SEC = 300;
    /** Long-tail recovery cadence after a hard dereg (cap-exceeded
     *  Retry-After, retry-budget exhausted, non-5xx terminal error).
     *  Without this, an idle device on stable network never retries
     *  because the trial-latches stay closed until a network event
     *  resets them — none of which fire on idle. */
    private static final long REG_LONG_TAIL_RETRY_MS = 30 * 60 * 1000L;

    private HamelinPortsMmTelFeature mMmTelFeature;
    private HamelinPortsAkaProviderImpl mAkaProvider;

    /** First P-Associated-URI from REGISTER 200 OK; e.g.
     *  "sip:+491634605643@telefonica.de". The host part is the
     *  carrier's public-facing domain. */
    private volatile String mAssociatedUri;

    /** Proxies IMS registration state to the modem so the EPC can
     *  route MT voice as IMS rather than CSFB. The concrete bridge
     *  is per-device — created by {@link ImsModemBridgeFactory}; on
     *  devices without a vendor bridge this is a no-op stub. */
    private final IImsModemBridge mModemBridge;

    ImsRegistrationController(Context context, int slotId) {
        mContext = context;
        mSlotId = slotId;
        mCm = context.getSystemService(ConnectivityManager.class);
        mModemBridge = ImsModemBridgeFactory.create(context, slotId);
    }

    ImsRegistrationImplBase getRegistrationImpl() {
        return mRegImpl;
    }

    public void setMmTelFeature(HamelinPortsMmTelFeature feature) {
        mMmTelFeature = feature;
    }

    public boolean isRegistered() {
        return mRegistered;
    }

    public Context getContext() { return mContext; }
    public Network getImsNetwork() { return mImsNetwork; }
    /** Last LinkProperties iface name we observed on the IMS PDN.
     *  Used by call/SMS callers to pick the right P-Access-Network-Info
     *  flavour (3GPP-E-UTRAN-FDD on rmnet*, IEEE-802.11 on ipsec*). */
    public String getLastBoundIface() { return mLastBoundIface; }

    /** Map the bound underlying iface to the framework's REGISTRATION_TECH_*
     *  enum. AOSP's IpSecManager.createIpSecTunnelInterface produces
     *  interface names of the form {@code ipsecN}; cellular IMS PDNs are
     *  named {@code rmnetN}. Reported upstream so Telecom can render the
     *  correct call badge ("Wi-Fi calling" vs "VoLTE") and so framework
     *  consumers gating on REGISTRATION_TECH_IWLAN see truth. */
    private int currentRegistrationTech() {
        String iface = mLastBoundIface;
        if (iface != null && iface.startsWith("ipsec")) {
            return ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
        }
        return ImsRegistrationImplBase.REGISTRATION_TECH_LTE;
    }

    private int currentModemBridgeRat() {
        return currentRegistrationTech() == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
                ? IImsModemBridge.RAT_IWLAN
                : IImsModemBridge.RAT_LTE;
    }

    /** Carrier's public-facing SIP domain (host part of P-Associated-URI),
     *  e.g. "telefonica.de". Used by HamelinPortsCallSession when building
     *  the INVITE Request-URI's phone-context. Returns null until
     *  REGISTER has succeeded. */
    public String getPublicDomain() {
        if (mAssociatedUri == null) return null;
        int at = mAssociatedUri.lastIndexOf('@');
        return at > 0 ? mAssociatedUri.substring(at + 1) : null;
    }

    /** SIM country ISO, upper-cased for PhoneNumberUtils (e.g. "DE");
     *  null if unknown. Used by HamelinPortsCallSession.toNationalForm. */
    public String getCountryIso() {
        android.telephony.SubscriptionManager sm =
                mContext.getSystemService(android.telephony.SubscriptionManager.class);
        if (sm == null) return null;
        android.telephony.SubscriptionInfo info =
                sm.getActiveSubscriptionInfoForSimSlotIndex(mSlotId);
        if (info == null) return null;
        String iso = info.getCountryIso();
        return (iso == null || iso.isEmpty()) ? null : iso.toUpperCase();
    }

    void start() {
        if (mRunning) return;
        mRunning = true;
        Log.i(TAG, "start: requesting IMS PDN");

        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();

        mNetCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                Log.i(TAG, "IMS PDN onAvailable " + n);
            }
            @Override public void onLost(Network n) {
                Log.w(TAG, "IMS PDN onLost " + n);
                /* Mid-call PDN loss (typically LTE→2G handover):
                 * drop the active call locally before tearing the
                 * stack down. Without this the dialer stays ACTIVE
                 * and the user hears silence for minutes — SRVCC
                 * would normally bridge the call across, but the
                 * vendor RIL doesn't expose IRadioIms so SRVCC is
                 * unreachable on this device. See project memory
                 * srvcc_not_wired. */
                if (mMmTelFeature != null) {
                    try {
                        mMmTelFeature.onImsPdnLost();
                    } catch (Exception e) {
                        Log.w(TAG, "onImsPdnLost forwarding failed", e);
                    }
                }
                mImsNetwork = null;
                mPcscfs = null;
                tearDown();
                /* Reset the one-shot latches so the next
                 * onLinkPropertiesChanged (= fresh IMS PDN coming
                 * back, e.g. after the cell returns to LTE) triggers
                 * a new attemptRegistration. Without this we stay
                 * permanently deregistered from the framework's
                 * perspective and subsequent calls fall back to CS
                 * (ImsPhone.isVoiceOverCellularImsEnabled() reads
                 * false). */
                mTrialAttempted = false;
                mTrialTornDown = false;
            }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                List<InetAddress> pcscfs = lp.getPcscfServers();
                String iface = lp.getInterfaceName();
                Log.i(TAG, "IMS PDN props: iface=" + iface + " pcscfs=" + pcscfs);
                boolean ifaceSwapped = mLastBoundIface != null
                        && iface != null
                        && !iface.equals(mLastBoundIface);
                mImsNetwork = n;
                mPcscfs = pcscfs;
                if (ifaceSwapped) {
                    boolean callActive = mMmTelFeature != null
                            && mMmTelFeature.hasActiveCall();
                    Log.i(TAG, "IMS PDN iface swap " + mLastBoundIface
                            + " -> " + iface
                            + " (callActive=" + callActive + ")");
                    /* Latch the new iface NOW so a follow-up
                     * onLinkPropertiesChanged with the same iface
                     * (the framework consistently fires the callback
                     * twice in close succession) doesn't re-enter the
                     * swap branch with by-then-stale state. */
                    mLastBoundIface = iface;
                    if (callActive) {
                        /* Mid-call WWAN↔WLAN handover. tearDown() would
                         * call HamelinPortsSipStack.stop() which kills
                         * every reSIProcate Dialog, including the
                         * active call's. Without those handles, a
                         * follow-up re-INVITE has nothing to drive,
                         * so the call goes silent for the user
                         * (Telecom keeps it ACTIVE while RTP is dead
                         * on both sides). Preserve the SIP stack and
                         * just reset the trial latches so the new
                         * transport gets a fresh REGISTER. WF.10.C/D
                         * will land on top to migrate the dialog. */
                        mTrialAttempted = false;
                        mTrialTornDown = false;
                        mPcscfAttempt = 0;
                        mRegRetryCount = 0;
                    } else {
                        Log.i(TAG, "  idle swap — full tearDown + re-register");
                        tearDown();
                        mTrialAttempted = false;
                        mTrialTornDown = false;
                        mPcscfAttempt = 0;
                        mRegRetryCount = 0;
                    }
                }
                if (!pcscfs.isEmpty() && !mRegistered && !mTrialAttempted) {
                    mLastBoundIface = iface;
                    new Thread(() -> attemptRegistration(), "HamelinPortsIms-reg").start();
                }
            }
        };

        try {
            mCm.requestNetwork(req, mNetCallback);
        } catch (Exception e) {
            Log.e(TAG, "requestNetwork failed", e);
            mRunning = false;
        }
    }

    void stop() {
        mRunning = false;
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
        mRefreshHandler.removeCallbacks(mLongTailRetryRunnable);
        if (mNetCallback != null) {
            try { mCm.unregisterNetworkCallback(mNetCallback); } catch (Exception e) {}
            mNetCallback = null;
        }
        tearDown();
    }

    /** Schedule the next explicit REGISTER refresh. Fires at
     *  {@code Expires − 60 s} if we negotiated a value, otherwise
     *  every 15 minutes as a defensive default. Cancels any prior
     *  scheduled tick so back-to-back 200 OKs re-arm cleanly. */
    private void scheduleNextRefresh() {
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
        final int fallbackSec = 900;          /* 15 min */
        int delaySec;
        int expires = mNegotiatedExpiresSec;
        if (expires > 120) {
            delaySec = expires - 60;          /* 1 min before expiry */
        } else {
            delaySec = fallbackSec;
        }
        /* Testing override: `setprop persist.lineage.ims.refresh.sec N`
         * clamps the refresh interval to N seconds. Minimum 30 s to
         * avoid hammering the P-CSCF. Unset for production — we want
         * the natural Expires-minus-60 schedule. */
        int override = android.os.SystemProperties.getInt(
                "persist.lineage.ims.refresh.sec", 0);
        if (override >= 30 && override < delaySec) {
            delaySec = override;
            Log.i(TAG, "REGISTER refresh interval overridden to "
                    + delaySec + " s by property");
        }
        mRefreshHandler.postDelayed(mRefreshRunnable, delaySec * 1000L);
        mDiag.onRefreshScheduled(delaySec);
        Log.i(TAG, "REGISTER refresh scheduled in " + delaySec + " s"
                + " (expires=" + expires + ")");
    }

    private void tearDown() {
        /* HamelinPortsSipStack is a process-global singleton shared
         * between slot-0 and slot-1 controllers. If THIS slot never
         * actually drove the stack (e.g. slot 0 has no SIM, or the
         * slot's first attemptRegistration bailed at SIM-credential
         * read), tearDown must NOT call stop() — that would kill the
         * other slot's active session. The mAkaProvider is set in
         * attemptRegistration after creds are loaded and before the
         * first REGISTER queues, so it's a precise marker for "this
         * controller has acquired stack-global state". */
        boolean ownedStack = mAkaProvider != null;
        if (mRegistered) {
            mRegistered = false;
            mRegImpl.onDeregistered(new ImsReasonInfo(
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE, 0));
        }
        if (ownedStack) {
            try { HamelinPortsSipStack.stop(); } catch (Exception e) {}
        }
        if (mAkaProvider != null) {
            try { mAkaProvider.close(); } catch (Exception e) {}
            mAkaProvider = null;
        }
        try { mCm.bindProcessToNetwork(null); } catch (Exception e) {}
    }

    /** Long-tail recovery after a hard dereg. Resets the trial latches
     *  and re-attempts REGISTER from cold. Idempotent — if we're already
     *  registered or stopped, no-op. Re-armed on every hard-dereg event
     *  so we keep poking at the network until it accepts us back. */
    private final Runnable mLongTailRetryRunnable = () -> {
        if (!mRunning || mRegistered) return;
        Log.i(TAG, "long-tail retry: clearing trial latches and "
                + "re-attempting REGISTER from cold");
        mTrialAttempted = false;
        mTrialTornDown = false;
        mRegRetryCount = 0;
        new Thread(() -> attemptRegistration(),
                "HamelinPortsIms-longtail").start();
    };

    /** Post-trial tear-down. Kills the native stack a couple of seconds
     *  after the terminal outcome so DUM's refresh timer and keepalive
     *  can't keep hitting the network. Idempotent. */
    private void scheduleTrialTearDown(String why) {
        if (mTrialTornDown) return;
        mTrialTornDown = true;
        boolean ownedStack = mAkaProvider != null;
        new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            Log.i(TAG, "one-shot trial tear-down: " + why);
            if (ownedStack) {
                try { HamelinPortsSipStack.stop(); } catch (Exception e) {}
            }
            if (mAkaProvider != null) {
                try { mAkaProvider.close(); } catch (Exception e) {}
                mAkaProvider = null;
            }
            try { mCm.bindProcessToNetwork(null); } catch (Exception e) {}
            /* Schedule long-tail recovery. On stable idle network the
             * trial latches are otherwise only reset by PDN onLost or
             * iface swap — neither of which fire on a charging device.
             * Without this, post-failure means dead-until-reboot. */
            mRefreshHandler.removeCallbacks(mLongTailRetryRunnable);
            mRefreshHandler.postDelayed(mLongTailRetryRunnable,
                    REG_LONG_TAIL_RETRY_MS);
            Log.i(TAG, "long-tail recovery armed at +"
                    + (REG_LONG_TAIL_RETRY_MS / 1000) + " s");
        }, "HamelinPortsIms-teardown").start();
    }

    private void attemptRegistration() {
        Network net = mImsNetwork;
        List<InetAddress> pcscfs = mPcscfs;
        if (net == null || pcscfs == null || pcscfs.isEmpty()) return;
        if (mRegistered) return;
        if (mTrialAttempted) {
            Log.i(TAG, "attemptRegistration: one-shot latch closed — skipping");
            return;
        }
        /* Do NOT latch here — early-boot attempts can fail before we reach
         * the actual REGISTER queue (SIM still loading, SELinux enforcing,
         * network not attached). Latch only after we've successfully handed
         * REGISTER 1 to the native stack. */

        SipClient.Credentials creds = SipClient.readSimCredentials(mContext, mSlotId);
        if (creds == null) { Log.e(TAG, "SIM unavailable"); return; }

        int pcscfIdx = Math.min(mPcscfAttempt, pcscfs.size() - 1);
        InetAddress pcscf = pcscfs.get(pcscfIdx);
        Log.i(TAG, "Attempting registration via reSIProcate to " + pcscf
                + " (#" + (pcscfIdx + 1) + "/" + pcscfs.size() + ")");
        SipClient.UeSecurityParams ueSec = new SipClient.UeSecurityParams();

        // Probe for the local IP that ConnectivityManager will use for this
        // PDN. Open a transient socket to the P-CSCF; we don't need to keep
        // it once we know the bound source address.
        String localIp;
        InetAddress localAddr;
        try (Socket probe = new Socket()) {
            net.bindSocket(probe);
            probe.connect(new InetSocketAddress(pcscf, 5060), CONNECT_TIMEOUT_MS);
            localIp = stripScope(probe.getLocalAddress().getHostAddress());
            localAddr = probe.getLocalAddress();
        } catch (Exception e) {
            Log.e(TAG, "couldn't determine local IP via P-CSCF probe", e);
            return;
        }
        Log.i(TAG, "local IMS PDN address = " + localIp);

        // UE's IPsec offer (TS 33.203 §7.2.1). Both algorithms must be
        // listed as a comma-separated list — DT's P-CSCF rejects with
        // 403 "No matched Security Item" if hmac-md5-96 isn't in the
        // offer, even when it picks sha-1-96.
        String secCommon =
                "ipsec-3gpp;prot=esp;mod=trans"
                + ";spi-c=" + ueSec.spiC
                + ";spi-s=" + ueSec.spiS
                + ";port-c=" + ueSec.portC
                + ";port-s=" + ueSec.portS;
        String securityClient =
                secCommon + ";alg=hmac-md5-96;ealg=null, "
                + secCommon + ";alg=hmac-sha-1-96;ealg=null";

        String impi = creds.imsi + "@" + creds.domain;
        String impu = "sip:" + impi;

        // Pin all sockets created by this process to the IMS PDN.
        // reSIProcate creates its TCP sockets internally and won't see
        // the per-socket Network.bindSocket() we use for the probe — so
        // without this the kernel routes via the default WiFi/cellular
        // interface and the source IP doesn't match our bound transport.
        try {
            mCm.bindProcessToNetwork(net);
        } catch (Exception e) {
            Log.e(TAG, "bindProcessToNetwork failed", e);
            return;
        }

        // Bring the native stack up. Order matters: start the stack →
        // bind transports → register the AKA provider (so the auth
        // round can fire) and the registration listener (so we hear
        // back about success).
        if (!HamelinPortsSipStack.start()) {
            Log.e(TAG, "HamelinPortsSipStack.start failed");
            return;
        }
        if (!HamelinPortsSipStack.addSipTransports(localIp, ueSec.portC, ueSec.portS)) {
            Log.e(TAG, "addSipTransports failed");
            tearDown();
            return;
        }
        mAkaProvider = new HamelinPortsAkaProviderImpl(
                mContext, ueSec, pcscf, localAddr, localIp);
        HamelinPortsSipStack.setAkaProvider(mAkaProvider);
        HamelinPortsSipStack.setRegistrationListener(new RegistrationListener() {
            @Override public void onExpiresReported(int expires) {
                mNegotiatedExpiresSec = expires;
                Log.i(TAG, "REGISTER Expires negotiated: " + expires + " s");
            }
            @Override public void onRegistered(String associatedUri) {
                Log.i(TAG, "*** IMS REGISTERED (native) *** associatedUri=" + associatedUri);
                mAssociatedUri = (associatedUri != null && !associatedUri.isEmpty())
                        ? associatedUri : null;
                mRegistered = true;
                /* Healthy REGISTER — reset the 5xx retry budget so a
                 * future transient failure gets its full allowance. */
                mRegRetryCount = 0;
                /* Cancel any pending long-tail recovery — we're back. */
                mRefreshHandler.removeCallbacks(mLongTailRetryRunnable);
                mDiag.onRegistered(mNegotiatedExpiresSec);
                mRegImpl.onRegistered(currentRegistrationTech());
                if (mMmTelFeature != null) mMmTelFeature.onRegistered();
                /* Tell the modem about our IMS state via the bridge.
                 * On devices whose AOSP IRadioIms surface alone is
                 * insufficient, the bridge implementation will
                 * relay this over its proprietary path so the EPC
                 * stops routing MT voice as CSFB. */
                try {
                    mModemBridge.sendRegistration(
                            mSlotId,
                            /*registered=*/ true,
                            currentModemBridgeRat(),
                            /*volte=*/ true,
                            /*smsIp=*/ true,
                            /*video=*/ false,
                            mAssociatedUri);
                    /* Sibling preference notification — without this
                     * the modem may default to "CS-preferred" voice
                     * domain policy and route MT via CSFB even after
                     * IMS is up. Must be sent alongside the
                     * registration notification. Causes the next NAS
                     * TAU to carry VoPS=1 (3GPP TS 24.301 §9.9.3.30). */
                    mModemBridge.sendPreference(
                            mSlotId,
                            /*volte=*/ true,
                            /*video=*/ false,
                            /*smsOverIms=*/ true);
                } catch (android.os.RemoteException e) {
                    Log.w(TAG, "modem-bridge sendRegistration/Preference failed", e);
                }
                /* Arm an explicit refresh timer. DUM's internal timer
                 * is supposed to re-REGISTER at T−50% of Expires but
                 * has been observed to silently stop firing; running
                 * our own watchdog on top keeps Mavenir's TAS binding
                 * fresh so MT voice keeps arriving as IMS instead of
                 * falling back to CSFB. */
                scheduleNextRefresh();
            }
            @Override public void onDeregistered(int statusCode, String reason,
                                                 int retryAfterSec) {
                Log.w(TAG, "REGISTER failed/removed: " + statusCode + " " + reason
                        + " retryAfter=" + retryAfterSec);
                mDiag.onDeregistered(statusCode, reason);
                mRefreshHandler.removeCallbacks(mRefreshRunnable);

                /* Retriable 5xx on an active registration. RFC 3261
                 * §21.5.4: 503 is a transient condition for which the
                 * UAC should retry, honouring any Retry-After header
                 * (RFC 3261 §20.33). 3GPP TS 24.229 §4.2A requires
                 * waiting at least Retry-After seconds; §5.1.1.4 says
                 * a failed reregistration is followed by a new
                 * initial registration (full REGISTER 1 → 401 →
                 * REGISTER 2 cycle with a fresh AKA challenge), not a
                 * same-dialog refresh.
                 *
                 * The framework is intentionally NOT told IMS is down
                 * during the retry window: keeping MmTel in the
                 * REGISTERED state avoids flipping MT routing policy
                 * to CSFB over what the spec classifies as a
                 * temporary server state. If the retry budget is
                 * exhausted, fall through to the normal dereg path
                 * below. */
                if (statusCode >= 500 && statusCode <= 599
                        && mRegistered
                        && mRegRetryCount < REG_MAX_RETRIES) {
                    int waitSec = retryAfterSec > 0
                            ? retryAfterSec : REG_DEFAULT_RETRY_SEC;
                    if (waitSec < REG_MIN_RETRY_SEC) waitSec = REG_MIN_RETRY_SEC;
                    if (waitSec > REG_MAX_RETRY_SEC) {
                        /* Pathologically long Retry-After (>24 h) —
                         * treat as "server is gone", fall through to
                         * the normal dereg + trial-tear-down path. */
                        Log.w(TAG, "Retry-After=" + retryAfterSec
                                + " s exceeds sanity cap "
                                + REG_MAX_RETRY_SEC + " — treating as hard dereg");
                    } else {
                        if (waitSec > REG_QUIET_RETRY_SEC) {
                            /* Long but reasonable wait. Framework
                             * stays in REGISTERED — Mavenir's 503
                             * does not destroy the binding, MT may
                             * still land. Diagnostic warning only. */
                            Log.w(TAG, "Retry-After=" + waitSec
                                    + " s exceeds quiet cap "
                                    + REG_QUIET_RETRY_SEC
                                    + " — long wait, framework left"
                                    + " in REGISTERED while we honour"
                                    + " the network's hold-off");
                        }
                        mRegRetryCount++;
                        final int delaySec = waitSec;
                        Log.i(TAG, "5xx on active registration — retry "
                                + mRegRetryCount + "/" + REG_MAX_RETRIES
                                + " in " + delaySec + " s");
                        mAssociatedUri = null;
                        /* RFC 3261 §21.5.4 + RFC 3263 §4.3: on 503 the
                         * UAC MAY stick with the same server after
                         * waiting. Keep the current P-CSCF
                         * (mPcscfAttempt unchanged) — if it's genuinely
                         * down, the retry will time out and the 408
                         * failover path below will move on. */
                        mRefreshHandler.postDelayed(() -> {
                            if (!mRunning) return;
                            Log.i(TAG, "5xx retry firing — fresh REGISTER cycle");
                            try { HamelinPortsSipStack.stop(); } catch (Exception e) {}
                            if (mAkaProvider != null) {
                                try { mAkaProvider.close(); } catch (Exception e) {}
                                mAkaProvider = null;
                            }
                            mRegistered = false;
                            mTrialAttempted = false;
                            mTrialTornDown = false;
                            new Thread(() -> attemptRegistration(),
                                    "HamelinPortsIms-5xx-retry").start();
                        }, delaySec * 1000L);
                        return;
                    }
                }

                mAssociatedUri = null;
                if (mRegistered) {
                    mRegistered = false;
                    mRegImpl.onDeregistered(new ImsReasonInfo(
                            ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE,
                            statusCode));
                    try {
                        mModemBridge.sendRegistration(
                                mSlotId,
                                /*registered=*/ false,
                                currentModemBridgeRat(),
                                /*volte=*/ false,
                                /*smsIp=*/ false,
                                /*video=*/ false,
                                /*impuUri=*/ null);
                    } catch (android.os.RemoteException e) {
                        Log.w(TAG, "modem-bridge dereg notify failed", e);
                    }
                }

                /* P-CSCF TCP flow went away (reSIProcate's
                 * ClientRegistrationHandler::onFlowTerminated) — not a
                 * protocol-level reject, just the transport died. Tear
                 * the native stack down, reset the one-shot latches,
                 * and start a fresh REGISTER cycle. Without this,
                 * subsequent MO INVITE / MESSAGE would try to send on
                 * the dead flow, get 408, and stay in limbo. */
                if ("flow-terminated".equals(reason)) {
                    Log.i(TAG, "flow terminated — rearming REGISTER");
                    new Thread(() -> {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        try { HamelinPortsSipStack.stop(); } catch (Exception e) {}
                        if (mAkaProvider != null) {
                            try { mAkaProvider.close(); } catch (Exception e) {}
                            mAkaProvider = null;
                        }
                        /* Restart against the SAME P-CSCF we had (no
                         * failover — this isn't a P-CSCF health issue,
                         * just a stale connection). mPcscfAttempt stays
                         * where it is. */
                        mTrialAttempted = false;
                        mTrialTornDown = false;
                        attemptRegistration();
                    }, "HamelinPortsIms-reconnect").start();
                    return;
                }
                /* P-CSCF failover on timeout. 408 means the P-CSCF we
                 * picked didn't respond to REGISTER 2 (IPsec side flaky,
                 * HSS-slow, or the P-CSCF is down). The PDN carries up
                 * to three P-CSCFs (TS 23.228 §5.1.1); try the next one
                 * before giving up. Other status codes (401/403/404 etc.)
                 * are protocol-level rejections that would repeat on
                 * any P-CSCF in the same pool — go straight to tear-down. */
                List<InetAddress> pcscfs = mPcscfs;
                if (statusCode == 408 && pcscfs != null
                        && mPcscfAttempt + 1 < pcscfs.size()) {
                    final int next = mPcscfAttempt + 1;
                    Log.i(TAG, "P-CSCF failover after 408: trying "
                            + (next + 1) + "/" + pcscfs.size());
                    /* Tear down the current native stack then kick a
                     * fresh attemptRegistration against the next P-CSCF.
                     * Reset the one-shot latch and tear-down guard so
                     * the retry isn't a no-op. Sleep 2 s to let the
                     * existing SIP transactions drain. */
                    new Thread(() -> {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        try { HamelinPortsSipStack.stop(); } catch (Exception e) {}
                        if (mAkaProvider != null) {
                            try { mAkaProvider.close(); } catch (Exception e) {}
                            mAkaProvider = null;
                        }
                        mPcscfAttempt = next;
                        mTrialAttempted = false;
                        mTrialTornDown = false;
                        attemptRegistration();
                    }, "HamelinPortsIms-failover").start();
                } else {
                    scheduleTrialTearDown("post-failure");
                }
            }
        });

        String pcscfHost = stripScope(pcscf.getHostAddress());
        /* REGISTER Expires we *request*. Production: 3600 (1 h). Override
         * with `setprop persist.lineage.ims.register.expires.sec N` to
         * shorten the cycle for testing the refresh watchdog without
         * waiting an hour. Mavenir may return 423 Interval Too Brief
         * with Min-Expires if too short — reSIProcate auto-retries
         * with the network-mandated minimum. The negotiated value comes
         * back via {@code RegistrationListener.onExpiresReported}. */
        int expiresSec = android.os.SystemProperties.getInt(
                "persist.lineage.ims.register.expires.sec", 3600);
        if (expiresSec < 60) expiresSec = 60;        /* sanity floor */
        Log.i(TAG, "REGISTER request Expires=" + expiresSec + " s");
        boolean queued = HamelinPortsSipStack.startRegister(
                impi, impu, creds.domain,
                expiresSec, creds.imeiUrn,
                pcscfHost, 5060, securityClient);
        Log.i(TAG, "REGISTER 1 queued=" + queued);
        if (queued) {
            /* Latch only after REGISTER 1 is actually queued for sending.
             * A failed start/addTransports/queue is not a consumed trial. */
            mTrialAttempted = true;
        }
    }

    private static String stripScope(String ip) {
        int i = ip.indexOf('%');
        return i > 0 ? ip.substring(0, i) : ip;
    }
}
