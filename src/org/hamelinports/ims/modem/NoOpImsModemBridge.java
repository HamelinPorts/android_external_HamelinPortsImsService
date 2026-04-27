/* SPDX-License-Identifier: Apache-2.0 */
package org.hamelinports.ims.modem;

import android.os.RemoteException;

/**
 * No-op {@link IImsModemBridge} for devices that don't ship a
 * proprietary modem-bridge service. AOSP-conformant modems handle
 * the IRadioIms surface entirely on their own and need no extra
 * vendor co-ordination from us; for those, {@link #create} returns
 * this stub and HamelinPortsImsService runs as if no bridge were
 * configured.
 */
public final class NoOpImsModemBridge extends IImsModemBridge.Stub {
    @Override public void start(int slotId) {}
    @Override public void stop(int slotId) {}
    @Override public void sendRegistration(int slotId, boolean registered, int rat,
                                           boolean volte, boolean smsIp,
                                           boolean video, String impuUri) {}
    @Override public void sendPreference(int slotId, boolean volte, boolean video,
                                         boolean smsOverIms) {}
    @Override public void registerCallback(IImsModemBridgeCallback cb) {}
    @Override public void unregisterCallback(IImsModemBridgeCallback cb) {}
}
