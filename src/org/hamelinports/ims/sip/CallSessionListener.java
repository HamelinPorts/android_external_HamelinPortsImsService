package org.hamelinports.ims.sip;

/**
 * Java side of the native InviteSessionHandler. Native fires these
 * from the SIP stack thread when the active outbound call's state
 * changes; implementations forward to the AOSP IMS framework
 * (ImsCallSessionListener).
 *
 * Calls arrive on the SIP stack thread — implementations should
 * post to the framework thread before touching listener state.
 */
public interface CallSessionListener {
    /** A 1xx response arrived (180 Ringing, 183 Session Progress…). */
    void onProvisional(int statusCode, String reason);
    /** Remote SDP received (usually in 18x or 200). Carries the
     *  network-chosen audio payload plus the RTP and RTCP endpoint
     *  ports (RTCP defaults to RTP+1 if no a=rtcp: attribute).
     *  Fires before {@link #onConnected}. */
    void onAnswer(String remoteIp, int remoteRtpPort, int remoteRtcpPort,
                  int payloadType, int clockRate,
                  String codecName, String fmtp);
    /** Companion to {@link #onAnswer} — fires once per incoming SDP
     *  answer that included an {@code m=video} block with port &gt; 0
     *  (i.e. the remote accepted our video offer). Default no-op so
     *  voice-only listeners don't need to care; Phase C.2 overrides
     *  this to open a SESSION_TYPE_VIDEO imsmedia session alongside
     *  the audio one. */
    default void onAnswerVideo(String remoteIp, int remoteRtpPort, int remoteRtcpPort,
                               int payloadType, int clockRate,
                               String codecName, String fmtp) {}
    /** Phase C.4.3 — remote sent a mid-call re-INVITE with a new
     *  SDP offer. Video fields carry {@code port=0} when the new
     *  offer dropped video (downgrade). The implementation MUST
     *  answer via {@link HamelinPortsSipStack#provideReinviteAnswer} or
     *  the session will stay in ReceivedReinvite state and the peer
     *  will time out.
     *
     *  <p>Current policy: auto-accept by mirroring the remote offer —
     *  no user prompt. A later revision can route through Telecom's
     *  {@code receiveSessionModifyRequest} for user consent. */
    default void onRemoteReinvite(String audioIp, int audioPort, int audioRtcpPort,
                                  int audioPt, int audioRate,
                                  String audioCodec, String audioFmtp,
                                  String videoIp, int videoPort, int videoRtcpPort,
                                  int videoPt, int videoRate,
                                  String videoCodec, String videoFmtp) {}
    /** 200 OK to INVITE — call is connected, ACK auto-sent. */
    void onConnected(int statusCode, String reason);
    /** Call ended (BYE from either side, CANCEL, etc.). */
    void onTerminated(int reasonCode, String reason);
    /** 4xx/5xx/6xx response to INVITE. */
    void onFailure(int statusCode, String reason);
}
