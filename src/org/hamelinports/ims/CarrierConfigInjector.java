package org.hamelinports.ims;

import android.content.Context;
import android.net.ipsec.ike.ChildSaProposal;
import android.net.ipsec.ike.SaProposal;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.hamelinports.ims.config.CarrierConfigPushLedger;
import org.hamelinports.ims.config.IwlanDpdInterval;
import org.hamelinports.ims.config.IwlanFallbackPolicy;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Pushes per-carrier CarrierConfig overrides from our APK assets so the
 * framework sees IMS knobs (e.g. {@code carrier_wfc_ims_available_bool})
 * the way this bundle expects, regardless of what the platform's
 * {@code com.android.carrierconfig} default ships.
 *
 * <p>Each carrier we want to override gets one asset file
 * {@code carrier_config_mccmnc_<MCCMNC>.xml} in the AOSP CarrierConfig
 * dialect (one or more {@code <carrier_config>} fragments wrapped in an
 * optional {@code <carrier_config_list>} root). XMLs from AOSP's own
 * CarrierConfig assets dir can be dropped in unchanged.
 *
 * <p>The push uses {@link CarrierConfigManager#overrideConfig(int,
 * PersistableBundle, boolean)} with {@code persistent=true}: the
 * framework stores the override on disk so it survives reboots and
 * SIM re-load, and applies it on top of whatever the default and
 * carrier-privileged services produce. We re-push on every
 * subscription-changed callback to recover from a wipe (e.g. SIM
 * change) without forcing the user to reboot. With
 * {@link CarrierConfigPushLedger#PROPERTY} on, a carrier-config change
 * re-checks the subscription too, so an override pushed before the SIM
 * was loaded (kept in memory only) is pushed again once it can be saved.
 *
 * <p>One instance per process: the service can be created more than once
 * in the same process, and two injectors would push twice.
 */
final class CarrierConfigInjector {
    private static final String TAG = "HamelinPortsCarrierCfg";
    private static final String ASSET_PREFIX = "carrier_config_mccmnc_";

    private static CarrierConfigInjector sInstance;

    private final Context mContext;
    private final SubscriptionManager mSm;
    private final CarrierConfigManager mCcm;
    private final TelephonyManager mTm;
    /** Worker thread only. */
    private final CarrierConfigPushLedger mLedger;
    /** MCCMNCs whose ePDG FQDN resolved; worker thread only. */
    private final Set<String> mEpdgResolved = new HashSet<>();
    private SubscriptionManager.OnSubscriptionsChangedListener mListener;
    private CarrierConfigManager.CarrierConfigChangeListener mConfigListener;
    /** Set with the kernel defaults, once per process. */
    private IwlanFallbackPolicy mIwlanFallback;
    /** Single-threaded background executor for the listener callback.
     *  The work it does — XML asset parsing and a DNS lookup for the
     *  ePDG FQDN — must NOT run on the main thread. */
    private static final Executor sWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HamelinPortsCarrierCfg-Worker");
        t.setDaemon(true);
        return t;
    });
    /** Lazy-built kernel-driven defaults (e.g. xfrm-loaded IPsec algos);
     *  layered under each per-MCCMNC override so SoC-quirks aren't
     *  carrier-specific. Computed once per process — kernel module
     *  set doesn't change at runtime. */
    private PersistableBundle mKernelDefaults;

    /** Starts the process's injector, or returns the one already running. */
    static synchronized CarrierConfigInjector start(Context ctx) {
        if (sInstance == null) {
            sInstance = new CarrierConfigInjector(ctx);
            sInstance.attach();
        }
        return sInstance;
    }

    private CarrierConfigInjector(Context ctx) {
        mContext = ctx.getApplicationContext();
        mSm = ctx.getSystemService(SubscriptionManager.class);
        mCcm = ctx.getSystemService(CarrierConfigManager.class);
        mTm = ctx.getSystemService(TelephonyManager.class);
        mLedger = new CarrierConfigPushLedger(
                SystemProperties.getBoolean(CarrierConfigPushLedger.PROPERTY, false));
    }

    private void attach() {
        if (mSm == null || mCcm == null) {
            Log.w(TAG, "SubscriptionManager/CarrierConfigManager unavailable; injector idle");
            return;
        }
        mListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
            @Override public void onSubscriptionsChanged() {
                injectForActive();
            }
        };
        mSm.addOnSubscriptionsChangedListener(sWorker, mListener);
        if (mLedger.repush()) {
            /* Our own push triggers this too; the ledger stops the loop. */
            mConfigListener = (slotIndex, subId, carrierId, specificCarrierId) -> {
                if (SubscriptionManager.isValidSubscriptionId(subId)) injectForActive();
            };
            mCcm.registerCarrierConfigChangeListener(sWorker, mConfigListener);
            Log.i(TAG, "re-push on carrier-config change enabled");
        }
        // Initial pass on the worker — DNS probe and XML parse must
        // not block the caller's thread (this runs from onCreate).
        sWorker.execute(this::injectForActive);
    }

    private void injectForActive() {
        List<SubscriptionInfo> subs = mSm.getActiveSubscriptionInfoList();
        if (subs == null) return;
        PersistableBundle kernelDefaults = kernelDefaults();
        for (SubscriptionInfo s : subs) {
            int subId = s.getSubscriptionId();
            String mcc = s.getMccString();
            String mnc = s.getMncString();
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    || mcc == null || mnc == null || mcc.isEmpty() || mnc.isEmpty()) {
                continue;
            }
            String mccmnc = mcc + mnc;
            if (!mLedger.mayNeedPush(subId, mccmnc)) continue;

            // Layer per-MCCMNC over kernel defaults so that, e.g., a
            // SoC's IPsec-algo set is applied to every sub regardless
            // of carrier and a carrier's WFC-availability flag is only
            // applied to subs on that carrier.
            PersistableBundle merged = new PersistableBundle();
            if (kernelDefaults != null) merged.putAll(kernelDefaults);
            PersistableBundle perCarrier = readAsset(mccmnc);
            if (perCarrier != null) {
                merged.putAll(perCarrier);
            } else if (epdgResolves(mcc, mnc)) {
                /* No explicit asset for this MCCMNC. Fall back to a
                 * DNS probe for the 3GPP-canonical ePDG FQDN
                 * (TS 23.003 §19.4.2.4). If it resolves, the carrier
                 * operates an ePDG and Wi-Fi Calling is almost
                 * certainly supported on the standard untrusted-WLAN
                 * attach path; flip carrier_wfc_ims_available_bool so
                 * the Settings WFC toggle becomes visible. We do NOT
                 * touch carrier_default_wfc_ims_enabled_bool — the
                 * user opts in by toggling, and can opt back out if
                 * the carrier turns out to require an entitlement
                 * step we don't drive (e.g. some US majors). False
                 * positives are user-recoverable; false negatives
                 * (toggle hidden) are not, so a slight bias toward
                 * "show the toggle when uncertain" is correct. */
                merged.putBoolean("carrier_wfc_ims_available_bool", true);
                Log.i(TAG, "ePDG FQDN resolves for mccmnc=" + mccmnc
                        + " — auto-enabling WFC availability");
            }

            clearStaleIwlanFallbackRules(merged, subId);

            if (merged.isEmpty()) continue;

            String digest = digestOf(merged);
            boolean simLoaded = isSimLoaded(s.getSimSlotIndex());
            if (!mLedger.shouldPush(subId, mccmnc, digest, simLoaded)) continue;

            try {
                mCcm.overrideConfig(subId, merged, /*persistent=*/ true);
                mLedger.recordPush(subId, mccmnc, digest, simLoaded);
                Log.i(TAG, "pushed " + merged.size() + " override(s) subId=" + subId
                        + " mccmnc=" + mccmnc
                        + (mLedger.repush() ? " simLoaded=" + simLoaded : ""));
            } catch (Exception e) {
                Log.w(TAG, "overrideConfig failed subId=" + subId + " mccmnc=" + mccmnc, e);
            }
        }
    }

    /**
     * With the IWLAN fallback off, overwrites QNS rule arrays that an earlier
     * "on" left in the persistent override (keys cannot be removed by
     * omission). A per-carrier asset that sets the key wins.
     */
    private void clearStaleIwlanFallbackRules(PersistableBundle merged, int subId) {
        if (mIwlanFallback.enabled()) return;
        PersistableBundle current;
        try {
            current = mCcm.getConfigForSubId(subId,
                    IwlanFallbackPolicy.KEY_UNREGISTRATION_RULES,
                    IwlanFallbackPolicy.KEY_INITIAL_CONNECTION_FAILURE_RULES);
        } catch (RuntimeException e) {
            Log.w(TAG, "carrier config unreadable subId=" + subId + ": " + e);
            return;
        }
        Map<String, String[]> clear = mIwlanFallback.overrides(
                current.getStringArray(IwlanFallbackPolicy.KEY_UNREGISTRATION_RULES),
                current.getStringArray(IwlanFallbackPolicy.KEY_INITIAL_CONNECTION_FAILURE_RULES));
        for (Map.Entry<String, String[]> e : clear.entrySet()) {
            if (merged.containsKey(e.getKey())) continue;
            merged.putStringArray(e.getKey(), e.getValue());
            Log.i(TAG, "IWLAN fallback off: clearing " + e.getKey() + " subId=" + subId);
        }
    }

    private boolean isSimLoaded(int slotIndex) {
        return mTm != null && slotIndex >= 0
                && mTm.getSimState(slotIndex) == TelephonyManager.SIM_STATE_LOADED;
    }

    /** SHA-256 of the bundle's serialised form, or "" if it cannot be serialised. */
    private static String digestOf(PersistableBundle b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (DigestOutputStream out =
                    new DigestOutputStream(OutputStream.nullOutputStream(), md)) {
                b.writeToStream(out);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            Log.w(TAG, "override digest failed: " + e);
            return "";
        }
    }

    /** {@link #probeEpdgFqdn}, remembering a positive answer per MCCMNC for re-runs. */
    private boolean epdgResolves(String mcc, String mnc) {
        String mccmnc = mcc + mnc;
        if (mEpdgResolved.contains(mccmnc)) return true;
        if (!probeEpdgFqdn(mcc, mnc)) return false;
        mEpdgResolved.add(mccmnc);
        return true;
    }

    /** Build (lazily, once) the SoC-derived defaults that go under every
     *  per-MCCMNC override. Today: the IPsec integrity algorithm set the
     *  kernel actually loaded, queried via
     *  {@link ChildSaProposal#getSupportedIntegrityAlgorithms()} which
     *  bottoms out at {@code IpSecAlgorithm.getSupportedAlgorithms()}.
     *
     *  Why this matters: AOSP IWlan blindly trusts the
     *  {@code iwlan.supported_integrity_algorithms_int_array}
     *  CarrierConfig default list, which includes AES-XCBC-96 (id 5).
     *  On Exynos-9611 the kernel's xfrm doesn't register {@code xcbc(aes)},
     *  so {@code SaProposal.Builder.addIntegrityAlgorithm(5)} throws
     *  {@code IllegalArgumentException} and {@code EpdgChildSaProposal.buildProposal}
     *  crashes before the first IKE_SA_INIT leaves the device. Filtering
     *  the carrier-config list down to what the kernel actually loaded
     *  lets the proposal build succeed; the carrier picks whichever
     *  intersection is supported on its side. Forward-compatible: a
     *  kernel that adds AES-CMAC tomorrow auto-expands the proposal
     *  list with no maintainer intervention.
     *
     *  PRF is NOT probed here: {@code IkeSaProposal} doesn't have a
     *  kernel-backed support API because PRF is IKE-internal (handled
     *  by Android's userspace BoringSSL / java.crypto, not xfrm). The
     *  AOSP {@code iwlan.supported_prf_algorithms_int_array} default
     *  works on every device; no override needed. */
    private PersistableBundle kernelDefaults() {
        if (mKernelDefaults != null) return mKernelDefaults;
        PersistableBundle b = new PersistableBundle();

        Set<Integer> integrityAlgos = ChildSaProposal.getSupportedIntegrityAlgorithms();
        // Drop INTEGRITY_ALGORITHM_NONE — included in the kernel-supported
        // set because IKE allows negotiating "no integrity" for some bizarre
        // legacy cases, but for IPsec ESP we always want a MAC. Including
        // NONE in the proposal list could let a hostile P-CSCF strip MAC
        // from the negotiation.
        List<Integer> integrityIds = new ArrayList<>();
        for (int id : integrityAlgos) {
            if (id != SaProposal.INTEGRITY_ALGORITHM_NONE) {
                integrityIds.add(id);
            }
        }
        int[] integrityArr = new int[integrityIds.size()];
        for (int i = 0; i < integrityIds.size(); i++) integrityArr[i] = integrityIds.get(i);
        b.putIntArray("iwlan.supported_integrity_algorithms_int_array", integrityArr);

        Log.i(TAG, "kernel-supported IPsec integrity algos = "
                + integrityIds + " (SoC defaults applied to every sub)");

        /* Show the carrier name with a "Wi-Fi Calling" suffix in the
         * status bar / lock screen whenever IMS is registered over the
         * ePDG tunnel — index 1 of {@code wfcSpnFormats} resolves to
         * "%s Wi-Fi Calling". AOSP defaults to 0 (no suffix); 1 is
         * carrier-agnostic and is what most stock OEM firmwares pick.
         * Applied to every active sub so users can see at a glance
         * whether they're on VoLTE or VoWiFi without per-carrier
         * config files. */
        b.putInt(CarrierConfigManager.KEY_WFC_SPN_FORMAT_IDX_INT, 1);
        b.putInt(CarrierConfigManager.KEY_WFC_DATA_SPN_FORMAT_IDX_INT, 1);
        b.putInt(CarrierConfigManager.KEY_WFC_FLIGHT_MODE_SPN_FORMAT_IDX_INT, 1);

        /* IKE dead peer detection interval (AOSP default 120 s). Each DPD
         * exchange wakes the phone from suspend and only checks that the
         * ePDG is alive; the NAT-T keepalive, left at its default, keeps
         * home router NAT mappings alive for incoming Wi-Fi calls. A
         * device whose keepalive is offloaded sets a longer interval and
         * accepts a later detection of a silently lost ePDG. IWLAN reads
         * the value when it builds the next tunnel. */
        String dpdProperty = SystemProperties.get(IwlanDpdInterval.PROPERTY);
        IwlanDpdInterval dpd = IwlanDpdInterval.fromProperty(dpdProperty);
        if (dpd.rejected()) {
            Log.w(TAG, IwlanDpdInterval.PROPERTY + "=" + dpdProperty + " is outside "
                    + IwlanDpdInterval.MIN_SEC + ".." + IwlanDpdInterval.MAX_SEC
                    + " s — using " + dpd.seconds() + " s");
        }
        Log.i(TAG, "IKE DPD interval " + dpd.seconds() + " s");
        b.putInt(CarrierConfigManager.Iwlan.KEY_DPD_TIMER_SEC_INT, dpd.seconds());

        /* IWLAN data path failure: QNS rules that move IMS to cellular when
         * the app reports reason 1402, or when IWLAN setups keep failing.
         * QNS reads them once per boot. Off by default. */
        String fallbackProperty = SystemProperties.get(IwlanFallbackPolicy.PROPERTY);
        mIwlanFallback = IwlanFallbackPolicy.fromProperty(fallbackProperty);
        if (mIwlanFallback.rejected()) {
            Log.w(TAG, IwlanFallbackPolicy.PROPERTY + "=" + fallbackProperty + " is neither 0 nor "
                    + IwlanFallbackPolicy.MIN_SEC + ".." + IwlanFallbackPolicy.MAX_SEC
                    + " s — IWLAN fallback off");
        }
        if (mIwlanFallback.enabled()) {
            for (Map.Entry<String, String[]> e : mIwlanFallback.overrides(null, null).entrySet()) {
                b.putStringArray(e.getKey(), e.getValue());
            }
            Log.i(TAG, "IWLAN fallback to cellular for " + mIwlanFallback.seconds()
                    + " s after a data path failure");
        }

        mKernelDefaults = b;
        return b;
    }

    /** DNS probe for the 3GPP-canonical ePDG FQDN
     *  ({@code epdg.epc.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org} per
     *  TS 23.003 §19.4.2.4, with MNC zero-padded to 3 digits). A
     *  successful resolution is a strong yes/no signal that the
     *  carrier operates a Wi-Fi Calling ePDG. Used as the fallback
     *  when no explicit per-MCCMNC asset XML matches.
     *
     *  Runs on the worker thread, blocking up to whatever the system
     *  resolver's default timeout is (typically a handful of seconds
     *  on cache miss). One-off cost per sub-attach. */
    private boolean probeEpdgFqdn(String mcc, String mnc) {
        // Per TS 23.003, MNC in the ePDG FQDN is always 3 digits
        // (zero-padded if the SIM stores 2 digits). MCC is always 3.
        String mnc3 = mnc;
        while (mnc3.length() < 3) mnc3 = "0" + mnc3;
        String fqdn = "epdg.epc.mnc" + mnc3 + ".mcc" + mcc + ".pub.3gppnetwork.org";
        try {
            InetAddress[] addrs = InetAddress.getAllByName(fqdn);
            int n = (addrs == null) ? 0 : addrs.length;
            Log.i(TAG, "probeEpdgFqdn " + fqdn + " -> " + n + " addr(s)");
            return n > 0;
        } catch (UnknownHostException e) {
            Log.i(TAG, "probeEpdgFqdn " + fqdn + " -> NXDOMAIN");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "probeEpdgFqdn " + fqdn + " unexpected: " + e);
            return false;
        }
    }

    private PersistableBundle readAsset(String mccmnc) {
        String name = ASSET_PREFIX + mccmnc + ".xml";
        try (InputStream is = mContext.getAssets().open(name)) {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(is, "utf-8");
            PersistableBundle merged = new PersistableBundle();
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG
                        && "carrier_config".equals(parser.getName())) {
                    merged.putAll(PersistableBundle.restoreFromXml(parser));
                }
            }
            return merged;
        } catch (IOException ioe) {
            return null;
        } catch (XmlPullParserException xpe) {
            Log.w(TAG, "parse error for " + name, xpe);
            return null;
        }
    }
}
