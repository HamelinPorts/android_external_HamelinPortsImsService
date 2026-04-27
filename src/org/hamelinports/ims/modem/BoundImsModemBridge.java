/* SPDX-License-Identifier: Apache-2.0 */
package org.hamelinports.ims.modem;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * Client-side proxy returned by {@link ImsModemBridgeFactory} for
 * the bound bridge service. Implements {@link IImsModemBridge}
 * directly (not extending Stub — we're the client side, not the
 * service side) and forwards each call to the latched remote.
 *
 * <p>If a call arrives before {@link ServiceConnection#onServiceConnected}
 * has latched a remote, the most recent {@code sendRegistration} /
 * {@code sendPreference} arguments are cached and replayed once the
 * remote connects. This matters most for the Wi-Fi Calling REGISTER
 * race: our SIP REGISTER 200 OK can land in ~1 s after the IMS PDN
 * iface swap, well before {@code Context.bindService} completes its
 * round-trip to the per-device bridge service. Without replay, the
 * modem never learns "IMS is up over WFC" and the network's MT-routing
 * decision falls through to whatever it had cached for cellular —
 * voicemail or CSFB-to-a-dead-modem if airplane mode is on.
 *
 * <p>Last-wins semantics: a later sendRegistration(false, ...) (dereg)
 * supersedes any earlier registered=true cache, so on bridge connect
 * the modem gets the current state, not stale history.
 */
final class BoundImsModemBridge implements IImsModemBridge {
    private static final String TAG = "ImsModemBridgeFactory";

    private final int mSlotId;
    private volatile IImsModemBridge mRemote;

    /** Cached latest registration args; replayed on bind. Volatile
     *  so the bind-side handler reads a happens-before-published
     *  snapshot without needing a lock for the no-op read path. */
    private volatile RegistrationCall mPendingReg;
    private volatile PreferenceCall   mPendingPref;

    private final ServiceConnection mConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IImsModemBridge r = IImsModemBridge.Stub.asInterface(binder);
            mRemote = r;
            Log.i(TAG, "connected to " + name + " slot=" + mSlotId);
            try {
                r.start(mSlotId);
            } catch (RemoteException e) {
                Log.w(TAG, "start() on freshly-connected bridge failed", e);
            }
            // Replay the most recent IMS state so the modem catches up
            // on calls that landed before the bind completed (notably
            // WFC REGISTER 200 OK arriving ~1 s after iface swap).
            RegistrationCall reg = mPendingReg;
            if (reg != null) {
                try {
                    r.sendRegistration(mSlotId, reg.registered, reg.rat,
                            reg.volte, reg.smsIp, reg.video, reg.impuUri);
                    Log.i(TAG, "replayed sendRegistration(registered="
                            + reg.registered + ", rat=" + reg.rat
                            + ") on bridge bind");
                } catch (RemoteException e) {
                    Log.w(TAG, "replay sendRegistration failed", e);
                }
            }
            PreferenceCall pref = mPendingPref;
            if (pref != null) {
                try {
                    r.sendPreference(mSlotId, pref.volte, pref.video, pref.smsOverIms);
                    Log.i(TAG, "replayed sendPreference on bridge bind");
                } catch (RemoteException e) {
                    Log.w(TAG, "replay sendPreference failed", e);
                }
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "disconnected from " + name + " slot=" + mSlotId);
            mRemote = null;
            // Keep mPendingReg / mPendingPref — if the bridge re-binds
            // we want to bring the modem back up to the latest state.
        }
    };

    BoundImsModemBridge(int slotId) {
        mSlotId = slotId;
    }

    ServiceConnection connection() { return mConn; }

    @Override
    public IBinder asBinder() {
        IImsModemBridge r = mRemote;
        return r == null ? null : r.asBinder();
    }

    @Override
    public void start(int slotId) throws RemoteException {
        IImsModemBridge r = mRemote;
        if (r != null) r.start(slotId);
    }

    @Override
    public void stop(int slotId) throws RemoteException {
        IImsModemBridge r = mRemote;
        if (r != null) r.stop(slotId);
    }

    @Override
    public void sendRegistration(int slotId, boolean registered, int rat,
                                 boolean volte, boolean smsIp, boolean video,
                                 String impuUri) throws RemoteException {
        mPendingReg = new RegistrationCall(registered, rat, volte, smsIp, video, impuUri);
        IImsModemBridge r = mRemote;
        if (r != null) {
            r.sendRegistration(slotId, registered, rat, volte, smsIp, video, impuUri);
        } else {
            Log.d(TAG, "sendRegistration queued for replay — bridge not yet bound (slot="
                    + slotId + ")");
        }
    }

    @Override
    public void sendPreference(int slotId, boolean volte, boolean video,
                               boolean smsOverIms) throws RemoteException {
        mPendingPref = new PreferenceCall(volte, video, smsOverIms);
        IImsModemBridge r = mRemote;
        if (r != null) {
            r.sendPreference(slotId, volte, video, smsOverIms);
        } else {
            Log.d(TAG, "sendPreference queued for replay — bridge not yet bound (slot="
                    + slotId + ")");
        }
    }

    @Override
    public void registerCallback(IImsModemBridgeCallback cb) throws RemoteException {
        IImsModemBridge r = mRemote;
        if (r != null) r.registerCallback(cb);
    }

    @Override
    public void unregisterCallback(IImsModemBridgeCallback cb) throws RemoteException {
        IImsModemBridge r = mRemote;
        if (r != null) r.unregisterCallback(cb);
    }

    private static final class RegistrationCall {
        final boolean registered;
        final int     rat;
        final boolean volte;
        final boolean smsIp;
        final boolean video;
        final String  impuUri;
        RegistrationCall(boolean registered, int rat, boolean volte,
                         boolean smsIp, boolean video, String impuUri) {
            this.registered = registered;
            this.rat        = rat;
            this.volte      = volte;
            this.smsIp      = smsIp;
            this.video      = video;
            this.impuUri    = impuUri;
        }
    }

    private static final class PreferenceCall {
        final boolean volte;
        final boolean video;
        final boolean smsOverIms;
        PreferenceCall(boolean volte, boolean video, boolean smsOverIms) {
            this.volte      = volte;
            this.video      = video;
            this.smsOverIms = smsOverIms;
        }
    }
}
