package org.hamelinports.ims.net;

import android.net.ConnectivityManager;
import android.net.Network;
import android.util.Log;

/**
 * The process network binding through {@link ConnectivityManager}. The only
 * place that binds or clears it; callers go through {@link ProcessNetworkOwner}.
 */
public final class ConnectivityProcessBinding implements ProcessNetworkOwner.Binding<Network> {
    private static final String TAG = "HamelinPortsIms";

    private final ConnectivityManager mCm;

    public ConnectivityProcessBinding(ConnectivityManager cm) {
        mCm = cm;
    }

    @Override
    public boolean bind(Network network) {
        try {
            return mCm.bindProcessToNetwork(network);
        } catch (RuntimeException e) {
            Log.e(TAG, "bindProcessToNetwork failed", e);
            return false;
        }
    }

    @Override
    public void clear() {
        try {
            mCm.bindProcessToNetwork(null);
        } catch (RuntimeException e) {
            Log.w(TAG, "clearing the process network binding failed", e);
        }
    }

    @Override
    public Network current() {
        return mCm.getBoundNetworkForProcess();
    }
}
