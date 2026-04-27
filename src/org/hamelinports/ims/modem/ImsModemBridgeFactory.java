/* SPDX-License-Identifier: Apache-2.0 */
package org.hamelinports.ims.modem;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;

/**
 * Single entry point for obtaining an {@link IImsModemBridge}.
 *
 * <p>Per-device bridge implementations expose a {@link
 * android.app.Service} matching {@link #BIND_ACTION}. This factory
 * issues a {@code bindService} and returns a {@link BoundImsModemBridge}
 * proxy that callers use as an {@link IImsModemBridge}. Calls before
 * the binding completes are silently dropped — fine for the AIDL's
 * oneway contract; the controller will retry on the next IMS state
 * transition.
 *
 * <p>If no bridge service is installed, the factory returns
 * {@link NoOpImsModemBridge} — a local stub so the IMS service runs
 * unchanged.
 */
public final class ImsModemBridgeFactory {
    private static final String TAG = "ImsModemBridgeFactory";

    /** Well-known intent action a per-device bridge service publishes. */
    public static final String BIND_ACTION =
            "org.hamelinports.ims.modem.action.BIND_BRIDGE";

    private ImsModemBridgeFactory() {}

    public static IImsModemBridge create(Context ctx, int slotId) {
        Intent intent = new Intent(BIND_ACTION);
        ComponentName cn = resolve(ctx, intent);
        if (cn == null) {
            Log.i(TAG, "no bridge service installed — using no-op stub slot=" + slotId);
            return new NoOpImsModemBridge();
        }
        intent.setComponent(cn);
        BoundImsModemBridge proxy = new BoundImsModemBridge(slotId);
        boolean queued = ctx.bindService(intent, proxy.connection(),
                Context.BIND_AUTO_CREATE);
        if (!queued) {
            Log.w(TAG, "bindService refused for " + cn + " — using no-op stub");
            return new NoOpImsModemBridge();
        }
        Log.i(TAG, "binding to " + cn + " slot=" + slotId);
        return proxy;
    }

    private static ComponentName resolve(Context ctx, Intent intent) {
        List<android.content.pm.ResolveInfo> hits =
                ctx.getPackageManager().queryIntentServices(intent, 0);
        if (hits == null || hits.isEmpty()) return null;
        android.content.pm.ResolveInfo r = hits.get(0);
        return new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
    }
}
