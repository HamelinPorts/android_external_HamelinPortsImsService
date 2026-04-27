package org.hamelinports.ims;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

/**
 * Self-bootstrap for the AOSP ImsResolver binding chain.
 *
 * Background: ImsResolver only calls our {@code createMmTelFeature} when
 * {@code HANDLER_CONFIG_CHANGED} fires with a VALID slot id. On the A51
 * the SIM frequently boots with {@code simSlotIndex = -1}; ImsResolver
 * receives the early CarrierConfigChanged for invalid slot, returns,
 * and never re-triggers because nothing else changes content-wise in
 * the carrier config when the slot finally becomes valid.
 *
 * What we do: after {@code BOOT_COMPLETED}, wait for a valid SIM
 * subscription, then call
 * {@link CarrierConfigManager#notifyConfigChangedForSubId(int)} on it.
 * That synthesises a fresh CCC notification — ImsResolver runs
 * {@code carrierConfigChanged → updateBoundDeviceServices →
 * scheduleQueryForFeatures → bindService → onBind →
 * querySupportedImsFeatures → calculateFeaturesToCreate →
 * createMmTelFeature}.
 *
 * Requires {@code MODIFY_PHONE_STATE} for the notify call. Polls every
 * 2 s for up to 60 s for a valid subscription before giving up.
 */
public class ImsResolverKickReceiver extends BroadcastReceiver {
    private static final String TAG = "HamelinPortsIms";
    private static final long POLL_INTERVAL_MS = 2_000;
    private static final long POLL_BUDGET_MS   = 60_000;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.i(TAG, "ImsResolverKickReceiver onReceive: " + intent.getAction());
        Handler h = new Handler(Looper.getMainLooper());
        long deadline = System.currentTimeMillis() + POLL_BUDGET_MS;
        Runnable poll = new Runnable() {
            @Override public void run() {
                int subId = pickFirstValidSubId(ctx);
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    Log.i(TAG, "kick: notifying CCC for subId=" + subId);
                    try {
                        CarrierConfigManager ccm =
                                ctx.getSystemService(CarrierConfigManager.class);
                        if (ccm != null) ccm.notifyConfigChangedForSubId(subId);
                        else Log.w(TAG, "kick: no CarrierConfigManager");
                    } catch (Exception e) {
                        Log.w(TAG, "kick: notifyConfigChangedForSubId failed", e);
                    }
                    return;
                }
                if (System.currentTimeMillis() < deadline) {
                    h.postDelayed(this, POLL_INTERVAL_MS);
                } else {
                    Log.w(TAG, "kick: gave up waiting for a valid subscription");
                }
            }
        };
        h.postDelayed(poll, POLL_INTERVAL_MS);
    }

    private static int pickFirstValidSubId(Context ctx) {
        SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
        if (sm == null) return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        java.util.List<SubscriptionInfo> active = sm.getActiveSubscriptionInfoList();
        if (active == null || active.isEmpty()) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        for (SubscriptionInfo info : active) {
            if (info.getSimSlotIndex() >= 0) {
                return info.getSubscriptionId();
            }
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }
}
