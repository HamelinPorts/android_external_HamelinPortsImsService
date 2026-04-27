package org.hamelinports.ims;

import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsService;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.feature.RcsFeature;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.stub.ImsFeatureConfiguration;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;
import android.util.SparseArray;

public class HamelinPortsImsService extends ImsService {
    static final String TAG = "HamelinPortsIms";

    /** One MmTelFeature + ImsRegistrationController per slot. Dual-SIM
     *  devices may populate either slot, and the framework asks us
     *  per-slot. A single shared instance would misroute SMS/INVITE
     *  when the active SIM is in slot 1 but we only bound slot 0. */
    private final SparseArray<HamelinPortsMmTelFeature>        mMmTelFeatures = new SparseArray<>();
    private final SparseArray<ImsRegistrationController>  mRegControllers = new SparseArray<>();

    /** Strong ref to the per-carrier CarrierConfig override pump.
     *  Held on the service so its OnSubscriptionsChangedListener
     *  isn't garbage-collected. */
    @SuppressWarnings("unused")
    private CarrierConfigInjector mCarrierConfigInjector;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "HamelinPortsImsService created");
        mCarrierConfigInjector = CarrierConfigInjector.start(this);
    }

    @Override
    public ImsFeatureConfiguration querySupportedImsFeatures() {
        TelephonyManager tm = getSystemService(TelephonyManager.class);
        int numSlots = (tm != null) ? tm.getActiveModemCount() : 1;
        Log.i(TAG, "querySupportedImsFeatures: advertising MMTEL on " + numSlots + " slots");
        ImsFeatureConfiguration.Builder b = new ImsFeatureConfiguration.Builder();
        for (int i = 0; i < numSlots; i++) {
            b.addFeature(i, ImsFeature.FEATURE_MMTEL);
            b.addFeature(i, ImsFeature.FEATURE_EMERGENCY_MMTEL);
        }
        return b.build();
    }

    private ImsRegistrationController getOrCreateController(int slotId) {
        ImsRegistrationController c = mRegControllers.get(slotId);
        if (c == null) {
            c = new ImsRegistrationController(this, slotId);
            mRegControllers.put(slotId, c);
        }
        return c;
    }

    @Override
    public MmTelFeature createMmTelFeature(int slotId) {
        Log.i(TAG, "createMmTelFeature slotId=" + slotId);
        ImsRegistrationController c = getOrCreateController(slotId);
        HamelinPortsMmTelFeature f = new HamelinPortsMmTelFeature(this, slotId, c);
        c.setMmTelFeature(f);
        c.start();
        mMmTelFeatures.put(slotId, f);
        return f;
    }

    @Override
    public ImsRegistrationImplBase getRegistration(int slotId) {
        Log.i(TAG, "getRegistration slotId=" + slotId);
        return getOrCreateController(slotId).getRegistrationImpl();
    }

    @Override
    public void enableIms(int slotId) {
        Log.i(TAG, "enableIms slotId=" + slotId);
        ImsRegistrationController c = mRegControllers.get(slotId);
        if (c != null) c.start();
    }

    @Override
    public void disableIms(int slotId) {
        Log.i(TAG, "disableIms slotId=" + slotId);
        ImsRegistrationController c = mRegControllers.get(slotId);
        if (c != null) c.stop();
    }

    /** Surfaces per-slot registration diag via
     *  {@code adb shell dumpsys activity service
     *  org.hamelinports.ims/.HamelinPortsImsService}. Queryable after the
     *  logcat ring has rolled — intended for overnight REGISTER-refresh
     *  soak tests where tens of refresh ticks happen over hours.
     *
     *  Debug subcommands (extra args after the component):
     *    wfc-status [subId]         - print VoWiFi pref state
     *    wfc-on     [subId] [mode]  - flip VoWiFi pref on; mode 1/2/3
     *                                 (cellular/wifi-pref/wifi-only),
     *                                 default 2
     *    wfc-off    [subId]         - flip VoWiFi pref off
     *  subId defaults to the system default-data sub.
     */
    @Override
    public void dump(java.io.FileDescriptor fd,
                     java.io.PrintWriter pw, String[] args) {
        if (args != null && args.length > 0 && args[0].startsWith("wfc-")) {
            handleWfcDebug(pw, args);
            pw.flush();
            return;
        }
        pw.println("HamelinPortsImsService");
        pw.println("  wallclock_ms=" + System.currentTimeMillis());
        for (int i = 0; i < mRegControllers.size(); i++) {
            int slotId = mRegControllers.keyAt(i);
            ImsRegistrationController c = mRegControllers.valueAt(i);
            pw.println("-- RegistrationController slot=" + slotId + " --");
            c.dump(pw);
        }
        pw.flush();
    }

    private int defaultSubId(String[] args, int idx) {
        if (args.length > idx) {
            try { return Integer.parseInt(args[idx]); } catch (NumberFormatException ignored) {}
        }
        int s = SubscriptionManager.getDefaultDataSubscriptionId();
        if (s == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            s = SubscriptionManager.getDefaultSubscriptionId();
        }
        return s;
    }

    private void handleWfcDebug(java.io.PrintWriter pw, String[] args) {
        int subId = defaultSubId(args, 1);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            pw.println("wfc: no valid sub");
            return;
        }
        ImsMmTelManager m;
        try {
            m = ImsMmTelManager.createForSubscriptionId(subId);
        } catch (IllegalArgumentException e) {
            pw.println("wfc: createForSubscriptionId failed for subId=" + subId + ": " + e);
            return;
        }
        try {
            switch (args[0]) {
                case "wfc-status": {
                    pw.println("subId=" + subId);
                    pw.println("  VoWiFiSettingEnabled=" + m.isVoWiFiSettingEnabled());
                    pw.println("  VoWiFiModeSetting="    + m.getVoWiFiModeSetting());
                    pw.println("  VoWiFiRoamingModeSetting=" + m.getVoWiFiRoamingModeSetting());
                    break;
                }
                case "wfc-on": {
                    int mode = ImsMmTelManager.WIFI_MODE_WIFI_PREFERRED;
                    if (args.length > 2) {
                        try { mode = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {}
                    }
                    m.setVoWiFiSettingEnabled(true);
                    m.setVoWiFiModeSetting(mode);
                    pw.println("wfc enabled subId=" + subId + " mode=" + mode);
                    break;
                }
                case "wfc-off": {
                    m.setVoWiFiSettingEnabled(false);
                    pw.println("wfc disabled subId=" + subId);
                    break;
                }
                default:
                    pw.println("wfc: unknown command " + args[0]);
            }
        } catch (Exception e) {
            pw.println("wfc: " + args[0] + " failed: " + e);
        }
    }
}
