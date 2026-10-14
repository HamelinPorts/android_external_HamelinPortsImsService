package org.hamelinports.ims;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;

import org.hamelinports.ims.modem.IImsModemBridge;
import org.hamelinports.ims.modem.ImsModemBridgeFactory;
import org.hamelinports.ims.net.ProcessNetworkOwner;
import org.hamelinports.ims.reg.DeferredRequests;
import org.hamelinports.ims.reg.OutgoingGate;
import org.hamelinports.ims.reg.RefreshPolicy;
import org.hamelinports.ims.reg.RegistrationSnapshot;
import org.hamelinports.ims.reg.RegistrationState;
import org.hamelinports.ims.reg.SlotActivation;
import org.hamelinports.ims.sip.HamelinPortsAkaProviderImpl;
import org.hamelinports.ims.sip.HamelinPortsSipStack;
import org.hamelinports.ims.sip.RegistrationListener;
import org.hamelinports.ims.sip.SipClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives the IMS REGISTER lifecycle of one SIM slot. The SIP work runs in
 * the native reSIProcate stack via HamelinPortsSipStack; this class supplies
 * the AKA provider, the IPsec offer and the framework callbacks.
 *
 * <p>Threading: one HandlerThread per slot owns all registration state.
 * Network and telephony callbacks, broadcasts, alarms and native upcalls are
 * posted onto it. Blocking work (probe connect, stack stop) runs there too,
 * never on the native DUM thread, because stopping the stack joins that thread.
 *
 * <p>Timing uses {@link SystemClock#elapsedRealtime()}, which keeps counting
 * in suspend; the network's registration expiry does too.
 */
public class ImsRegistrationController {
    private static final String TAG = HamelinPortsImsService.TAG;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final String CHECK_ALARM_TAG = "HamelinPortsIms:reg-check";
    private static final String WAKELOCK_TAG = "HamelinPortsIms:reg";
    private static final String DEFERRAL_ALARM_TAG = "HamelinPortsIms:mo-deferral";
    private static final long DEFERRAL_ALARM_WINDOW_MS = 1000;
    /** Lets the in-flight SIP transactions drain before the stack is stopped. */
    private static final long STACK_DRAIN_MS = 2000;
    /** Watchdog: IMS APN non-CONNECTED on every transport for this long forces a teardown. */
    private static final long IMS_PDN_WATCHDOG_MS = 15_000L;
    private static final int DEFAULT_REQUESTED_EXPIRES_SEC = 3600;
    private static final int MIN_REQUESTED_EXPIRES_SEC = 60;

    private final Context mContext;
    private final int mSlotId;
    private final ImsRegistrationImplBase mRegImpl = new ImsRegistrationImplBase();
    private final HamelinPortsSipStack mStack;
    private final ConnectivityManager mCm;
    /** Shared by all slots: only the slot that bound the process may clear the binding. */
    private final ProcessNetworkOwner<Network> mProcessNetwork;
    private final AlarmManager mAlarmManager;
    private final PowerManager mPowerManager;
    private final PowerManager.WakeLock mWakeLock;
    private final Handler mHandler;
    /** Token for delayed registration work that a teardown must cancel. */
    private final Object mDelayedRegWork = new Object();
    /** Proxies IMS registration state to the modem so the EPC routes MT voice as IMS rather than CSFB. */
    private final IImsModemBridge mModemBridge;

    /* State owned by the registration thread. */
    private final RegistrationState mState = new RegistrationState();
    private ConnectivityManager.NetworkCallback mNetCallback;
    private android.telephony.TelephonyCallback mTelCallback;
    private boolean mWakeSignalsRegistered;
    private HamelinPortsAkaProviderImpl mAkaProvider;
    /** What the framework was last told; it stays REGISTERED during a quick renewal. */
    private boolean mFrameworkRegistered;
    private int mRequestedExpiresSec = DEFAULT_REQUESTED_EXPIRES_SEC;
    private int mReportedExpiresSec;
    /** Index into mPcscfs for the next REGISTER; bumped on a 408. */
    private int mPcscfAttempt;
    private long mWakeLockSinceMs;
    private int mLastWwanImsState = android.telephony.TelephonyManager.DATA_DISCONNECTED;
    private int mLastWlanImsState = android.telephony.TelephonyManager.DATA_DISCONNECTED;
    private boolean mImsPdnWatchdogArmed;
    /** The framework wants IMS on this slot (feature ready / enableImsForSubscription). */
    private boolean mImsEnabled;
    /** Subscription the machine runs for, or NO_SUBSCRIPTION while stopped. */
    private int mRunningSubId = SlotActivation.NO_SUBSCRIPTION;
    private boolean mIdleLogged;
    /** The last attempt found the SIM not ready; SIM_STATE_LOADED registers at once. */
    private boolean mWaitingForSim;

    /* Published by the registration thread for callers on other threads. */
    private volatile Network mImsNetwork;
    private volatile List<InetAddress> mPcscfs;
    /** Underlying iface of the IMS PDN; flips rmnetN <-> ipsecN on a WWAN <-> WLAN handover. */
    private volatile String mLastBoundIface;
    /** First P-Associated-URI from the last 200 OK; its host is the carrier's public domain. */
    private volatile String mAssociatedUri;
    private volatile HamelinPortsMmTelFeature mMmTelFeature;
    /** Published as one object: readers never pair values of two states. */
    private volatile RegistrationSnapshot mSnapshot = RegistrationSnapshot.NONE;
    private volatile String mStateSummary = "";
    /** MO lapse checks posted but not yet run; MO requests wait for them. */
    private final AtomicInteger mPendingOutgoingChecks = new AtomicInteger();
    private final DeferredRequests mDeferred = new DeferredRequests();
    private final RegistrationDiag mDiag = new RegistrationDiag();

    private final AlarmManager.OnAlarmListener mCheckAlarm =
            () -> checkRegistration("ALARM");
    /** Deadline the deferral alarm is armed for, or -1; registration thread only. */
    private long mDeferralAlarmAtMs = -1;
    private final AlarmManager.OnAlarmListener mDeferralAlarm = () -> {
        mDeferralAlarmAtMs = -1;   /* fired: re-arm for whatever is still queued */
        publishState();
    };

    private final BroadcastReceiver mWakeSignalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                checkRegistration("SCREEN_ON");
            } else if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action)) {
                if (!mPowerManager.isDeviceIdleMode()) checkRegistration("DOZE_EXIT");
            } else if (PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED.equals(action)) {
                if (!mPowerManager.isDeviceLightIdleMode()) checkRegistration("LIGHT_DOZE_EXIT");
            }
        }
    };

    /** SIM records loaded on this slot, e.g. after the PIN was entered. */
    private final BroadcastReceiver mSimStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int slot = intent.getIntExtra(SubscriptionManager.EXTRA_SLOT_INDEX,
                    SubscriptionManager.INVALID_SIM_SLOT_INDEX);
            int state = intent.getIntExtra(TelephonyManager.EXTRA_SIM_STATE,
                    TelephonyManager.SIM_STATE_UNKNOWN);
            if (slot == mSlotId && state == TelephonyManager.SIM_STATE_LOADED) {
                onSimLoaded();
            }
        }
    };

    /** SIM inserted, removed or swapped; the framework does not re-create the feature for it. */
    private final SubscriptionManager.OnSubscriptionsChangedListener mSubscriptionsListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override
                public void onSubscriptionsChanged() {
                    evaluateActivation("SUBSCRIPTIONS_CHANGED");
                }
            };

    /** Wall-clock history for dumpsys; it survives logcat rollover on overnight runs. */
    private static final class RegistrationDiag {
        volatile long mRegisteredAtMs;
        volatile int mRegisteredCount;
        volatile int mLastNegotiatedExpires;
        volatile long mDeregisteredAtMs;
        volatile int mDeregisteredCount;
        volatile int mLastDeregCode;
        volatile String mLastDeregReason = "";
        volatile long mLastRefreshAtMs;
        volatile int mRefreshesSent;
        volatile int mRefreshesNotSent;
        volatile int mFreshAttempts;
        volatile long mNextCheckElapsedMs = -1;

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

        void onRefresh(boolean sent) {
            mLastRefreshAtMs = System.currentTimeMillis();
            if (sent) mRefreshesSent++;
            else mRefreshesNotSent++;
        }
    }

    ImsRegistrationController(Context context, int slotId,
                              ProcessNetworkOwner<Network> processNetwork) {
        mContext = context;
        mSlotId = slotId;
        mProcessNetwork = processNetwork;
        mCm = context.getSystemService(ConnectivityManager.class);
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
        /* Every acquire carries its own timeout; a counted lock could leak. */
        mWakeLock.setReferenceCounted(false);
        mModemBridge = ImsModemBridgeFactory.create(context, slotId);
        mStack = new HamelinPortsSipStack(slotId);
        HandlerThread thread = new HandlerThread("ims-reg-" + slotId);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
        if (sm != null) {
            sm.addOnSubscriptionsChangedListener(mHandler::post, mSubscriptionsListener);
        }
    }

    /** Per-slot SIP stack accessor for downstream call/SMS/MmTel code. */
    public HamelinPortsSipStack stack() { return mStack; }

    ImsRegistrationImplBase getRegistrationImpl() {
        return mRegImpl;
    }

    public void setMmTelFeature(HamelinPortsMmTelFeature feature) {
        mMmTelFeature = feature;
    }

    /**
     * True while the binding can carry a normal MO request: registered, not
     * about to lapse, and no fresh registration due (that would stop the
     * stack under the request).
     */
    public boolean isRegistered() {
        return mSnapshot.isUsable(SystemClock.elapsedRealtime());
    }

    /**
     * True while the binding itself is valid. For an emergency call, which
     * holds the stack from its creation, so a due fresh registration waits.
     */
    public boolean isBindingValid() {
        return mSnapshot.isBindingValid(SystemClock.elapsedRealtime());
    }

    /** True while a fresh registration runs or is due, so MO requests should wait for it. */
    public boolean isRetryInFlight() {
        return mSnapshot.isRenewalPending(SystemClock.elapsedRealtime())
                || mPendingOutgoingChecks.get() > 0;
    }

    /**
     * Holds an MO request until no renewal is pending ({@code onReady}) or
     * for at most {@link OutgoingGate#MAX_DEFERRAL_MS} ({@code onExpired}).
     * Both run on the registration thread; {@code onReady} should re-check
     * {@link #isRegistered()}. Cancel the entry when the request goes away.
     */
    public DeferredRequests.Entry deferUntilRegistered(Runnable onReady, Runnable onExpired) {
        DeferredRequests.Entry entry =
                mDeferred.add(onReady, onExpired, SystemClock.elapsedRealtime());
        /* Re-evaluate on the owning thread: an outcome that landed just before
         * the add releases this request, else the deadline alarm is armed. */
        mHandler.post(this::publishState);
        return entry;
    }

    /**
     * Re-checks the registration age before an MO call or SMS. If the binding
     * has lapsed, MO callers see {@link #isRetryInFlight()} until the
     * registration thread has started the fresh registration.
     */
    public void checkRegistrationBeforeOutgoing() {
        long until = mSnapshot.usableUntilMs();
        boolean lapsed = until > 0 && SystemClock.elapsedRealtime() >= until;
        if (lapsed) mPendingOutgoingChecks.incrementAndGet();
        mHandler.post(() -> {
            checkRegistration("MO");
            if (lapsed) mPendingOutgoingChecks.decrementAndGet();
            publishState();
        });
    }

    public Context getContext() { return mContext; }
    public Network getImsNetwork() { return mImsNetwork; }
    /** Last iface seen on the IMS PDN; picks the P-Access-Network-Info flavour (rmnet* vs ipsec*). */
    public String getLastBoundIface() { return mLastBoundIface; }

    /** REGISTRATION_TECH_* for the bound iface: ipsecN is the IWLAN tunnel, rmnetN cellular. */
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

    /** Host part of the P-Associated-URI (e.g. "telefonica.de"), or null before the first 200 OK. */
    public String getPublicDomain() {
        String uri = mAssociatedUri;
        if (uri == null) return null;
        int at = uri.lastIndexOf('@');
        return at > 0 ? uri.substring(at + 1) : null;
    }

    /** SIM country ISO, upper-cased for PhoneNumberUtils (e.g. "DE"); null if unknown. */
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

    /**
     * The framework enabled or disabled IMS on this slot. The machine runs
     * only while enabled and while the slot has an active subscription.
     */
    void setImsEnabled(boolean enabled, String reason) {
        mHandler.post(() -> {
            mImsEnabled = enabled;
            evaluateActivation(reason);
        });
    }

    /** Starts, stops or restarts the machine for the slot's current subscription. */
    private void evaluateActivation(String reason) {
        int activeSubId = SipClient.activeSubscriptionId(mContext, mSlotId);
        SlotActivation.Action action =
                SlotActivation.decide(mImsEnabled, activeSubId, mRunningSubId);
        switch (action) {
            case START:
                startOnRegThread(activeSubId, reason);
                break;
            case RESTART:
                Log.i(TAG, "slot=" + mSlotId + " subscription changed subId=" + mRunningSubId
                        + " -> " + activeSubId + " — restarting registration");
                stopOnRegThread();
                startOnRegThread(activeSubId, reason);
                break;
            case STOP:
                Log.i(TAG, "slot=" + mSlotId + " stop reason=" + reason
                        + " imsEnabled=" + mImsEnabled);
                stopOnRegThread();
                if (mImsEnabled) logIdle();
                break;
            case IDLE:
                logIdle();
                break;
            case NONE:
                break;
        }
    }

    private void logIdle() {
        if (mIdleLogged) return;
        mIdleLogged = true;
        Log.i(TAG, "slot=" + mSlotId + " registration idle: no active subscription");
    }

    /** The subscription went away under a running attempt: stop without a retry. */
    private void idleNoSubscription() {
        stopOnRegThread();
        logIdle();
    }

    private void startOnRegThread(int subId, String reason) {
        if (mState.isRunning()) return;
        mState.setRunning(true);
        mRunningSubId = subId;
        mIdleLogged = false;
        Log.i(TAG, "slot=" + mSlotId + " start subId=" + subId + " reason=" + reason
                + ": requesting IMS PDN");
        if (!requestImsNetwork()) {
            mState.setRunning(false);
            mRunningSubId = SlotActivation.NO_SUBSCRIPTION;
            return;
        }
        registerImsPdnWatchdog(subId);
        registerWakeSignals();
        publishState();
    }

    private void stopOnRegThread() {
        mState.setRunning(false);
        mRunningSubId = SlotActivation.NO_SUBSCRIPTION;
        mWaitingForSim = false;
        mHandler.removeCallbacks(mImsPdnWatchdogRunnable);
        mImsPdnWatchdogArmed = false;
        if (mTelCallback != null) {
            try {
                android.telephony.TelephonyManager tm =
                        mContext.getSystemService(android.telephony.TelephonyManager.class);
                if (tm != null) tm.unregisterTelephonyCallback(mTelCallback);
            } catch (Exception e) {
                Log.w(TAG, "unregisterTelephonyCallback failed", e);
            }
            mTelCallback = null;
        }
        if (mNetCallback != null) {
            try { mCm.unregisterNetworkCallback(mNetCallback); } catch (Exception e) {}
            mNetCallback = null;
        }
        if (mWakeSignalsRegistered) {
            try { mContext.unregisterReceiver(mWakeSignalReceiver); } catch (Exception e) {}
            try { mContext.unregisterReceiver(mSimStateReceiver); } catch (Exception e) {}
            mWakeSignalsRegistered = false;
        }
        tearDown();
        armNextCheck();
        publishState();
    }

    /** Lapse checks on the moments a sleeping device comes back, and the SIM load. */
    private void registerWakeSignals() {
        if (mWakeSignalsRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        filter.addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED);
        mContext.registerReceiver(mWakeSignalReceiver, filter, null, mHandler,
                Context.RECEIVER_NOT_EXPORTED);
        /* Sent by the phone process, not by this UID, so the receiver must be
         * exported; the action is a protected broadcast and the sender must
         * also hold READ_PRIVILEGED_PHONE_STATE. */
        mContext.registerReceiver(mSimStateReceiver,
                new IntentFilter(TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED),
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, mHandler,
                Context.RECEIVER_EXPORTED);
        mWakeSignalsRegistered = true;
    }

    /**
     * The SIM became readable after an attempt found it not ready: register
     * now instead of after the retry or the long tail.
     */
    private void onSimLoaded() {
        if (!mWaitingForSim || !mState.isRunning()) return;
        mWaitingForSim = false;
        Log.i(TAG, "slot=" + mSlotId + " SIM loaded — registering without waiting for the retry");
        mState.clearRecovery();
        List<InetAddress> pcscfs = mPcscfs;
        if (pcscfs != null && !pcscfs.isEmpty() && mState.isIdle()) {
            startFreshRegistration("SIM_LOADED");
        }
        armNextCheck();
        publishState();
    }

    /**
     * Builds and registers the IMS NetworkCallback. Idempotent; the watchdog
     * calls it to make the framework re-evaluate a wedged IMS PDN handover.
     */
    private boolean requestImsNetwork() {
        if (mNetCallback != null) {
            try { mCm.unregisterNetworkCallback(mNetCallback); } catch (Exception e) {}
            mNetCallback = null;
        }
        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();

        mNetCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                Log.i(TAG, "IMS PDN onAvailable " + n);
            }
            @Override public void onLost(Network n) {
                onImsPdnLost(n);
            }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                onImsPdnLinkProperties(n, lp);
            }
        };

        try {
            mCm.requestNetwork(req, mNetCallback, mHandler);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "requestNetwork failed", e);
            mNetCallback = null;
            return false;
        }
    }

    private void onImsPdnLost(Network n) {
        Log.w(TAG, "IMS PDN onLost " + n);
        /* SRVCC is unreachable on this device (no IRadioIms), so a call on
         * the lost PDN is dropped locally instead of staying silent. */
        HamelinPortsMmTelFeature feature = mMmTelFeature;
        if (feature != null) {
            try {
                feature.onImsPdnLost();
            } catch (Exception e) {
                Log.w(TAG, "onImsPdnLost forwarding failed", e);
            }
        }
        mImsNetwork = null;
        mPcscfs = null;
        tearDown();
        armNextCheck();
        publishState();
    }

    private void onImsPdnLinkProperties(Network n, LinkProperties lp) {
        List<InetAddress> pcscfs = lp.getPcscfServers();
        String iface = lp.getInterfaceName();
        Log.i(TAG, "IMS PDN props: iface=" + iface + " pcscfs=" + pcscfs);
        boolean ifaceSwapped = mLastBoundIface != null
                && iface != null
                && !iface.equals(mLastBoundIface);
        mImsNetwork = n;
        mPcscfs = pcscfs;
        if (ifaceSwapped) {
            boolean callActive = isCallActive();
            Log.i(TAG, "IMS PDN iface swap " + mLastBoundIface + " -> " + iface
                    + " (callActive=" + callActive + ")");
            /* Latch now: the framework fires this callback twice in a row. */
            mLastBoundIface = iface;
            mPcscfAttempt = 0;
            if (callActive) {
                /* Stopping the stack would kill the active call's dialog. */
                mState.clearRecovery();
            } else {
                Log.i(TAG, "  idle swap — full tearDown + re-register");
                tearDown();
            }
        }
        if (!pcscfs.isEmpty() && mState.isIdle()) {
            mLastBoundIface = iface;
            startFreshRegistration("PDN");
        } else {
            checkRegistration("LINK_CHANGE");
        }
        armNextCheck();
        publishState();
    }

    private void registerImsPdnWatchdog(int subId) {
        android.telephony.TelephonyManager tm =
                mContext.getSystemService(android.telephony.TelephonyManager.class);
        if (tm == null) return;
        mTelCallback = new ImsPdnWatchdogCallback();
        try {
            tm.createForSubscriptionId(subId).registerTelephonyCallback(
                    mHandler::post, mTelCallback);
            Log.i(TAG, "IMS PDN watchdog registered for subId=" + subId);
        } catch (Exception e) {
            Log.w(TAG, "registerTelephonyCallback failed", e);
            mTelCallback = null;
        }
    }

    private final class ImsPdnWatchdogCallback
            extends android.telephony.TelephonyCallback
            implements android.telephony.TelephonyCallback.PreciseDataConnectionStateListener {
        @Override public void onPreciseDataConnectionStateChanged(
                android.telephony.PreciseDataConnectionState s) {
            onImsPdnState(s);
        }
    }

    private void onImsPdnState(android.telephony.PreciseDataConnectionState s) {
        android.telephony.data.ApnSetting apn = s.getApnSetting();
        if (apn == null) return;
        if ((apn.getApnTypeBitmask() & android.telephony.data.ApnSetting.TYPE_IMS) == 0) {
            return;
        }
        final int connected = android.telephony.TelephonyManager.DATA_CONNECTED;
        int t = s.getTransportType();
        int st = s.getState();
        boolean becameConnected = false;
        if (t == android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
            becameConnected = st == connected && mLastWwanImsState != connected;
            mLastWwanImsState = st;
        } else if (t == android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WLAN) {
            becameConnected = st == connected && mLastWlanImsState != connected;
            mLastWlanImsState = st;
        }
        boolean anyHealthy = mLastWwanImsState == connected || mLastWlanImsState == connected;
        if (anyHealthy) {
            if (mImsPdnWatchdogArmed) {
                mHandler.removeCallbacks(mImsPdnWatchdogRunnable);
                mImsPdnWatchdogArmed = false;
            }
        } else if (!mImsPdnWatchdogArmed) {
            mImsPdnWatchdogArmed = true;
            mHandler.postDelayed(mImsPdnWatchdogRunnable, IMS_PDN_WATCHDOG_MS);
        }
        if (becameConnected) {
            checkRegistration("PDN_CONNECTED");
        }
    }

    /**
     * Fires when the IMS APN stays non-CONNECTED on both transports: a
     * handover that collided with a radio-state change can leave the PDN half
     * alive without onLost. Tear down and re-request the network.
     */
    private final Runnable mImsPdnWatchdogRunnable = () -> {
        mImsPdnWatchdogArmed = false;
        Log.w(TAG, "IMS PDN stuck non-CONNECTED on both transports for "
                + (IMS_PDN_WATCHDOG_MS / 1000) + " s — forcing teardown");
        mImsNetwork = null;
        mPcscfs = null;
        tearDown();
        mPcscfAttempt = 0;
        armNextCheck();
        publishState();
        /* Unregister + request again is what makes DataNetworkController
         * pick a transport anew (WWAN if the cell is back, else ePDG). */
        if (mState.isRunning()) {
            requestImsNetwork();
        }
    };

    /**
     * Applies the registration age rule (see {@link RefreshPolicy#decide}).
     * Runs on the registration thread for every trigger: the check alarm,
     * screen on, Doze exit, PDN changes and MO requests.
     */
    private void checkRegistration(String trigger) {
        long now = SystemClock.elapsedRealtime();
        RefreshPolicy.Action action = RefreshPolicy.decide(mState, now);
        switch (action) {
            case REFRESH:
                startRefresh(trigger, now);
                break;
            case FRESH:
                if (isCallActive()) {
                    /* Stopping the stack would drop the call's dialog. */
                    mState.deferFresh(now + RefreshPolicy.ACTIVE_CALL_RECHECK_MS);
                    Log.i(TAG, "fresh registration deferred: call active trigger=" + trigger);
                    break;
                }
                logFreshReason(trigger, now);
                startFreshRegistration(trigger);
                break;
            case TIMED_OUT:
                handleOutcomeOverdue(trigger, now);
                break;
            case REARM:
            case NOOP:
                if ("ALARM".equals(trigger)) {
                    Log.i(TAG, "registration check trigger=ALARM action=" + action
                            + " " + mState.describe(now));
                }
                break;
        }
        armNextCheck();
        publishState();
    }

    /** A call that needs the current stack (an MO call waiting for registration does not). */
    private boolean isCallActive() {
        HamelinPortsMmTelFeature feature = mMmTelFeature;
        return feature != null && feature.hasCallOnStack();
    }

    /**
     * No final response within the wakelock bound: the transaction stalled
     * (in suspend, or the stack is stuck) and its connection and SA cannot
     * be trusted. A stale refresh goes through the retry budget; a stale
     * fresh attempt is terminal, so a stuck stack costs one attempt per
     * long-tail period instead of one per bound.
     */
    private void handleOutcomeOverdue(String trigger, long now) {
        RegistrationState.Op op = mState.inFlight();
        Log.w(TAG, "REGISTER outcome overdue trigger=" + trigger + " op=" + op
                + " ageS=" + (now - mState.inFlightSentMs()) / 1000);
        releaseWakeLock("outcome overdue");
        if (op == RegistrationState.Op.REFRESH && mState.isRegistered()
                && mState.scheduleRetry(now, 0, "refresh retry")) {
            Log.i(TAG, "refresh overdue — retry " + mState.retryCount() + "/"
                    + RefreshPolicy.REG_MAX_RETRIES + " in "
                    + (mState.retryAtMs() - now) / 1000 + " s");
            return;
        }
        failTerminally(0, "outcome overdue");
    }

    private void logFreshReason(String trigger, long now) {
        if (mState.retryAtMs() >= 0) {
            Log.i(TAG, mState.retryReason() + " firing trigger=" + trigger
                    + " lateS=" + (now - mState.retryAtMs()) / 1000
                    + " — fresh REGISTER cycle");
        } else {
            Log.w(TAG, "lapse detected trigger=" + trigger
                    + " ageS=" + mState.ageMs(now) / 1000
                    + " remainingS=" + mState.remainingMs(now) / 1000
                    + " — fresh REGISTER cycle");
        }
    }

    private void startRefresh(String trigger, long now) {
        RefreshPolicy.Window w = mState.renewalWindow();
        long lateMs = mState.ageMs(now) - w.endMs();
        Log.i(TAG, "refresh trigger=" + trigger + " gen=" + mState.generation()
                + " ageS=" + mState.ageMs(now) / 1000
                + " remainingS=" + mState.remainingMs(now) / 1000
                + " lateS=" + Math.max(0, lateMs) / 1000);
        mState.beginRefresh(now);
        acquireWakeLock(RefreshPolicy.REFRESH_WAKELOCK_MS, "refresh");
        boolean sent = mStack.refreshRegister();
        mDiag.onRefresh(sent);
        if (sent) return;
        /* No live registration handle: the binding cannot be renewed in place. */
        Log.w(TAG, "refresh not sent — no live registration handle");
        releaseWakeLock("refresh not sent");
        if (!mState.scheduleRetry(now, 0, "refresh retry")) {
            failTerminally(0, "refresh not sent");
        }
    }

    /**
     * Registers from scratch: stop the stack first (its sockets close inside
     * the old SA), then close the AKA provider (SAs and xfrm policies), then
     * a new security offer and REGISTER. The framework keeps its state until
     * the outcome, so a quick renewal does not flip MT routing to CSFB.
     */
    private void startFreshRegistration(String reason) {
        SipClient.CredentialResult creds = SipClient.readSimCredentials(mContext, mSlotId);
        SlotActivation.NextStep next = SlotActivation.afterCredentialRead(creds.status);
        if (next == SlotActivation.NextStep.IDLE) {
            idleNoSubscription();
            return;
        }
        mWaitingForSim = next == SlotActivation.NextStep.RETRY;
        long now = SystemClock.elapsedRealtime();
        int gen = mState.beginFresh(now);
        /* Before the stack goes down: no MO request may still see a binding. */
        RegistrationSnapshot published = publishSnapshot(now);
        mDiag.mFreshAttempts++;
        mHandler.removeCallbacksAndMessages(mDelayedRegWork);
        acquireWakeLock(RefreshPolicy.FRESH_WAKELOCK_MS, "fresh registration");
        Log.i(TAG, "fresh registration gen=" + gen + " reason=" + reason
                + " published bindingValid=" + published.isBindingValid(now));
        stopStackAndReleaseSa();
        if (!attemptRegistration(gen, creds.credentials)) {
            Log.w(TAG, "fresh registration gen=" + gen + " not sent");
            stopStackAndReleaseSa();
            mState.endAttempt();
            releaseWakeLock("registration not sent");
            if (!mState.scheduleRetry(now, 0, "attempt retry")) {
                failTerminally(0, "registration not sent");
            }
        }
    }

    /** Stops the native stack, then releases the SAs and xfrm policies of the last AKA. */
    private void stopStackAndReleaseSa() {
        mStack.stop();
        mStack.setNetworkHandle(HamelinPortsSipStack.NO_NETWORK);
        if (mAkaProvider != null) {
            try { mAkaProvider.close(); } catch (Exception e) {}
            mAkaProvider = null;
        }
    }

    /** Drops the registration and everything tied to the current stack. */
    private void tearDown() {
        mState.invalidate();
        /* Before the stack goes down: no MO request may still see a binding. */
        publishSnapshot(SystemClock.elapsedRealtime());
        mHandler.removeCallbacksAndMessages(mDelayedRegWork);
        mAssociatedUri = null;
        reportDeregistered(ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE, 0, false);
        stopStackAndReleaseSa();
        releaseProcessNetwork();
        releaseWakeLock("teardown");
    }

    /** Clears the process binding, but only if this slot made it and it still stands. */
    private void releaseProcessNetwork() {
        if (mProcessNetwork.release(mSlotId)) {
            Log.i(TAG, "slot=" + mSlotId + " cleared the process network binding");
        }
    }

    private void reportDeregistered(int reasonCode, int sipCode, boolean notifyModem) {
        if (!mFrameworkRegistered) return;
        mFrameworkRegistered = false;
        mRegImpl.onDeregistered(new ImsReasonInfo(reasonCode, sipCode));
        if (!notifyModem) return;
        try {
            mModemBridge.sendRegistration(mSlotId, /*registered=*/ false,
                    currentModemBridgeRat(), /*volte=*/ false, /*smsIp=*/ false,
                    /*video=*/ false, /*impuUri=*/ null);
        } catch (android.os.RemoteException e) {
            Log.w(TAG, "modem-bridge dereg notify failed", e);
        }
    }

    /** Terminal failure: report it and try again after the long-tail wait. */
    private void failTerminally(int sipCode, String why) {
        long now = SystemClock.elapsedRealtime();
        mAssociatedUri = null;
        reportDeregistered(ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE, sipCode, true);
        mState.scheduleLongTail(now);
        int gen = mState.generation();
        mHandler.postDelayed(() -> {
            /* An active call still needs the stack; the long-tail fresh
             * registration stops it later. */
            if (!mState.isCurrent(gen) || isCallActive()) return;
            Log.i(TAG, "trial tear-down: " + why);
            stopStackAndReleaseSa();
            releaseProcessNetwork();
        }, mDelayedRegWork, STACK_DRAIN_MS);
        Log.i(TAG, "long-tail recovery armed at +"
                + (RefreshPolicy.LONG_TAIL_RETRY_MS / 1000) + " s");
    }

    private RegistrationListener newRegistrationListener(int gen) {
        return new RegistrationListener() {
            @Override public void onExpiresReported(int expires) {
                mHandler.post(() -> {
                    if (!mState.isCurrent(gen)) return;
                    mReportedExpiresSec = expires;
                    Log.i(TAG, "REGISTER Expires negotiated: " + expires + " s");
                });
            }
            @Override public void onRegistered(String associatedUri) {
                mHandler.post(() -> handleRegistered(gen, associatedUri));
            }
            @Override public void onDeregistered(int statusCode, String reason,
                                                 int retryAfterSec) {
                mHandler.post(() -> handleDeregistered(gen, statusCode, reason, retryAfterSec));
            }
        };
    }

    private void handleRegistered(int gen, String associatedUri) {
        if (!mState.isCurrent(gen)) {
            Log.i(TAG, "dropping 200 OK of stale gen=" + gen);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        int expires = mReportedExpiresSec > 0 ? mReportedExpiresSec : mRequestedExpiresSec;
        Log.i(TAG, "*** IMS REGISTERED (native) *** gen=" + gen + " expiresS=" + expires);
        if (!OutgoingGate.isGrantedExpiresAcceptable(expires, mRequestedExpiresSec)) {
            Log.w(TAG, "granted Expires " + expires + " s is below "
                    + Math.min(OutgoingGate.MIN_GRANTED_EXPIRES_SEC, mRequestedExpiresSec)
                    + " s — treating the registration as failed");
            releaseWakeLock("Expires too short");
            failTerminally(0, "Expires too short");
            armNextCheck();
            publishState();
            return;
        }
        mAssociatedUri = (associatedUri != null && !associatedUri.isEmpty())
                ? associatedUri : null;
        mState.onRegistered(now, expires, refreshOverrideSec());
        releaseWakeLock("200 OK");
        mDiag.onRegistered(expires);
        mFrameworkRegistered = true;
        mRegImpl.onRegistered(currentRegistrationTech());
        HamelinPortsMmTelFeature feature = mMmTelFeature;
        if (feature != null) feature.onRegistered();
        try {
            mModemBridge.sendRegistration(mSlotId, /*registered=*/ true,
                    currentModemBridgeRat(), /*volte=*/ true, /*smsIp=*/ true,
                    /*video=*/ false, mAssociatedUri);
            /* Without the preference the modem may keep a CS-preferred voice
             * domain and route MT via CSFB even with IMS up. */
            mModemBridge.sendPreference(mSlotId, /*volte=*/ true, /*video=*/ false,
                    /*smsOverIms=*/ true);
        } catch (android.os.RemoteException e) {
            Log.w(TAG, "modem-bridge sendRegistration/Preference failed", e);
        }
        armNextCheck();
        publishState();
    }

    private void handleDeregistered(int gen, int statusCode, String reason, int retryAfterSec) {
        if (!mState.isCurrent(gen)) {
            Log.i(TAG, "dropping REGISTER failure " + statusCode + " of stale gen=" + gen);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        Log.w(TAG, "REGISTER failed/removed: " + statusCode + " " + reason
                + " retryAfter=" + retryAfterSec + " gen=" + gen);
        mDiag.onDeregistered(statusCode, reason);
        boolean wasRefresh = mState.inFlight() == RegistrationState.Op.REFRESH;
        releaseWakeLock("final response " + statusCode);

        /* A failed renewal of a live binding is followed by a new initial
         * registration (TS 24.229 §5.1.1.4), after any Retry-After (RFC 3261
         * §21.5.4). The binding stays valid meanwhile, so the framework keeps
         * REGISTERED, and the retry waits for an active call to end. */
        boolean is5xx = statusCode >= 500 && statusCode <= 599;
        if (mState.isRegistered() && (is5xx || wasRefresh)) {
            String what = is5xx ? "5xx retry" : "refresh retry";
            if (mState.scheduleRetry(now, retryAfterSec, what)) {
                Log.i(TAG, (is5xx ? "5xx on active registration" : "refresh failed")
                        + " — retry " + mState.retryCount() + "/" + RefreshPolicy.REG_MAX_RETRIES
                        + " in " + (mState.retryAtMs() - now) / 1000 + " s");
                armNextCheck();
                publishState();
                return;
            }
            Log.w(TAG, "retry budget spent or Retry-After=" + retryAfterSec
                    + " s beyond cap — treating as terminal");
        }

        /* P-CSCF failover on timeout: the PDN carries up to three P-CSCFs
         * (TS 23.228 §5.1.1). Other codes would repeat on any of them. */
        List<InetAddress> pcscfs = mPcscfs;
        if (statusCode == 408 && pcscfs != null && mPcscfAttempt + 1 < pcscfs.size()) {
            final int next = mPcscfAttempt + 1;
            Log.i(TAG, "P-CSCF failover after 408: trying " + (next + 1) + "/" + pcscfs.size());
            /* Part of the same fresh cycle: MO requests keep waiting, and the
             * new generation drops late callbacks of the stack being replaced. */
            int failoverGen = mState.beginFresh(now);
            acquireWakeLock(RefreshPolicy.FRESH_WAKELOCK_MS, "P-CSCF failover");
            mHandler.postDelayed(() -> {
                if (!mState.isCurrent(failoverGen) || !mState.isRunning()) return;
                mPcscfAttempt = next;
                startFreshRegistration("pcscf-failover");
                armNextCheck();
                publishState();
            }, mDelayedRegWork, STACK_DRAIN_MS);
        } else {
            failTerminally(statusCode, "post-failure " + statusCode);
        }
        armNextCheck();
        publishState();
    }

    private int refreshOverrideSec() {
        /* Test hook: `setprop persist.lineage.ims.refresh.sec N` (N >= 30)
         * moves the refresh point earlier. */
        return android.os.SystemProperties.getInt("persist.lineage.ims.refresh.sec", 0);
    }

    /** Arms the single wakeup alarm at the next point the state machine needs to run. */
    private void armNextCheck() {
        long now = SystemClock.elapsedRealtime();
        RefreshPolicy.Window w = RefreshPolicy.nextCheck(mState, now);
        if (w == null) {
            mAlarmManager.cancel(mCheckAlarm);
            mDiag.mNextCheckElapsedMs = -1;
            return;
        }
        /* UID 1000 alarms are exempt from Doze deferral and from the
         * minimum window, so the window is honoured as given. */
        mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                w.startMs, w.lengthMs, CHECK_ALARM_TAG, mCheckAlarm, mHandler);
        mDiag.mNextCheckElapsedMs = w.startMs;
    }

    private void acquireWakeLock(long boundMs, String why) {
        if (!mWakeLock.isHeld()) mWakeLockSinceMs = SystemClock.elapsedRealtime();
        mWakeLock.acquire(boundMs);
        Log.i(TAG, "wakelock acquired boundMs=" + boundMs + " for " + why);
    }

    private void releaseWakeLock(String why) {
        if (!mWakeLock.isHeld()) return;
        mWakeLock.release();
        Log.i(TAG, "wakelock released heldMs="
                + (SystemClock.elapsedRealtime() - mWakeLockSinceMs) + " after " + why);
    }

    /** Publishes the state for other threads and releases or expires held MO requests. */
    private void publishState() {
        long now = SystemClock.elapsedRealtime();
        RegistrationSnapshot snapshot = publishSnapshot(now);
        boolean pending = snapshot.isRenewalPending(now) || mPendingOutgoingChecks.get() > 0;
        List<Runnable> due = mDeferred.takeDue(now, pending);
        if (!due.isEmpty()) {
            Log.i(TAG, "releasing " + due.size() + " deferred MO requests (renewalPending="
                    + pending + ")");
            for (Runnable r : due) {
                try { r.run(); } catch (Throwable t) { Log.w(TAG, "deferred MO request threw", t); }
            }
        }
        armDeferralDeadline();
    }

    /**
     * Publishes the MO view of the state only. Called on its own right after
     * a state change that is about to stop the stack, so no MO request can
     * still see a usable binding while the stack goes down.
     */
    private RegistrationSnapshot publishSnapshot(long now) {
        RegistrationSnapshot snapshot = mState.snapshot(now);
        mSnapshot = snapshot;
        mStateSummary = mState.describe(now);
        return snapshot;
    }

    /**
     * Wakeup alarm at the earliest MO deferral deadline, so an expired request
     * is failed on time even if the device suspends meanwhile. Only touched
     * when that deadline changes.
     */
    private void armDeferralDeadline() {
        long next = mDeferred.nextDeadlineMs();
        if (next == mDeferralAlarmAtMs) return;
        mDeferralAlarmAtMs = next;
        if (next < 0) {
            mAlarmManager.cancel(mDeferralAlarm);
        } else {
            mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, next,
                    DEFERRAL_ALARM_WINDOW_MS, DEFERRAL_ALARM_TAG, mDeferralAlarm, mHandler);
        }
    }

    private boolean attemptRegistration(int gen, SipClient.Credentials creds) {
        Network net = mImsNetwork;
        List<InetAddress> pcscfs = mPcscfs;
        if (net == null || pcscfs == null || pcscfs.isEmpty()) return false;
        if (creds == null) { Log.e(TAG, "SIM not ready"); return false; }

        int pcscfIdx = Math.min(mPcscfAttempt, pcscfs.size() - 1);
        InetAddress pcscf = pcscfs.get(pcscfIdx);
        Log.i(TAG, "Attempting registration via reSIProcate to " + pcscf
                + " (#" + (pcscfIdx + 1) + "/" + pcscfs.size() + ")");
        SipClient.UeSecurityParams ueSec = new SipClient.UeSecurityParams();

        /* The source address the IMS PDN gives a socket to the P-CSCF. */
        String localIp;
        InetAddress localAddr;
        try (Socket probe = new Socket()) {
            net.bindSocket(probe);
            probe.connect(new InetSocketAddress(pcscf, 5060), CONNECT_TIMEOUT_MS);
            localIp = stripScope(probe.getLocalAddress().getHostAddress());
            localAddr = probe.getLocalAddress();
        } catch (Exception e) {
            Log.e(TAG, "couldn't determine local IP via P-CSCF probe", e);
            return false;
        }
        Log.i(TAG, "local IMS PDN address = " + localIp);

        /* UE IPsec offer (TS 33.203 §7.2.1). Both algorithms are listed: the
         * P-CSCF rejects an offer without hmac-md5-96 with 403. */
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

        /* reSIProcate creates its sockets internally; its socket hook pins
         * each one to this network. The process binding stays as a fallback
         * until the device shows every SIP socket pinned. */
        if (!mProcessNetwork.bind(mSlotId, net)) {
            Log.w(TAG, "slot=" + mSlotId + " process network binding refused");
        }
        mStack.setNetworkHandle(net.getNetworkHandle());

        /* Order: stack -> transports -> AKA provider and listener -> REGISTER. */
        if (!mStack.start()) {
            Log.e(TAG, "HamelinPortsSipStack.start failed");
            return false;
        }
        if (!mStack.addSipTransports(localIp, ueSec.portC, ueSec.portS)) {
            Log.e(TAG, "addSipTransports failed");
            return false;
        }
        mAkaProvider = new HamelinPortsAkaProviderImpl(
                mContext, mSlotId, ueSec, pcscf, localAddr, localIp);
        mStack.setAkaProvider(mAkaProvider);
        mReportedExpiresSec = 0;
        mStack.setRegistrationListener(newRegistrationListener(gen));

        String pcscfHost = stripScope(pcscf.getHostAddress());
        /* Requested Expires; `setprop persist.lineage.ims.register.expires.sec N`
         * shortens it for tests. A 423 makes reSIProcate retry with Min-Expires. */
        int expiresSec = android.os.SystemProperties.getInt(
                "persist.lineage.ims.register.expires.sec", DEFAULT_REQUESTED_EXPIRES_SEC);
        if (expiresSec < MIN_REQUESTED_EXPIRES_SEC) expiresSec = MIN_REQUESTED_EXPIRES_SEC;
        mRequestedExpiresSec = expiresSec;
        Log.i(TAG, "REGISTER request Expires=" + expiresSec + " s");
        boolean queued = mStack.startRegister(
                impi, impu, creds.domain,
                expiresSec, creds.imeiUrn,
                pcscfHost, 5060, securityClient);
        Log.i(TAG, "REGISTER 1 queued=" + queued + " gen=" + gen);
        return queued;
    }

    /** Diagnostic block for {@code dumpsys activity service org.hamelinports.ims/.HamelinPortsImsService}. */
    void dump(java.io.PrintWriter pw) {
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        pw.println("  slot=" + mSlotId + " usable=" + isRegistered()
                + " renewalPending=" + isRetryInFlight()
                + " processNetworkOwner=" + mProcessNetwork.ownerSlot());
        pw.println("  state: " + mStateSummary);
        pw.println("  pcscfs=" + mPcscfs);
        pw.println("  lastRegistered: " + fmtAgo(nowWall, mDiag.mRegisteredAtMs)
                + " count=" + mDiag.mRegisteredCount
                + " negotiatedExpires=" + mDiag.mLastNegotiatedExpires);
        pw.println("  lastDeregistered: " + fmtAgo(nowWall, mDiag.mDeregisteredAtMs)
                + " count=" + mDiag.mDeregisteredCount
                + " code=" + mDiag.mLastDeregCode
                + " reason=" + mDiag.mLastDeregReason);
        pw.println("  refreshes: sent=" + mDiag.mRefreshesSent
                + " notSent=" + mDiag.mRefreshesNotSent
                + " lastAt=" + fmtAgo(nowWall, mDiag.mLastRefreshAtMs)
                + " freshAttempts=" + mDiag.mFreshAttempts);
        long next = mDiag.mNextCheckElapsedMs;
        pw.println("  nextCheck: " + (next < 0 ? "none" : "in " + (next - nowElapsed) / 1000 + " s"));
    }

    private static String fmtAgo(long nowMs, long thenMs) {
        if (thenMs == 0) return "never";
        long deltaSec = (nowMs - thenMs) / 1000L;
        return thenMs + "ms (" + deltaSec + "s ago)";
    }

    private static String stripScope(String ip) {
        int i = ip.indexOf('%');
        return i > 0 ? ip.substring(0, i) : ip;
    }
}
