package org.hamelinports.ims;

import android.content.Context;
import android.net.ipsec.ike.ChildSaProposal;
import android.net.ipsec.ike.SaProposal;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
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
 * change) without forcing the user to reboot.
 */
final class CarrierConfigInjector {
    private static final String TAG = "HamelinPortsCarrierCfg";
    private static final String ASSET_PREFIX = "carrier_config_mccmnc_";

    private final Context mContext;
    private final SubscriptionManager mSm;
    private final CarrierConfigManager mCcm;
    private final Map<Integer, String> mInjected = new HashMap<>();
    private SubscriptionManager.OnSubscriptionsChangedListener mListener;
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

    static CarrierConfigInjector start(Context ctx) {
        CarrierConfigInjector i = new CarrierConfigInjector(ctx);
        i.attach();
        return i;
    }

    private CarrierConfigInjector(Context ctx) {
        mContext = ctx.getApplicationContext();
        mSm = ctx.getSystemService(SubscriptionManager.class);
        mCcm = ctx.getSystemService(CarrierConfigManager.class);
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
            if (mccmnc.equals(mInjected.get(subId))) continue;

            // Layer per-MCCMNC over kernel defaults so that, e.g., a
            // SoC's IPsec-algo set is applied to every sub regardless
            // of carrier and a carrier's WFC-availability flag is only
            // applied to subs on that carrier.
            PersistableBundle merged = new PersistableBundle();
            if (kernelDefaults != null) merged.putAll(kernelDefaults);
            PersistableBundle perCarrier = readAsset(mccmnc);
            if (perCarrier != null) {
                merged.putAll(perCarrier);
            } else if (probeEpdgFqdn(mcc, mnc)) {
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

            if (merged.isEmpty()) continue;

            try {
                mCcm.overrideConfig(subId, merged, /*persistent=*/ true);
                mInjected.put(subId, mccmnc);
                Log.i(TAG, "pushed " + merged.size() + " override(s) subId=" + subId
                        + " mccmnc=" + mccmnc);
            } catch (Exception e) {
                Log.w(TAG, "overrideConfig failed subId=" + subId + " mccmnc=" + mccmnc, e);
            }
        }
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
