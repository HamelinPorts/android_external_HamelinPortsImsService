/* SPDX-License-Identifier: Apache-2.0 */
package org.hamelinports.ims.modem;

/**
 * Bridge → HamelinPortsImsService notification channel. Carries opaque
 * vendor events the bridge wants to surface but that don't map onto
 * an AOSP AIDL.
 *
 * <p>Event ids and payload formats are defined per-bridge; unknown
 * ids are logged and ignored on the IMS side. Keep payloads small —
 * binder transactions over this callback are oneway and unbatched.
 */
oneway interface IImsModemBridgeCallback {
    void onModemEvent(int eventId, in byte[] payload);
}
