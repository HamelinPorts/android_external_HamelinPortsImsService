package org.hamelinports.ims;

import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSessionListener;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.util.Log;

import org.hamelinports.ims.sip.CallSessionListener;
import org.hamelinports.ims.sip.HamelinPortsSipStack;
import org.hamelinports.ims.sip.SipClient;

import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Outbound IMS call session — drives the INVITE through reSIProcate
 * via HamelinPortsSipStack. The native HamelinPortsInviteHandler owns the SIP
 * lifecycle (PRACK, ACK, BYE auto-handled by DUM); we just translate
 * its callbacks back into the AOSP ImsCallSessionListener.
 *
 * No audio yet — see project_a51_volte_root_cause memory: the call
 * rings, connects (ACK auto-sent), the network tears it down after
 * its RTP-inactivity timeout (~30 s). RTP media engine is a separate
 * follow-up.
 */
public class HamelinPortsCallSession extends ImsCallSessionImplBase {
    private static final String TAG = HamelinPortsImsService.TAG;

    private final ImsRegistrationController mRegController;
    private final ImsCallProfile mProfile;
    private ImsCallSessionListener mListener;
    private DatagramSocket mRtpSocket;
    private DatagramSocket mRtcpSocket;
    private int mRtpPort;
    private int mRtcpPort;
    private String mCalleeNumber;

    /* Video RTP/RTCP socket pair. Allocated only when
     * {@link #isVideoCall} returns true; null for voice-only MO calls.
     * Independent of the audio pair — imsmedia opens a second
     * SESSION_TYPE_VIDEO session with these once the peer accepts the
     * m=video block in the SDP answer. */
    private DatagramSocket mRtpSocketVideo;
    private DatagramSocket mRtcpSocketVideo;
    private int mRtpPortVideo;
    private int mRtcpPortVideo;

    /** Remote audio parameters captured from the 200 OK's SDP answer
     *  (or from a 18x provisional if the network chose to send SDP
     *  early). Consumed by the media engine on {@code onConnected}.
     *  Null before the network answer arrives. */
    private RemoteAudio mRemoteAudio;
    /** Populated by {@link CallSessionListener#onAnswerVideo} when the
     *  peer accepts our {@code m=video} offer with {@code port > 0}.
     *  Null for voice-only calls or when the network rejected video
     *  (Mavenir returns {@code port=0} in that case per RFC 3264 §6.1). */
    private RemoteVideo mRemoteVideo;
    private HamelinPortsImsMediaSession mImsMedia;
    private HamelinPortsImsMediaVideoSession mImsMediaVideo;
    private HamelinPortsVideoCallProvider mVideoProvider;
    private String mCurrentCameraId;
    private android.view.Surface mPendingPreviewSurface;
    private android.view.Surface mPendingDisplaySurface;

    private static final class RemoteVideo {
        final String ip;
        final int rtpPort;
        final int rtcpPort;
        final int payloadType;
        final int clockRate;
        final String codecName;
        final String fmtp;
        RemoteVideo(String ip, int rtpPort, int rtcpPort,
                    int pt, int rate, String name, String fmtp) {
            this.ip = ip; this.rtpPort = rtpPort; this.rtcpPort = rtcpPort;
            this.payloadType = pt; this.clockRate = rate;
            this.codecName = name; this.fmtp = fmtp;
        }
    }

    private static final class RemoteAudio {
        final String ip;
        final int rtpPort;
        final int rtcpPort;
        final int payloadType;
        final int clockRate;
        final String codecName;
        final String fmtp;
        RemoteAudio(String ip, int rtpPort, int rtcpPort,
                    int pt, int rate, String name, String fmtp) {
            this.ip = ip;
            this.rtpPort = rtpPort;
            this.rtcpPort = rtcpPort;
            this.payloadType = pt;
            this.clockRate = rate;
            this.codecName = name;
            this.fmtp = fmtp;
        }
    }

    HamelinPortsCallSession(ImsRegistrationController regController, ImsCallProfile profile) {
        this(regController, profile, null);
    }

    HamelinPortsCallSession(ImsRegistrationController regController, ImsCallProfile profile,
                       java.util.function.Consumer<HamelinPortsCallSession> onGone) {
        mRegController = regController;
        mProfile = profile;
        mOnGone = onGone;
    }

    /** Optional callback invoked exactly once when the session reaches a
     *  terminal state, so {@link HamelinPortsMmTelFeature} can null its
     *  {@code mActiveCall} reference promptly instead of waiting until
     *  the next {@code createCallSession} to garbage-collect it. */
    private final java.util.function.Consumer<HamelinPortsCallSession> mOnGone;
    private boolean mGoneFired = false;

    /** SRVCC Phase I.3 — handover-in-flight guard. While true, external
     *  {@link #terminate(int)} calls must NOT emit BYE: after
     *  HANDOVER_STARTED the CS leg is taking over, and a BYE to the
     *  P-CSCF now confuses the SCC-AS and can tear down the
     *  freshly-handed-over CS call (3GPP TS 23.216 §6.2 / TS 24.237
     *  §12.2). Set by {@link HamelinPortsMmTelFeature#onSrvccStarted},
     *  cleared on any terminal SRVCC transition. */
    private volatile boolean mSrvccStarted = false;

    /** Monotonic nanotime of the most recent {@code onConnected}
     *  callback, for annotating SRVCC diagnostic log lines with a
     *  call-age. Zero until the call is answered. */
    private volatile long mCallStartNs = 0;

    void setSrvccStarted(boolean v) { mSrvccStarted = v; }
    boolean isSrvccStarted() { return mSrvccStarted; }

    /** Called by {@link HamelinPortsMmTelFeature#onImsPdnLost} when the
     *  IMS bearer disappears mid-call (LTE→2G on networks where
     *  SRVCC isn't wired — see project_srvcc_not_wired memory).
     *  Without this path Telecom stays in ACTIVE state and the user
     *  hears silence until they manually hang up. Release media
     *  immediately and fire a {@code callSessionTerminated} with
     *  {@code CODE_LOCAL_NETWORK_NO_SERVICE} so the dialer plays
     *  the normal disconnect tone and the call-log entry reads
     *  "Lost service". Do NOT attempt a BYE — the sockets are
     *  already aborted, reSIProcate's stack may be torn down, and
     *  the endCall path is now no-op-guarded anyway. */
    void handleImsPdnLost() {
        Log.w(TAG, "MO handleImsPdnLost — terminating call locally");
        cleanup();
        if (mListener != null) {
            try {
                mListener.callSessionTerminated(new ImsReasonInfo(
                        ImsReasonInfo.CODE_LOCAL_NETWORK_NO_SERVICE, 0,
                        "IMS bearer lost mid-call"));
            } catch (Exception e) {
                Log.w(TAG, "callSessionTerminated threw", e);
            }
        }
    }
    long getCallStartNs() { return mCallStartNs; }

