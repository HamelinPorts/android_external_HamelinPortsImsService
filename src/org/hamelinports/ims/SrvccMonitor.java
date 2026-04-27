// SPDX-License-Identifier: Apache-2.0
package org.hamelinports.ims;

import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * SRVCC (3GPP TS 23.216) <b>observer-only</b> logger. Subscribes to
 * {@link TelephonyCallback.SrvccStateListener} on the slot's active
 * subscription and emits one log line per transition, tagged
 * {@code SRVCC-OBS}, so a field post-mortem can correlate "when did
 * the framework's SRVCC-state cache change" against "when did the
 * framework call our {@code MmTelFeature.notifySrvcc*} overrides".
 *
 * <p>The <em>authoritative</em> SRVCC handling — providing the
 * {@link android.telephony.ims.SrvccCall} list to the modem, tearing
 * down the IMS session silently on COMPLETED, re-synchronising on
 * FAILED/CANCELED — lives in {@link HamelinPortsMmTelFeature}'s
 * {@code notifySrvcc*} overrides, not here. The framework drives
 * both paths from the same modem event
 * ({@code RIL_UNSOL_SRVCC_STATE_NOTIFY}), so logging both sides lets
 * us time-correlate the two code paths when debugging.
 *
 * <p>Why a dedicated class: {@link TelephonyCallback} registration
 * wants a {@code subId}, not a {@code slotId}. The slot↔sub mapping
 * isn't available until the SIM is provisioned
 * ({@link SubscriptionManager#getActiveSubscriptionInfoForSimSlotIndex}),
 * so we start after {@code onFeatureReady} and lazily log a miss if
 * the subscription isn't ready yet.
 */
public final class SrvccMonitor extends TelephonyCallback
        implements TelephonyCallback.SrvccStateListener {

    private static final String TAG = "SRVCC-OBS";

    private final Context mContext;
    private final int mSlotId;
    private TelephonyManager mBoundTm;
    private int mLastState = TelephonyManager.SRVCC_STATE_HANDOVER_NONE;

    SrvccMonitor(Context ctx, int slotId) {
        mContext = ctx;
        mSlotId = slotId;
    }

    void start() {
        SubscriptionManager sm = mContext.getSystemService(SubscriptionManager.class);
        if (sm == null) {
            Log.w(TAG, "no SubscriptionManager");
            return;
        }
        SubscriptionInfo info = sm.getActiveSubscriptionInfoForSimSlotIndex(mSlotId);
        if (info == null) {
            Log.w(TAG, "no active subscription on slot " + mSlotId + "; not registered");
            return;
        }
        int subId = info.getSubscriptionId();
        TelephonyManager tmRoot = mContext.getSystemService(TelephonyManager.class);
        if (tmRoot == null) {
            Log.w(TAG, "no TelephonyManager");
            return;
        }
        mBoundTm = tmRoot.createForSubscriptionId(subId);
        mBoundTm.registerTelephonyCallback(mContext.getMainExecutor(), this);
        Log.i(TAG, "listener registered slot=" + mSlotId + " subId=" + subId);
    }

    void stop() {
        if (mBoundTm != null) {
            mBoundTm.unregisterTelephonyCallback(this);
            mBoundTm = null;
            Log.i(TAG, "listener unregistered slot=" + mSlotId);
        }
    }

    @Override
    public void onSrvccStateChanged(int state) {
        if (state == mLastState) return;
        Log.i(TAG, "event=STATE_CHANGED ts_ns=" + System.nanoTime()
                + " slot=" + mSlotId
                + " prev=" + stateName(mLastState)
                + " new=" + stateName(state));
        mLastState = state;
    }

    private static String stateName(int s) {
        switch (s) {
            case TelephonyManager.SRVCC_STATE_HANDOVER_NONE:      return "NONE";
            case TelephonyManager.SRVCC_STATE_HANDOVER_STARTED:   return "STARTED";
            case TelephonyManager.SRVCC_STATE_HANDOVER_COMPLETED: return "COMPLETED";
            case TelephonyManager.SRVCC_STATE_HANDOVER_FAILED:    return "FAILED";
            case TelephonyManager.SRVCC_STATE_HANDOVER_CANCELED:  return "CANCELED";
            default: return "UNKNOWN(" + s + ")";
        }
    }
}
