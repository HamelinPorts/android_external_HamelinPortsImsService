// SPDX-License-Identifier: Apache-2.0
package org.hamelinports.ims.sip;

/**
 * Subscriber for MT (server-side) INVITE events from the native
 * reSIProcate stack. Wired once at MmTelFeature start-up; one
 * {@link #onIncomingInvite} fires per incoming INVITE after reSIProcate
 * has parsed the offer SDP.
 *
 * <p>Implementation must: (1) create a new
 * {@code HamelinPortsIncomingCallSession} keyed on {@code callId};
 * (2) invoke {@code MmTelFeature.notifyIncomingCall} so the framework
 * presents the in-call UI; (3) fire
 * {@link HamelinPortsSipStack#progressRinging(String)} so the remote
 * hears ring-back.
 *
 * <p>Fields {@code remoteIp} / {@code remotePort} / {@code rtcpPort}
 * / {@code payloadType} / {@code clockRate} / {@code codecName} /
 * {@code fmtp} are the first non-telephone-event audio codec the
 * offer carried; the MT answer will mirror them in its m=audio line.
 */
public interface IncomingCallListener {
    void onIncomingInvite(String callId, String fromUri,
                          String remoteIp, int remotePort, int rtcpPort,
                          int payloadType, int clockRate,
                          String codecName, String fmtp);

    /** Companion to {@link #onIncomingInvite}, fires once per incoming
     *  INVITE whose SDP offer included an {@code m=video} block with
     *  {@code port > 0}. Always delivered AFTER the audio call; the
     *  implementation should annotate the existing incoming session
     *  with video params so {@code accept()} can build a matching
     *  SDP answer and open a SESSION_TYPE_VIDEO imsmedia session. */
    default void onIncomingInviteVideo(String callId, String remoteIp,
                                       int remotePort, int rtcpPort,
                                       int payloadType, int clockRate,
                                       String codecName, String fmtp) {}

    /** Server-side MT dialog terminated before accept — remote CANCEL,
     *  network BYE, or local decline. The implementation must propagate
     *  {@code callSessionTerminated} on the matching session so the
     *  framework stops ringing the dialer. {@code reason} mirrors
     *  reSIProcate {@code TerminatedReason} ordinals (e.g. 6 = peer
     *  canceled). */
    void onIncomingCancelled(String callId, int reason);
}