    /** Stable session-unique identifier for {@link android.telephony.ims.SrvccCall}.
     *  RIL uses this only to correlate the IMS-side handle with its
     *  internal call tracking across the PS→CS transition; format is
     *  opaque to the modem. "lios-" + identityHashCode survives the
     *  session lifetime and avoids collisions between back-to-back
     *  calls even with quick recycle. */
    private final String mSrvccCallId = "lios-" + System.identityHashCode(this);
    String getSrvccCallId() { return mSrvccCallId; }

    private synchronized void fireGoneOnce() {
        if (mGoneFired) return;
        mGoneFired = true;
        if (mOnGone != null) {
            try { mOnGone.accept(this); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void setListener(ImsCallSessionListener listener) {
        mListener = listener;
    }

    @Override
    public ImsCallProfile getCallProfile() {
        return mProfile;
    }

    /** ImsPhoneConnection.updateMediaCapabilities reads the audio
     *  quality from the LOCAL profile (not the main getCallProfile)
     *  to set Connection#PROPERTY_HIGH_DEF_AUDIO. Default returns
     *  null, so without this override the HD badge never renders
     *  even when we've stamped the codec onto mProfile. */
    @Override
    public ImsCallProfile getLocalCallProfile() {
        return mProfile;
    }

    /** Same profile object reused. ImsPhoneConnection's HD-detection
     *  reads {@code remoteCallProfile.getRestrictCause()} alongside the
     *  local audio quality; the default {@code CALL_RESTRICT_CAUSE_NONE}
     *  on our profile satisfies that check. */
    @Override
    public ImsCallProfile getRemoteCallProfile() {
        return mProfile;
    }

    /** Framework fetches this once the call becomes active; returns
     *  null for voice-only calls. Lazy-initialised: the Provider
     *  wraps {@code this} as the SurfaceTarget, forwarding Telecom-
     *  delivered camera/display surfaces to the live imsmedia video
     *  session (or latching them if the session isn't up yet). */
    @Override
    public android.telephony.ims.ImsVideoCallProvider getImsVideoCallProvider() {
        if (!isVideoCall(mProfile)) return null;
        if (mVideoProvider == null) {
            mVideoProvider = new HamelinPortsVideoCallProvider(new HamelinPortsVideoCallProvider.SurfaceTarget() {
                @Override public void setCameraId(String cameraId) {
                    mCurrentCameraId = cameraId;
                    Log.i(TAG, "MO videoProvider.setCameraId=" + cameraId
                            + " (wire-through to libimsmedia TBD: C.3.x)");
                }
                @Override public void setPreviewSurface(android.view.Surface s) {
                    mPendingPreviewSurface = s;
                    if (mImsMediaVideo != null) mImsMediaVideo.setPreviewSurface(s);
                }
                @Override public void setDisplaySurface(android.view.Surface s) {
                    mPendingDisplaySurface = s;
                    if (mImsMediaVideo != null) mImsMediaVideo.setDisplaySurface(s);
                }
                @Override public void setDeviceOrientation(int rotation) {
                    Log.i(TAG, "MO videoProvider.setDeviceOrientation=" + rotation
                            + " (libimsmedia deviceOrientation TBD: C.3.x)");
                }
                @Override public void setZoom(float value) {
                    /* Zoom only has meaning at the camera layer; imsmedia doesn't expose it. */
                }
                @Override public void sessionModifyRequest(
                        android.telecom.VideoProfile from, android.telecom.VideoProfile to) {
                    handleSessionModifyRequest(from, to);
                }
                @Override public void sessionModifyResponse(
                        android.telecom.VideoProfile responseProfile) {
                    /* Our side's response to a REMOTE session-modify
                     * request (incoming re-INVITE). C.4.3 work —
                     * requires detecting re-INVITE via InviteSession-
                     * Handler::onOffer on a CLIENT handle (currently
                     * only the server-side path runs). Skeleton. */
                    Log.i(TAG, "MO sessionModifyResponse " + responseProfile
                            + " (incoming re-INVITE handling TODO)");
                }
            });
        }
        return mVideoProvider;
    }

    @Override
    public void start(String callee, ImsCallProfile profile) {
        Log.i(TAG, "CallSession.start callee=" + callee);
        new Thread(() -> {
            try {
                doOutgoingCall(callee);
            } catch (Exception e) {
                Log.e(TAG, "outgoing call failed", e);
                if (mListener != null) {
                    mListener.callSessionInitiatedFailed(new ImsReasonInfo(
                            ImsReasonInfo.CODE_LOCAL_INTERNAL_ERROR, 0, e.getMessage()));
                }
            }
        }, "HamelinPortsIms-call").start();
    }

    @Override
    public void terminate(int reason) {
        if (mSrvccStarted) {
            /* SRVCC Phase I.3 guard: a terminate() during SRVCC HO
             * in-flight must NOT emit BYE on the IMS leg. The CS leg
             * is taking the call; a BYE now races the SCC-AS session
             * transfer and can drop the newly-handed-over CS call
             * (3GPP TS 23.216 §6.2 / TS 24.237 §12.2). Return without
             * teardown so the SRVCC COMPLETED handler retains
             * ownership of the session destruction path. */
            Log.w(TAG, "CallSession.terminate reason=" + reason
                    + " — suppressed (SRVCC HO in-flight)");
            return;
        }
        Log.i(TAG, "CallSession.terminate reason=" + reason);
        try {
            HamelinPortsSipStack.endCall();
        } catch (Exception e) {
            Log.e(TAG, "endCall failed", e);
        }
        cleanup();
    }

    /**
     * SRVCC Phase I.3 — silent session teardown for
     * {@code SRVCC_STATE_HANDOVER_COMPLETED}. Releases media, closes
     * sockets, and surfaces a {@link ImsReasonInfo#CODE_LOCAL_HO_NOT_FEASIBLE}
     * disposition to the framework, but <em>never</em> touches the
     * wire: no BYE to P-CSCF. The CS leg already carries voice and
     * the SCC-AS has released the IMS-side leg via Session Transfer;
     * any UE-originated BYE here risks tearing down the live CS call
     * or confusing IMS state. Intended to be called by
     * {@link HamelinPortsMmTelFeature#onSrvccCompleted()} exactly once.
     */
    void srvccSilentTerminate() {
        Log.i(TAG, "CallSession.srvccSilentTerminate — no BYE on wire");
        cleanup();
        if (mListener != null) {
            /* CODE_LOCAL_HO_NOT_FEASIBLE (149) is the closest standard
             * AOSP disposition for "call didn't continue on this
             * stack"; the framework's ImsPhoneCallTracker has already
             * re-parented the Connection to GsmCdmaCallTracker by the
             * time this fires, so the exact code is secondary — what
             * matters is that we do fire callSessionTerminated so our
             * MmTelFeature.mActiveCall clears and any lingering state
             * machine reaches a terminal sink. */
            try {
                mListener.callSessionTerminated(new ImsReasonInfo(
                        ImsReasonInfo.CODE_LOCAL_HO_NOT_FEASIBLE, 0,
                        "SRVCC_COMPLETED"));
            } catch (Throwable t) {
                Log.w(TAG, "callSessionTerminated(SRVCC) failed", t);
            }
        }
    }

    /** One-line SRVCC diagnostic snapshot — appended to the tagged log
     *  event by {@link HamelinPortsMmTelFeature}. Includes only fields we
     *  can get without new JNI bridges: call-age, RTP endpoints, codec,
     *  SRVCC flag. Call-ID / dialog tags / CSeq would need a new
     *  HamelinPortsSipStack native accessor — TODO when we bring down a
     *  real SRVCC capture that shows the need. */
    String srvccDiagSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("callee=").append(mCalleeNumber != null ? mCalleeNumber : "null");
        sb.append(" rtp_local_port=").append(mRtpPort);
        sb.append(" rtcp_local_port=").append(mRtcpPort);
        RemoteAudio r = mRemoteAudio;
        if (r != null) {
            sb.append(" rtp_remote=").append(r.ip).append(':').append(r.rtpPort);
            sb.append(" rtcp_remote_port=").append(r.rtcpPort);
            sb.append(" pt=").append(r.payloadType);
            sb.append(" codec=").append(r.codecName).append('/').append(r.clockRate);
        } else {
            sb.append(" rtp_remote=unanswered");
        }
        sb.append(" srvcc_started=").append(mSrvccStarted);
        sb.append(" has_ims_media=").append(mImsMedia != null);
        sb.append(" has_ims_video=").append(mImsMediaVideo != null);
        sb.append(" is_video=").append(isVideoCall(mProfile));
        sb.append(" remote_video_accepted=").append(mRemoteVideo != null);
        long s = mCallStartNs;
        if (s > 0) {
            sb.append(" call_age_ms=").append((System.nanoTime() - s) / 1_000_000L);
        } else {
            sb.append(" call_age_ms=pre-connect");
        }
        return sb.toString();
    }

    private void doOutgoingCall(String callee) throws Exception {
        mCalleeNumber = callee.replaceAll("[^0-9+]", "");

        // Allocate the local RTP port. The socket must be bound to an
        // explicit global address (not the wildcard ::) so that
        // getLocalAddress() returns something we can put in the SDP
        // c= line — the network sends RTP to whatever address we
        // advertise, and "::" is not routable (results in RX=0 and
        // the network tearing down the call on RTP-inactivity).
        Network imsNet = mRegController.getImsNetwork();
        InetAddress bindAddr = pickImsLocalIpv6(imsNet);
        if (bindAddr == null) {
            Log.e(TAG, "no IMS-PDN global IPv6 to bind RTP to");
            if (mListener != null) {
                mListener.callSessionInitiatedFailed(new android.telephony.ims.ImsReasonInfo(
                        android.telephony.ims.ImsReasonInfo.CODE_LOCAL_INTERNAL_ERROR, 0));
            }
            return;
        }
        mRtpSocket = new DatagramSocket(new InetSocketAddress(bindAddr, 0));
        mRtcpSocket = new DatagramSocket(new InetSocketAddress(bindAddr, 0));
        if (imsNet != null) {
            imsNet.bindSocket(mRtpSocket);
            imsNet.bindSocket(mRtcpSocket);
        }
        mRtpPort = mRtpSocket.getLocalPort();
        mRtcpPort = mRtcpSocket.getLocalPort();
        Log.i(TAG, "RTP/RTCP ports allocated: " + mRtpPort + "/" + mRtcpPort
                + " bind=" + bindAddr.getHostAddress());

        if (isVideoCall(mProfile)) {
            mRtpSocketVideo  = new DatagramSocket(new InetSocketAddress(bindAddr, 0));
            mRtcpSocketVideo = new DatagramSocket(new InetSocketAddress(bindAddr, 0));
            if (imsNet != null) {
                imsNet.bindSocket(mRtpSocketVideo);
                imsNet.bindSocket(mRtcpSocketVideo);
            }
            mRtpPortVideo  = mRtpSocketVideo.getLocalPort();
            mRtcpPortVideo = mRtcpSocketVideo.getLocalPort();
            Log.i(TAG, "Video RTP/RTCP ports allocated: "
                    + mRtpPortVideo + "/" + mRtcpPortVideo);
        }

        String localIp = stripScope(bindAddr.getHostAddress());

        // Build the Request-URI in NATIONAL form + phone-context.
        // The carrier host is the public-facing domain
        // (P-Associated-URI host part) — falls back to the IMS home
        // network domain only as a last resort.
        String carrierHost = mRegController.getPublicDomain();
        if (carrierHost == null || carrierHost.isEmpty()) {
            Log.w(TAG, "no carrier public domain — REGISTER did not yet succeed?");
            if (mListener != null) {
                mListener.callSessionInitiatedFailed(new android.telephony.ims.ImsReasonInfo(
                        android.telephony.ims.ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED, 0));
            }
            return;
        }
        String calleeUri = buildCalleeUri(callee, carrierHost,
                                          mRegController.getCountryIso());

        // Refresh the PANI cache immediately before the INVITE so the
        // decorator stamps the right access-type for the current PDN.
        // Cellular: 3GPP-E-UTRAN-FDD + utran-cell-id-3gpp from the LTE cell.
        // Wi-Fi Calling: IEEE-802.11 + i-wlan-node-id from the AP BSSID.
        SipClient.refreshPaniForOutbound(
                mRegController.getContext(), mRegController.getLastBoundIface());

        // Wire ourselves as the call-session listener.
        HamelinPortsSipStack.setCallSessionListener(new CallSessionListener() {
            @Override public void onProvisional(int code, String reason) {
                Log.i(TAG, "call onProvisional: " + code + " " + reason);
                if (code == 180 || code == 183) {
                    if (mListener != null) {
                        mListener.callSessionProgressing(new ImsStreamMediaProfile());
                    }
                }
            }
            @Override public void onAnswer(String remoteIp, int remoteRtpPort,
                                           int remoteRtcpPort,
                                           int pt, int clockRate,
                                           String codecName, String fmtp) {
                Log.i(TAG, "call onAnswer (audio): " + remoteIp
                        + " rtp=" + remoteRtpPort + " rtcp=" + remoteRtcpPort
                        + " pt=" + pt + " " + codecName + "/" + clockRate
                        + " fmtp=[" + fmtp + "]");
                boolean isReinvite = (mImsMedia != null);
                mRemoteAudio = new RemoteAudio(remoteIp, remoteRtpPort, remoteRtcpPort,
                                               pt, clockRate,
                                               codecName, fmtp);
                /* Stamp the negotiated codec onto the profile so the
                 * dialer's "HD" / "HD+" call badge renders correctly
                 * once callSessionInitiated fires from onConnected. */
                ImsStreamMediaProfile media = mProfile.getMediaProfile();
                if (media != null) {
                    media.mAudioQuality = audioQualityFor(codecName, clockRate);
                }
                /* C.4 downgrade path: re-INVITE 200 OK with no video
                 * m-line (or port=0) arrives — audio onAnswer fires
                 * but onAnswerVideo does not. Clear mRemoteVideo and
                 * run the apply step so we tear down the video
                 * imsmedia session. If it's the initial answer or
                 * the reinvite accepted video, onAnswerVideo above
                 * will (re-)set mRemoteVideo and call apply itself. */
                if (isReinvite && mPendingReinviteWantVideo == false) {
                    mRemoteVideo = null;
                    applyReinviteAnswer();
                }
            }
            @Override public void onRemoteReinvite(
                    String audioIp, int audioPort, int audioRtcpPort,
                    int audioPt, int audioRate, String audioCodec, String audioFmtp,
                    String videoIp, int videoPort, int videoRtcpPort,
                    int videoPt, int videoRate, String videoCodec, String videoFmtp) {
                handleRemoteReinvite(audioIp, audioPort, audioRtcpPort,
                        audioPt, audioRate, audioCodec, audioFmtp,
                        videoIp, videoPort, videoRtcpPort,
                        videoPt, videoRate, videoCodec, videoFmtp);
            }
            @Override public void onAnswerVideo(String remoteIp, int remoteRtpPort,
                                                int remoteRtcpPort,
                                                int pt, int clockRate,
                                                String codecName, String fmtp) {
                Log.i(TAG, "call onAnswer (video ACCEPTED): " + remoteIp
                        + " rtp=" + remoteRtpPort + " rtcp=" + remoteRtcpPort
                        + " pt=" + pt + " " + codecName + "/" + clockRate
                        + " fmtp=[" + fmtp + "]");
                mRemoteVideo = new RemoteVideo(remoteIp, remoteRtpPort, remoteRtcpPort,
                                               pt, clockRate,
                                               codecName, fmtp);
                /* C.4 — if a media session is already running this is
                 * a re-INVITE 200 OK; start / keep / swap the video
                 * session to match. Initial-INVITE answers are handled
                 * by the onConnected → startMediaEngine path. */
                if (mImsMedia != null) applyReinviteAnswer();
            }
            @Override public void onConnected(int code, String reason) {
                Log.i(TAG, "call onConnected: " + code);
                mCallStartNs = System.nanoTime();
                if (mListener != null) {
                    mListener.callSessionInitiated(mProfile);
                }
                startMediaEngine();
            }
            @Override public void onTerminated(int reasonCode, String reason) {
                Log.i(TAG, "call onTerminated: " + reasonCode + " " + reason);
                cleanup();
                if (mListener != null) {
                    mListener.callSessionTerminated(new ImsReasonInfo(
                            ImsReasonInfo.CODE_USER_TERMINATED, reasonCode, reason));
                }
            }
            @Override public void onFailure(int code, String reason) {
                Log.w(TAG, "call onFailure: " + code + " " + reason);
                cleanup();
                if (mListener != null) {
                    mListener.callSessionInitiatedFailed(new ImsReasonInfo(
                            ImsReasonInfo.CODE_SIP_TEMPRARILY_UNAVAILABLE, code, reason));
                }
            }
        });

        String sdp = buildSdp(localIp, mRtpPort, mRtcpPort,
                isVideoCall(mProfile) ? mRtpPortVideo  : 0,
                isVideoCall(mProfile) ? mRtcpPortVideo : 0,
                videoDirectionAttr(mProfile));
        Log.i(TAG, "---> INVITE " + calleeUri
                + (isVideoCall(mProfile) ? " [+video]" : ""));
        boolean queued = HamelinPortsSipStack.startCall(calleeUri, sdp);
        if (!queued && mListener != null) {
            mListener.callSessionInitiatedFailed(new ImsReasonInfo(
                    ImsReasonInfo.CODE_LOCAL_INTERNAL_ERROR, 0, "queue failed"));
        }
    }

    private void cleanup() {
        if (mImsMedia != null) {
            mImsMedia.stop();
            mImsMedia = null;
        }
        if (mImsMediaVideo != null) {
            mImsMediaVideo.stop();
            mImsMediaVideo = null;
        }
        if (mRtpSocket != null) {
            mRtpSocket.close();
            mRtpSocket = null;
        }
        if (mRtcpSocket != null) {
            mRtcpSocket.close();
            mRtcpSocket = null;
        }
        if (mRtpSocketVideo != null) {
            mRtpSocketVideo.close();
            mRtpSocketVideo = null;
        }
        if (mRtcpSocketVideo != null) {
            mRtcpSocketVideo.close();
            mRtcpSocketVideo = null;
        }
        // Release any communication-device pin we installed in
        // applyCommunicationRoute. Per AudioManager docs, the selection
        // stays active for the requesting process until cleared or the
        // process dies — we don't want a hung-up call to keep
        // routing the system's notification beeps over BT SCO.
        try {
            android.media.AudioManager am = mRegController.getContext()
                    .getSystemService(android.media.AudioManager.class);
            if (am != null) {
                am.clearCommunicationDevice();
            }
        } catch (Exception e) {
            Log.w(TAG, "MO clearCommunicationDevice failed", e);
        }
        /* Tell the MmTelFeature this session is gone so its
         * mActiveCall reference clears immediately — without this the
         * next createCallSession sees a still-live "prior" pointer
         * and re-runs terminate() on a dead session. */
        fireGoneOnce();
    }

    /**
     * Self-managed VoIP integration: AAudio streams opened by
     * libimsmedia don't auto-follow Telecom's BluetoothHeadset
     * legacy SCO routing — they bind to the system default unless
     * an explicit setCommunicationDevice has been issued. Mirror
     * the route the user selected in the dialer by pinning the
     * matching device before the imsmedia session opens its streams.
     *
     * Selection priority:
     *   - Honor any existing setCommunicationDevice (e.g. a custom
     *     ConnectionService running on a build with sco_managed_by_audio
     *     enabled, or a third-party app that pinned a route)
     *   - Else prefer a connected BT SCO / BLE headset
     *   - Else leave the system default (earpiece/speaker)
     *
     * @param tag log prefix ("MO" or "MT") so call-flow direction
     *            is visible in logcat without splitting the helper.
     */
    private void applyCommunicationRoute(String tag) {
        try {
            android.media.AudioManager am = mRegController.getContext()
                    .getSystemService(android.media.AudioManager.class);
            if (am == null) return;

            // Don't early-out on getCommunicationDevice() != null. Telecom
            // pins the device on every audio-route transition (BT_DEVICE_REMOVED
            // → earpiece, BT_DEVICE_ADDED → bt_sco_hs, etc.) but the value
            // can be stale: e.g. Telecom set earpiece on a transient BT
            // disconnect ~hours ago and never updated when BT reconnected.
            // We scan the *currently available* comm devices and bind to BT
            // if the user's headset is connected — matches Telecom's
            // CallAudioRouteController's `route=BLUETOOTH` state, which is
            // what the dialer UI reflects.
            android.media.AudioDeviceInfo current = am.getCommunicationDevice();
            // Diagnostic enumeration so we can tell apart "BT not paired"
            // from "BT registered but not advertised as a comm device" —
            // the latter means we need a different routing API.
            StringBuilder commDevList = new StringBuilder();
            android.media.AudioDeviceInfo target = null;
            for (android.media.AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (commDevList.length() > 0) commDevList.append(", ");
                commDevList.append("[type=").append(d.getType())
                        .append(" ").append(d.getProductName()).append("]");
                int t = d.getType();
                if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    target = d;
                    break;
                }
            }
            if (target == null) {
                StringBuilder outDevList = new StringBuilder();
                for (android.media.AudioDeviceInfo d : am.getDevices(
                        android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (outDevList.length() > 0) outDevList.append(", ");
                    outDevList.append("[type=").append(d.getType())
                            .append(" ").append(d.getProductName()).append("]");
                }
                Log.i(TAG, tag + " no BT in comm-devices list; current="
                        + (current == null ? "null" : "type=" + current.getType()
                                + " " + current.getProductName())
                        + "; comm-devices=" + commDevList
                        + "; output-devices=" + outDevList);
                return;
            }
            if (current != null && current.getId() == target.getId()) {
                Log.i(TAG, tag + " comm device already on BT: type=" + current.getType()
                        + " " + current.getProductName());
                return;
            }
            boolean ok = am.setCommunicationDevice(target);
            Log.i(TAG, tag + " setCommunicationDevice(type=" + target.getType()
                    + " " + target.getProductName() + ") = " + ok
                    + " (was: " + (current == null ? "null"
                            : "type=" + current.getType()) + ")");
        } catch (Exception e) {
            Log.w(TAG, tag + " applyCommunicationRoute failed", e);
        }
    }

    /** Start the RTP media engine on the socket we already bound,
     *  using the audio parameters from the network's SDP answer.
     *  Only called once {@code onAnswer} populated {@code mRemoteAudio}. */
    private void startMediaEngine() {
        if (mImsMedia != null) return;
        if (mRemoteAudio == null) {
            Log.w(TAG, "onConnected without onAnswer — no remote SDP, skipping media");
            return;
        }
        if (mRtpSocket == null || mRtpSocket.isClosed()) {
            Log.w(TAG, "onConnected but RTP socket is closed");
            return;
        }
        // AudioManager mode is owned by Telecom: with
        // config_use_voip_mode_for_ims=true (HPS framework-overlay),
        // ImsPhoneConnection.audioModeIsVoip is true, Telecom enters
        // VoipCallFocusState directly on call activation, and the
        // global mode is MODE_IN_COMMUNICATION before this point.
        //
        // Bind the AAudio streams to the user's chosen communication
        // device. Telecom only calls setCommunicationDevice itself
        // when sco_managed_by_audio is enabled (a trunk_staging-only
        // aconfig flag in current AOSP — bp4a leaves it disabled and
        // there's no LOS/AOSP release config that flips it to ENABLED;
        // see project_x205_volte_audio_mode_test_pending memory). Per
        // Android's self-managed call guide, a VoIP app that opens its
        // own AAudio voice streams must call setCommunicationDevice
        // itself or the policy resolves the streams to the system
        // default (built-in earpiece/speaker on a tablet) regardless
        // of which device the user picked in the dialer.
        //
        // Order matters for BT_SCO. We start imsmedia first so its
        // AAudio output stream is already open & active when we pin BT
        // — AudioPolicy's setCommunicationDevice then re-routes a live
        // stream (audio HAL gets BT_SCO=on with PCM already flowing).
        // Stream-first / route-second avoids a ~500ms framework
        // dispatch window between eSCO acceptance and BT_SCO=on
        // reaching the HAL, in which some HFP headsets (Jabra Evolve2,
        // etc.) terminate the SCO link with HCI 0x13
        // REMOTE_USER_TERMINATED_CONNECTION because no PCM is flowing.
        try {
            mImsMedia = new HamelinPortsImsMediaSession(mRegController.getContext(),
                    mRtpSocket, mRtcpSocket,
                    mRemoteAudio.ip, mRemoteAudio.rtpPort, mRemoteAudio.rtcpPort,
                    mRemoteAudio.payloadType, mRemoteAudio.clockRate,
                    mRemoteAudio.codecName, mRemoteAudio.fmtp);
            mImsMedia.start();
        } catch (Exception e) {
            Log.e(TAG, "imsmedia audio start failed", e);
        }
        applyCommunicationRoute("MO");
        /* C.2 — open a parallel SESSION_TYPE_VIDEO imsmedia session
         * when the peer accepted our video offer. Without surfaces
         * (set up in C.3 via VideoCallProvider) this just exercises
         * the RTP graph — encoder input has no frames, decoder
         * output goes to /dev/null. Still lets us verify on the wire
         * that H.264 RTP packets flow bidirectionally. */
        if (mRemoteVideo != null && mRtpSocketVideo != null
                && !mRtpSocketVideo.isClosed()) {
            try {
                int dir = mediaDirectionFor(mProfile);
                mImsMediaVideo = new HamelinPortsImsMediaVideoSession(
                        mRegController.getContext(),
                        mRtpSocketVideo, mRtcpSocketVideo,
                        mRemoteVideo.ip, mRemoteVideo.rtpPort, mRemoteVideo.rtcpPort,
                        mRemoteVideo.payloadType, mRemoteVideo.clockRate,
                        mRemoteVideo.codecName, mRemoteVideo.fmtp,
                        dir);
                mImsMediaVideo.start();
                /* Apply any surfaces Telecom delivered before the
                 * session started (provider wiring happens earlier
                 * than onConnected). */
                if (mPendingPreviewSurface != null)
                    mImsMediaVideo.setPreviewSurface(mPendingPreviewSurface);
                if (mPendingDisplaySurface != null)
                    mImsMediaVideo.setDisplaySurface(mPendingDisplaySurface);
                Log.i(TAG, "MO imsmedia video session started (preview="
                        + (mPendingPreviewSurface != null)
                        + ", display=" + (mPendingDisplaySurface != null) + ")");
            } catch (Exception e) {
                Log.e(TAG, "imsmedia video start failed", e);
            }
        } else if (isVideoCall(mProfile)) {
            Log.w(TAG, "video offered but no accepted video m-line in answer"
                    + " (mRemoteVideo=" + mRemoteVideo
                    + ", socket="     + mRtpSocketVideo + ")");
        }
    }

    /** Phase C.4.3 — remote sent a re-INVITE with a new SDP offer.
     *  Mirror it in our answer: accept what was offered, negotiate
     *  the stream change locally, and notify Telecom so the dialer
     *  UI updates. No user prompt — RFC 6337 permits automatic
     *  acceptance of mid-call media renegotiation. */
    private void handleRemoteReinvite(
            String audioIp, int audioPort, int audioRtcpPort,
            int audioPt, int audioRate, String audioCodec, String audioFmtp,
            String videoIp, int videoPort, int videoRtcpPort,
            int videoPt, int videoRate, String videoCodec, String videoFmtp) {
        boolean wantVideo = videoPort > 0;
        boolean haveVideo = mImsMediaVideo != null;
        Log.i(TAG, "MO handleRemoteReinvite wantVideo=" + wantVideo
                + " haveVideo=" + haveVideo
                + " audio=" + audioIp + ":" + audioPort + " " + audioCodec
                + " video=" + videoIp + ":" + videoPort + " " + videoCodec);

        /* Update remote endpoint records. */
        mRemoteAudio = new RemoteAudio(audioIp, audioPort, audioRtcpPort,
                audioPt, audioRate, audioCodec, audioFmtp);
        mRemoteVideo = wantVideo
                ? new RemoteVideo(videoIp, videoPort, videoRtcpPort,
                        videoPt, videoRate, videoCodec, videoFmtp)
                : null;

        /* Allocate our own video sockets if the upgrade requires
         * them and we haven't yet. */
        if (wantVideo && mRtpSocketVideo == null) {
            try {
                allocateVideoSocketsOnDemand();
            } catch (Exception e) {
                Log.e(TAG, "video socket allocation for remote upgrade failed", e);
                return;
            }
        }

        /* Build and send the SDP answer. Mirror direction (sendrecv
         * default unless the remote indicated otherwise via fmtp —
         * which we don't parse today). */
        String localIp = mRtpSocket != null
                ? stripScope(mRtpSocket.getLocalAddress().getHostAddress())
                : null;
        if (localIp == null) {
            Log.w(TAG, "remote re-INVITE: no local IP, cannot answer");
            return;
        }
        String sdpAnswer = buildSdp(localIp, mRtpPort, mRtcpPort,
                wantVideo ? mRtpPortVideo  : 0,
                wantVideo ? mRtcpPortVideo : 0,
                "sendrecv");
        boolean queued = HamelinPortsSipStack.provideReinviteAnswer(sdpAnswer);
        Log.i(TAG, "remote re-INVITE answer queued=" + queued);
        if (!queued) return;

        /* Apply the local media change. Re-uses the same
         * upgrade/downgrade logic as the MO-initiated path — just
         * with our "wantVideo" guess replacing the Telecom hint. */
        mPendingReinviteWantVideo = wantVideo;
        applyReinviteAnswer();

        /* Nudge Telecom so the dialer reflects the new call type. */
        if (mVideoProvider != null) {
            int state = wantVideo
                    ? android.telecom.VideoProfile.STATE_BIDIRECTIONAL
                    : android.telecom.VideoProfile.STATE_AUDIO_ONLY;
            android.telecom.VideoProfile vp = new android.telecom.VideoProfile(state);
            mVideoProvider.receiveSessionModifyRequest(vp);
        }
    }

    /** Phase C.4 — build a fresh SDP offer based on a target video
     *  state coming from Telecom's session-modify request, then
     *  trigger a re-INVITE via reSIProcate. The response arrives via
     *  {@link CallSessionListener#onAnswer} (audio) and
     *  {@link CallSessionListener#onAnswerVideo} (if video accepted);
     *  our onAnswer handler detects that a media session is already
     *  up and routes the change through
     *  {@link #applyReinviteAnswer}. */
    private void handleSessionModifyRequest(android.telecom.VideoProfile from,
                                            android.telecom.VideoProfile to) {
        int toState = to != null ? to.getVideoState()
                                  : android.telecom.VideoProfile.STATE_AUDIO_ONLY;
        int fromState = from != null ? from.getVideoState()
                                      : android.telecom.VideoProfile.STATE_AUDIO_ONLY;
        boolean wantVideo = toState != android.telecom.VideoProfile.STATE_AUDIO_ONLY;
        boolean haveVideo = mImsMediaVideo != null;
        Log.i(TAG, "MO handleSessionModifyRequest "
                + videoStateName(fromState) + " → " + videoStateName(toState)
                + " (wantVideo=" + wantVideo + ", haveVideo=" + haveVideo + ")");

        /* Allocate video sockets on upgrade — they weren't allocated
         * at initial-INVITE time if the call started as VOICE. */
        if (wantVideo && mRtpSocketVideo == null) {
            try {
                allocateVideoSocketsOnDemand();
            } catch (Exception e) {
                Log.e(TAG, "video socket allocation for upgrade failed", e);
                return;
            }
        }

        /* Rebuild SDP: m=video with real port for upgrade/continue,
         * port=0 for downgrade (RFC 3264 §6.1). */
        String localIp = mRtpSocket != null
                ? stripScope(mRtpSocket.getLocalAddress().getHostAddress())
                : null;
        if (localIp == null) {
            Log.w(TAG, "MO reinvite: no local IP (socket gone), aborting");
            return;
        }
        String dir = videoDirectionForState(toState);
        int videoPort  = wantVideo ? mRtpPortVideo  : 0;
        int videoRtcp  = wantVideo ? mRtcpPortVideo : 0;
        String sdp = buildSdp(localIp, mRtpPort, mRtcpPort,
                              videoPort, videoRtcp, dir);
        Log.i(TAG, "MO reinvite SDP built, wantVideo=" + wantVideo
                + ", dir=" + dir);
        mPendingReinviteWantVideo = wantVideo;
        boolean queued = HamelinPortsSipStack.reinvite(sdp);
        Log.i(TAG, "MO reinvite queued=" + queued);
        if (!queued && mVideoProvider != null) {
            /* Tell Telecom the change failed — keep user in current state. */
            mVideoProvider.receiveSessionModifyResponse(
                    android.telecom.Connection.VideoProvider.SESSION_MODIFY_REQUEST_FAIL,
                    from, from);
        }
    }

    /** Track whether we are expecting a re-INVITE 200 OK with video
     *  accepted. Cleared by {@link #applyReinviteAnswer}. */
    private volatile boolean mPendingReinviteWantVideo;

    /** Called from {@code onAnswer(Video)} when a re-INVITE's 200 OK
     *  arrives (detected by {@code mImsMedia != null} at onAnswer time
     *  — initial answer would have {@code mImsMedia == null}). Starts
     *  or stops the video imsmedia session to match the new SDP. */
    private void applyReinviteAnswer() {
        if (mPendingReinviteWantVideo && mRemoteVideo != null
                && mImsMediaVideo == null) {
            /* Upgrade — start the video session now. */
            try {
                int dir = mediaDirectionFor(mProfile);
                mImsMediaVideo = new HamelinPortsImsMediaVideoSession(
                        mRegController.getContext(),
                        mRtpSocketVideo, mRtcpSocketVideo,
                        mRemoteVideo.ip, mRemoteVideo.rtpPort, mRemoteVideo.rtcpPort,
                        mRemoteVideo.payloadType, mRemoteVideo.clockRate,
                        mRemoteVideo.codecName, mRemoteVideo.fmtp,
                        dir);
                mImsMediaVideo.start();
                if (mPendingPreviewSurface != null)
                    mImsMediaVideo.setPreviewSurface(mPendingPreviewSurface);
                if (mPendingDisplaySurface != null)
                    mImsMediaVideo.setDisplaySurface(mPendingDisplaySurface);
                Log.i(TAG, "MO reinvite upgrade — imsmedia video session started");
            } catch (Exception e) {
                Log.e(TAG, "MO reinvite upgrade video start failed", e);
            }
        } else if (!mPendingReinviteWantVideo && mImsMediaVideo != null) {
            /* Downgrade — stop the video session. */
            try {
                mImsMediaVideo.stop();
                mImsMediaVideo = null;
                mRemoteVideo = null;
                Log.i(TAG, "MO reinvite downgrade — imsmedia video session stopped");
            } catch (Exception e) {
                Log.w(TAG, "MO reinvite downgrade stop failed", e);
            }
        }
        /* Tell Telecom whether the modify succeeded so the dialer UI
         * can update. Use the current observed state as the response
         * profile — simplest accurate reporting. */
        if (mVideoProvider != null) {
            int state;
            if (mRemoteVideo != null) state = android.telecom.VideoProfile.STATE_BIDIRECTIONAL;
            else                       state = android.telecom.VideoProfile.STATE_AUDIO_ONLY;
            android.telecom.VideoProfile vp = new android.telecom.VideoProfile(state);
            mVideoProvider.receiveSessionModifyResponse(
                    android.telecom.Connection.VideoProvider.SESSION_MODIFY_REQUEST_SUCCESS,
                    vp, vp);
        }
        mPendingReinviteWantVideo = false;
    }

    private void allocateVideoSocketsOnDemand() throws Exception {
        Network imsNet = mRegController.getImsNetwork();
        InetAddress bindAddr = pickImsLocalIpv6(imsNet);
        if (bindAddr == null) throw new IllegalStateException(
                "no IMS-PDN IPv6 for on-demand video bind");
        mRtpSocketVideo  = new DatagramSocket(new InetSocketAddress(bindAddr, 0));
        mRtcpSocketVideo = new DatagramSocket(new InetSocketAddress(bindAddr, 0));
        if (imsNet != null) {
            imsNet.bindSocket(mRtpSocketVideo);
            imsNet.bindSocket(mRtcpSocketVideo);
        }
        mRtpPortVideo  = mRtpSocketVideo.getLocalPort();
        mRtcpPortVideo = mRtcpSocketVideo.getLocalPort();
        Log.i(TAG, "MO on-demand video RTP/RTCP allocated: "
                + mRtpPortVideo + "/" + mRtcpPortVideo);
    }

    private static String videoDirectionForState(int state) {
        switch (state) {
            case android.telecom.VideoProfile.STATE_BIDIRECTIONAL: return "sendrecv";
            case android.telecom.VideoProfile.STATE_TX_ENABLED:    return "sendonly";
            case android.telecom.VideoProfile.STATE_RX_ENABLED:    return "recvonly";
            default:                                                return "inactive";
        }
    }

    private static String videoStateName(int state) {
        switch (state) {
            case android.telecom.VideoProfile.STATE_AUDIO_ONLY:    return "AUDIO_ONLY";
            case android.telecom.VideoProfile.STATE_BIDIRECTIONAL: return "BIDIRECTIONAL";
            case android.telecom.VideoProfile.STATE_TX_ENABLED:    return "TX_ENABLED";
            case android.telecom.VideoProfile.STATE_RX_ENABLED:    return "RX_ENABLED";
            case android.telecom.VideoProfile.STATE_PAUSED:        return "PAUSED";
            default:                                                return "?(" + state + ")";
        }
    }

    /** Map a negotiated audio codec to the corresponding
     *  {@link ImsStreamMediaProfile} {@code AUDIO_QUALITY_*} constant.
     *  The dialer's "HD" / "HD+" call badge is gated on this — without
     *  a non-NONE value the Telecom UI never decorates the active call
     *  even when the wire is AMR-WB or EVS. Defaults to {@code AMR}
     *  (8 kHz narrowband) so the badge never falsely promotes; codecs
     *  we don't recognise stay safe-narrowband. */
    static int audioQualityFor(String codecName, int clockRate) {
        if (codecName == null) return ImsStreamMediaProfile.AUDIO_QUALITY_AMR;
        String c = codecName.trim();
        if (c.equalsIgnoreCase("AMR-WB") || c.equalsIgnoreCase("AMRWB")) {
            return ImsStreamMediaProfile.AUDIO_QUALITY_AMR_WB;
        }
        if (c.equalsIgnoreCase("AMR")) return ImsStreamMediaProfile.AUDIO_QUALITY_AMR;
        if (c.equalsIgnoreCase("EVS")) {
            // EVS uses sample rate to distinguish bandwidths.
            switch (clockRate) {
                case 8000:  return ImsStreamMediaProfile.AUDIO_QUALITY_EVS_NB;
                case 16000: return ImsStreamMediaProfile.AUDIO_QUALITY_EVS_WB;
                case 32000: return ImsStreamMediaProfile.AUDIO_QUALITY_EVS_SWB;
                case 48000: return ImsStreamMediaProfile.AUDIO_QUALITY_EVS_FB;
                default:    return ImsStreamMediaProfile.AUDIO_QUALITY_EVS_WB;
            }
        }
        return ImsStreamMediaProfile.AUDIO_QUALITY_AMR;
    }

    /** Translate the profile's call type to imsmedia RtpConfig
     *  direction constant. Audio is always SENDRECV; video mirrors
     *  the VT_TX / VT_RX asymmetry. */
    private static int mediaDirectionFor(ImsCallProfile profile) {
        if (profile == null) return android.telephony.imsmedia.RtpConfig.MEDIA_DIRECTION_SEND_RECEIVE;
        switch (profile.getCallType()) {
            case ImsCallProfile.CALL_TYPE_VT_TX:
                return android.telephony.imsmedia.RtpConfig.MEDIA_DIRECTION_SEND_ONLY;
            case ImsCallProfile.CALL_TYPE_VT_RX:
                return android.telephony.imsmedia.RtpConfig.MEDIA_DIRECTION_RECEIVE_ONLY;
            default:
                return android.telephony.imsmedia.RtpConfig.MEDIA_DIRECTION_SEND_RECEIVE;
        }
    }

    private static String buildCalleeUri(String callee, String carrierHost, String countryIso) {
        if (callee.startsWith("sip:") || callee.startsWith("tel:")) {
            return callee;
        }
        String nationalForm = SipClient.toNationalForm(callee, countryIso);
        return "sip:" + nationalForm
                + ";phone-context=" + carrierHost
                + "@" + carrierHost
                + ";user=phone";
    }

    /** True if {@code profile} is any of the video call types —
     *  anything except CALL_TYPE_VOICE (2). */
    private static boolean isVideoCall(ImsCallProfile profile) {
        if (profile == null) return false;
        int t = profile.getCallType();
        return t == ImsCallProfile.CALL_TYPE_VOICE_N_VIDEO
            || t == ImsCallProfile.CALL_TYPE_VT
            || t == ImsCallProfile.CALL_TYPE_VT_TX
            || t == ImsCallProfile.CALL_TYPE_VT_RX
            || t == ImsCallProfile.CALL_TYPE_VT_NODIR;
    }

    /** SDP direction attribute for the {@code m=video} block, derived
     *  from the profile's call type. Audio is always {@code sendrecv}. */
    private static String videoDirectionAttr(ImsCallProfile profile) {
        if (profile == null) return "sendrecv";
        switch (profile.getCallType()) {
            case ImsCallProfile.CALL_TYPE_VT_TX: return "sendonly";
            case ImsCallProfile.CALL_TYPE_VT_RX: return "recvonly";
            default: return "sendrecv";     /* VT, VOICE_N_VIDEO, VT_NODIR */
        }
    }

    /** Build the SDP offer. {@code videoRtpPort > 0} triggers the
     *  {@code m=video} block — H.264 Baseline packetization-mode 1
     *  (the carrier-interoperable default per GSMA PRD IR.94). */
    private static String buildSdp(String localIp,
                                   int audioRtpPort, int audioRtcpPort,
                                   int videoRtpPort, int videoRtcpPort,
                                   String videoDir) {
        String addrType = localIp.contains(":") ? "IP6" : "IP4";
        StringBuilder sb = new StringBuilder();
        sb.append("v=0\r\n");
        sb.append("o=- 1 1 IN ").append(addrType).append(" ").append(localIp).append("\r\n");
        sb.append("s=-\r\n");
        sb.append("c=IN ").append(addrType).append(" ").append(localIp).append("\r\n");
        sb.append("t=0 0\r\n");
        /* Audio m-line */
        sb.append("m=audio ").append(audioRtpPort).append(" RTP/AVP 96 97\r\n");
        /* a=rtcp is only required when RTCP port != RTP + 1, per RFC
         * 3605 §2.1. Our ephemeral allocation makes no guarantee the
         * ports are consecutive, so advertise explicitly to avoid
         * Mavenir sending RTCP to RTP+1 and getting ICMPv6 port
         * unreachable. */
        sb.append("a=rtcp:").append(audioRtcpPort).append("\r\n");
        sb.append("a=rtpmap:96 AMR-WB/16000/1\r\n");
        sb.append("a=fmtp:96 mode-set=0,1,2; octet-align=1\r\n");
        sb.append("a=rtpmap:97 telephone-event/16000\r\n");
        sb.append("a=fmtp:97 0-15\r\n");
        sb.append("a=sendrecv\r\n");
        sb.append("a=ptime:20\r\n");
        sb.append("a=maxptime:240\r\n");
        /* Video m-line (only when a video port was allocated). PT=99
         * H.264 Constrained Baseline (profile-level-id=42e01f: the
         * 'e' byte carries constraint_set1_flag which Mavenir's TAS
         * requires — a plain Baseline 42801f got port=0 back in
         * testing). RFC 4585 rtcp-fb is mandatory per GSMA PRD IR.94
         * §6.2.1 for MMTel-video interop; without it Mavenir also
         * strips the video line. */
        if (videoRtpPort > 0) {
            sb.append("m=video ").append(videoRtpPort).append(" RTP/AVP 99\r\n");
            sb.append("a=rtcp:").append(videoRtcpPort).append("\r\n");
            sb.append("a=rtpmap:99 H264/90000\r\n");
            sb.append("a=fmtp:99 packetization-mode=1;"
                    + " profile-level-id=42e01f;"
                    + " max-br=384; max-mbps=27600; max-fs=920;"
                    + " max-fr=30; max-rcmd-nalu-size=1400\r\n");
            /* RTP feedback — generic NACK + picture-loss indication
             * (RFC 4585 §6.3 / RFC 4585 §6.3.1). Required for the
             * decoder to request key-frames on packet loss. */
            sb.append("a=rtcp-fb:99 nack\r\n");
            sb.append("a=rtcp-fb:99 nack pli\r\n");
            /* Resolution hints per RFC 6236. 320x240 send, accept up
             * to 640x480 on recv — conservative defaults suitable
             * for MMTel-video on a 720p-class UE. */
            sb.append("a=imageattr:99 send [x=320,y=240]"
                    + " recv [x=[320:16:640],y=[240:16:480]]\r\n");
            sb.append("a=").append(videoDir).append("\r\n");
        }
        return sb.toString();
    }

    private static String stripScope(String ip) {
        int i = ip.indexOf('%');
        return i > 0 ? ip.substring(0, i) : ip;
    }

    /** Pick the IMS PDN's global IPv6 address so we can bind the RTP
     *  socket to it explicitly. A wildcard-bound socket reports
     *  {@code ::} from {@link DatagramSocket#getLocalAddress}, which
     *  the network would reject on the SDP c= line. */
    private InetAddress pickImsLocalIpv6(Network net) {
        if (net == null) return null;
        try {
            ConnectivityManager cm = mRegController.getContext()
                    .getSystemService(ConnectivityManager.class);
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet6Address
                        && !a.isLinkLocalAddress()
                        && !a.isLoopbackAddress()) {
                    return a;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "pickImsLocalIpv6 failed", e);
        }
        return null;
    }
}
