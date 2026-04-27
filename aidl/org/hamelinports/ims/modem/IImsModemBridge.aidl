/* SPDX-License-Identifier: Apache-2.0 */
package org.hamelinports.ims.modem;

import org.hamelinports.ims.modem.IImsModemBridgeCallback;

/**
 * Vendor-proprietary modem co-ordination surface that AOSP's
 * android.hardware.radio.ims.IRadioIms does not model.
 *
 * <p>Implemented by a per-device service. HamelinPortsImsService binds
 * to whichever implementation the device shipped via PRODUCT_PACKAGES;
 * absent implementation falls back to a no-op in-process stub.
 *
 * <p>All methods are oneway — modem coordination is fire-and-forget;
 * outcomes that matter to the IMS state machine arrive via the
 * standard AOSP paths (IRadioIms responses, ImsRegistrationCallback,
 * MmTelFeature capability changes, etc.).
 */
oneway interface IImsModemBridge {
    /** Radio-access technology constants the bridge accepts on
     *  {@link #sendRegistration}. These are the canonical
     *  RIL_RADIO_TECHNOLOGY values; vendor bridges may translate
     *  them internally to their own modem-side encoding. */
    const int RAT_LTE   = 14;
    const int RAT_NR    = 20;
    const int RAT_IWLAN = 18;

    /** Mark a slot as active. The bridge can use this to pre-bind
     *  per-slot modem channels and prepare any per-slot diagnostics.
     *  Call once per slot from each {@code BoundImsModemBridge}
     *  proxy after the binder connection latches. */
    void start(int slotId);

    /** Tear down per-slot state. */
    void stop(int slotId);

    /** Tell the modem the IMS registration state so it can program
     *  CS-domain routing. Without this on hardware where the modem
     *  doesn't autonomously observe IMS registrations, the EPC routes
     *  MT voice as CSFB even when our IMS REGISTER succeeded.
     *
     *  @param slotId      caller-provided slot id; the bridge
     *                     demultiplexes per-SIM modem state on this
     *                     (single-SIM devices typically pass 0)
     *  @param registered  true on REGISTER 200 OK, false on dereg
     *  @param rat         current RAT (TelephonyManager.NETWORK_TYPE_*)
     *  @param volte       VoLTE supported on this registration
     *  @param smsIp       SMS-over-IP supported
     *  @param video       MMTel-video supported
     *  @param impuUri     P-Associated-URI (carrier public identity)
     *                     or null on dereg
     */
    void sendRegistration(int slotId, boolean registered, int rat,
                          boolean volte, boolean smsIp, boolean video,
                          in @nullable String impuUri);

    /** Voice-domain preference notification. Tells the modem we want
     *  PS-preferred voice, which makes the next NAS TAU advertise
     *  VoPS=1.
     *
     *  @param slotId       caller-provided slot id
     *  @param volte        prefer VoLTE for voice
     *  @param video        prefer VT for video
     *  @param smsOverIms   prefer SMS over IMS rather than CS
     */
    void sendPreference(int slotId, boolean volte, boolean video, boolean smsOverIms);

    /** Subscribe to modem-side events the bridge surfaces back to us
     *  (e.g. vendor-specific PDN status, MT-routing flips). The set
     *  of event ids is bridge-defined; HamelinPortsImsService treats
     *  unknown ids as informational. */
    void registerCallback(in IImsModemBridgeCallback cb);

    void unregisterCallback(in IImsModemBridgeCallback cb);
}
