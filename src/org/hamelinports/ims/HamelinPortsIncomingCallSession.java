// SPDX-License-Identifier: Apache-2.0
package org.hamelinports.ims;

import android.content.Context;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSessionListener;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.util.Log;

import org.hamelinports.ims.sip.CallSessionListener;
import org.hamelinports.ims.sip.HamelinPortsSipStack;

import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * MT (incoming) IMS call session. Counterpart to {@link HamelinPortsCallSession}
 * (MO). Constructed by {@link HamelinPortsMmTelFeature} in response to the
 * native stack's {@code onIncomingInvite} callback, handed to the
 * telephony framework via
 * {@link android.telephony.ims.feature.MmTelFeature#notifyIncomingCall}.
 *
 * <p>State machine:
 * <ul>
 *   <li>Constructed → framework calls {@link #setListener} → fires
 *       180 Ringing on the wire so the caller hears ring-back
 *       ({@link HamelinPortsSipStack#progressRinging}).</li>
 *   <li>User answers → framework calls
 *       {@link #accept(int, ImsStreamMediaProfile)} → allocate local
 *       RTP/RTCP sockets, build SDP answer, invoke
 *       {@link HamelinPortsSipStack#acceptIncomingCall} → reSIProcate
 *       emits 200 OK with our SDP; remote ACKs; native
 *       {@code onConnected} fires → start media engine →
 *       {@link ImsCallSessionListener#callSessionInitiated}.</li>
 *   <li>User rejects / framework times out →
 *       {@link #reject(int)} → SIP 603/486 per reason code.</li>
 *   <li>Remote CANCEL before answer or BYE after → native
 *       {@code onTerminated} → we notify
 *       {@link ImsCallSessionListener#callSessionTerminated}.</li>
 * </ul>
 *
 * <p>Single-call-at-a-time: the session registers itself as the
 * CallSessionListener at {@code accept} time, same contract as MO.
 * Only one MT or MO is active at once; the {@code mActiveCall}
 * guard in {@link HamelinPortsMmTelFeature#createCallSession} plus the
 * native single-{@code mActive} handle enforce this.
 */
public class HamelinPortsIncomingCallSession extends ImsCallSessionImplBase {
    private static final String TAG = HamelinPortsImsService.TAG;

    private final ImsRegistrationController mRegController;
    private final ImsCallProfile mProfile;
    private final java.util.function.Consumer<HamelinPortsIncomingCallSession> mOnGone;
    private final String mCallId;
    private final String mFromUri;
    private final RemoteAudio mRemote;

    private ImsCallSessionListener mListener;
    private DatagramSocket mRtpSocket;
    private DatagramSocket mRtcpSocket;
    private int mRtpPort;
    private int mRtcpPort;
    private HamelinPortsImsMediaSession mImsMedia;
    private boolean mAccepted = false;
    private boolean mGoneFired = false;
    private int mSavedAudioMode = AudioManager.MODE_INVALID;

    /* Video state — populated by {@link #setRemoteVideo} when the
     *  incoming SDP offer included an m=video block. Sockets are
     *  allocated in {@link #doAccept}; the imsmedia video session is
     *  opened once remote ACKs our 200 OK. */
    private RemoteAudio mRemoteVideo;
    private DatagramSocket mRtpSocketVideo;
    private DatagramSocket mRtcpSocketVideo;
    private int mRtpPortVideo;
    private int mRtcpPortVideo;
    private HamelinPortsImsMediaVideoSession mImsMediaVideo;

    /* Framework's ImsCall.isAlive() returns false for State.INVALID (-1),
     * the default. ImsManager.takeCall() → isAlive() gate throws
     * "ImsCallSession is not alive" before Telecom is notified, so the
     * phone never rings. Start at NEGOTIATING because we hold a received
     * SDP offer awaiting local answer. */
    private volatile int mState = ImsCallSessionImplBase.State.NEGOTIATING;

    static final class RemoteAudio {
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

    HamelinPortsIncomingCallSession(ImsRegistrationController regController,
                               ImsCallProfile profile,
                               String callId, String fromUri,
                               RemoteAudio remote,
                               java.util.function.Consumer<HamelinPortsIncomingCallSession> onGone) {
        mRegController = regController;
        mProfile = profile;
        mCallId = callId;
        mFromUri = fromUri;
        mRemote = remote;
        mOnGone = onGone;
    }

    String getFromUri() { return mFromUri; }

    /** Called by {@link HamelinPortsMmTelFeature#onIncomingInviteVideo}
     *  when the incoming SDP offer included a video m-line. Must
     *  arrive BEFORE {@link #accept} so the SDP answer can mirror
     *  video properly. */
    void setRemoteVideo(RemoteAudio video) {
        mRemoteVideo = video;
        Log.i(TAG, "MT setRemoteVideo: " + video.ip + ":" + video.rtpPort
                + " pt=" + video.payloadType + " " + video.codecName);
    }

    /** AOSP {@link ImsCallSessionImplBase#getCallId()} returns null
     *  by default; the framework's ImsCall wrapper then renders the
     *  attachSession line as {@code callId:[UNINITIALIZED]} and
     *  ImsPhoneCallTracker silently drops the MT. We report the
     *  SIP Call-ID captured in {@code onIncomingInvite} so the
     *  framework can route the session end-to-end. */
    @Override
    public String getCallId() { return mCallId; }

    @Override
    public int getState() { return mState; }

    @Override
    public void setListener(ImsCallSessionListener listener) {
        mListener = listener;
        /* Fire 180 Ringing as soon as the framework has subscribed —
         * at that point the dialer has presented the incoming UI,
         * which is when IMS UE is supposed to alert the caller
         * (RFC 3261/3262 + TS 24.229 §5.1.3). Mavenir requires
         * 100rel which ReSIProcate handles; we wait for PRACK
         * before the framework's accept() call arrives. */
        if (mCallId != null) {
            boolean ok = HamelinPortsSipStack.progressRinging(mCallId);
            Log.i(TAG, "MT setListener: progressRinging ok=" + ok
                    + " callId=" + mCallId);
        }
    }

    @Override
    public ImsCallProfile getCallProfile() {
        return mProfile;
    }

    @Override
    public void accept(int callType, ImsStreamMediaProfile profile) {
        Log.i(TAG, "MT accept callType=" + callType + " callId=" + mCallId);
        if (mAccepted) return;
        mAccepted = true;
        mState = ImsCallSessionImplBase.State.ESTABLISHING;
        new Thread(() -> {
            try {
                doAccept();
            } catch (Exception e) {
                Log.e(TAG, "MT accept failed", e);
                fireGoneOnce();
                if (mListener != null) {
                    mListener.callSessionTerminated(new ImsReasonInfo(
                            ImsReasonInfo.CODE_LOCAL_INTERNAL_ERROR, 0, e.getMessage()));
                }
            }
        }, "HamelinPortsIms-mt").start();
    }

    @Override
    public void reject(int reason) {
        Log.i(TAG, "MT reject reason=" + reason + " callId=" + mCallId);
        mState = ImsCallSessionImplBase.State.TERMINATED;
        int sipCode = mapRejectReasonToSip(reason);
        if (mCallId != null) {
            HamelinPortsSipStack.rejectIncomingCall(mCallId, sipCode);
        }
        fireGoneOnce();
        if (mListener != null) {
            mListener.callSessionTerminated(new ImsReasonInfo(
                    ImsReasonInfo.CODE_USER_TERMINATED, sipCode, "MT rejected"));
        }
    }

    /** Called by {@link HamelinPortsMmTelFeature#onImsPdnLost} when the
     *  IMS bearer disappears during the MT call. Mirror MO semantics
     *  (see project_srvcc_not_wired memory): release media, fire
     *  {@code callSessionTerminated} with
     *  {@code CODE_LOCAL_NETWORK_NO_SERVICE}, skip the SIP BYE
     *  (sockets already dead). */
    void handleImsPdnLost() {
        if (mState == ImsCallSessionImplBase.State.TERMINATED) return;
        mState = ImsCallSessionImplBase.State.TERMINATED;
        Log.w(TAG, "MT handleImsPdnLost — terminating call locally");
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

    /** Called when reSIProcate signals the server dialog terminated
     *  before our {@link #accept} ran — remote CANCEL or equivalent.
     *  Fires {@code callSessionTerminated} so the framework stops
     *  ringing and Telecom removes the ringing connection.
     *
     *  Post-accept termination (BYE after 200 OK/ACK) is handled by
     *  the per-call {@link CallSessionListener} wired in
     *  {@link #doAccept}; firing {@code callSessionTerminated} again
     *  from here would be a redundant notification. */
    void handleRemoteCancel(int nativeReason) {
        if (mState == ImsCallSessionImplBase.State.TERMINATED) return;
        if (mAccepted) {
            Log.i(TAG, "MT handleRemoteCancel post-accept — deferring to"
                    + " CallSessionListener.onTerminated, nativeReason="
                    + nativeReason + " callId=" + mCallId);
            return;
        }
        mState = ImsCallSessionImplBase.State.TERMINATED;
        Log.i(TAG, "MT handleRemoteCancel nativeReason=" + nativeReason
                + " callId=" + mCallId);
        fireGoneOnce();
        if (mListener != null) {
            mListener.callSessionTerminated(new ImsReasonInfo(
                    ImsReasonInfo.CODE_SIP_REQUEST_CANCELLED,
                    487, "Remote canceled (native reason=" + nativeReason + ")"));
        }
    }

    @Override
    public void terminate(int reason) {
        Log.i(TAG, "MT terminate reason=" + reason + " callId=" + mCallId
                + " accepted=" + mAccepted);
        if (!mAccepted) {
            /* Pre-accept terminate = reject. Frameworks use this for
             * missed-call timeouts. */
            reject(reason);
            return;
        }
        /* Post-accept terminate = BYE on the MT server dialog. The
         * global endCall() acts on the MO {@code ClientInviteSession}
         * which is NotValid for MT → no BYE on the wire, and Mavenir
         * eventually times us out on RTCP silence (~8 s). Target this
         * dialog explicitly by Call-ID. */
        try {
            boolean sent = HamelinPortsSipStack.endCallByCallId(mCallId);
            Log.i(TAG, "MT BYE queued=" + sent + " callId=" + mCallId);
        } catch (Exception e) {
            Log.w(TAG, "endCallByCallId failed", e);
        }
        /* Fire callSessionTerminated synchronously so Telecom drops the
         * connection immediately — the SIP BYE goes in parallel but
         * the user-visible hangup UI transition shouldn't wait for the
         * network to round-trip. Mark state TERMINATED so the late
         * onTerminated upcall sees we already reported. */
        mState = ImsCallSessionImplBase.State.TERMINATED;
        if (mListener != null) {
            try {
                mListener.callSessionTerminated(new ImsReasonInfo(
                        ImsReasonInfo.CODE_USER_TERMINATED, 0,
                        "MT local hangup"));
            } catch (Exception e) {
                Log.w(TAG, "callSessionTerminated threw", e);
            }
        }
        cleanup();
    }

    private void doAccept() throws Exception {
        /* Allocate local RTP/RTCP on the IMS PDN's global IPv6.
         * Same pattern as HamelinPortsCallSession.doOutgoingCall — bind
         * explicitly (not :: wildcard) so the SDP c= line carries
         * a routable address. */
        Network imsNet = mRegController.getImsNetwork();
        InetAddress bindAddr = pickImsLocalIpv6(imsNet);
        if (bindAddr == null) {
            throw new IllegalStateException("no IMS-PDN global IPv6 for MT RTP bind");
        }
        mRtpSocket = new DatagramSocket(new InetSocketAddress(bindAddr, 0));
        mRtcpSocket = new DatagramSocket(new InetSocketAddress(bindAddr, 0));
        if (imsNet != null) {
            imsNet.bindSocket(mRtpSocket);
            imsNet.bindSocket(mRtcpSocket);
        }
        mRtpPort = mRtpSocket.getLocalPort();
        mRtcpPort = mRtcpSocket.getLocalPort();
        String localIp = stripScope(bindAddr.getHostAddress());
        Log.i(TAG, "MT RTP allocated: " + localIp + ":" + mRtpPort
                + " RTCP:" + mRtcpPort);

        /* Allocate a second pair for video when the incoming offer
         * had a video m-line. Sockets get owned by the video
         * imsmedia session on startMediaEngine; they close via
         * cleanup() alongside the audio sockets. */
        if (mRemoteVideo != null) {
            mRtpSocketVideo  = new DatagramSocket(new InetSocketAddress(bindAddr, 0));
            mRtcpSocketVideo = new DatagramSocket(new InetSocketAddress(bindAddr, 0));
            if (imsNet != null) {
                imsNet.bindSocket(mRtpSocketVideo);
                imsNet.bindSocket(mRtcpSocketVideo);
            }
            mRtpPortVideo  = mRtpSocketVideo.getLocalPort();
            mRtcpPortVideo = mRtcpSocketVideo.getLocalPort();
            Log.i(TAG, "MT video RTP allocated: " + mRtpPortVideo
                    + " RTCP:" + mRtcpPortVideo);
        }

        /* Hook ourselves as the call-session listener for the live
         * dialog. Same global-listener pattern MO uses; safe because
         * only one call is active. */
        HamelinPortsSipStack.setCallSessionListener(new CallSessionListener() {
            @Override public void onProvisional(int code, String reason) { /* MT doesn't receive these */ }
            @Override public void onAnswer(String remoteIp, int remoteRtpPort,
                                           int remoteRtcpPort, int pt,
                                           int clockRate, String codecName,
                                           String fmtp) { /* only MO path fires */ }
            @Override public void onConnected(int code, String reason) {
                Log.i(TAG, "MT onConnected (remote ACK'd our 200 OK)");
                mState = ImsCallSessionImplBase.State.ESTABLISHED;
                if (mListener != null) {
                    mListener.callSessionInitiated(mProfile);
                }
                startMediaEngine();
            }
            @Override public void onTerminated(int reasonCode, String reason) {
                Log.i(TAG, "MT onTerminated: " + reasonCode + " " + reason);
                mState = ImsCallSessionImplBase.State.TERMINATED;
                cleanup();
                if (mListener != null) {
                    mListener.callSessionTerminated(new ImsReasonInfo(
                            ImsReasonInfo.CODE_USER_TERMINATED, reasonCode, reason));
                }
            }
            @Override public void onFailure(int code, String reason) {
                Log.w(TAG, "MT onFailure: " + code + " " + reason);
                mState = ImsCallSessionImplBase.State.TERMINATED;
                cleanup();
                if (mListener != null) {
                    mListener.callSessionTerminated(new ImsReasonInfo(
                            ImsReasonInfo.CODE_LOCAL_CALL_DECLINE, code, reason));
                }
            }
        });

        String sdp = buildAnswerSdp(localIp, mRtpPort, mRtcpPort, mRemote,
                mRemoteVideo, mRtpPortVideo, mRtcpPortVideo);
        boolean ok = HamelinPortsSipStack.acceptIncomingCall(mCallId, sdp);
        Log.i(TAG, "MT accept: acceptIncomingCall ok=" + ok);
        if (!ok) {
            throw new IllegalStateException("native acceptIncomingCall returned false");
        }
    }

    private void startMediaEngine() {
        if (mImsMedia != null) return;
        if (mRtpSocket == null || mRtpSocket.isClosed()) {
            Log.w(TAG, "MT onConnected but RTP socket is closed");
            return;
        }
        /* Ensure AudioManager is in MODE_IN_COMMUNICATION (the VoIP mode)
         * before opening the imsmedia session. Telecom normally sets
         * this on call activation but timing depends on the focus
         * transition; doing it here defensively avoids the race where
         * openAudioStream() sees MODE_NORMAL and routes through the
         * media path — which then gets yanked with AAUDIO_ERROR_DISCONNECTED
         * when Telecom updates the focus. MODE_IN_CALL (the CS-voice
         * mode) must NOT be used: the audio HAL expects an actual
         * modem voice bearer and AudioSource starts time-out. */
        try {
            AudioManager am = mRegController.getContext()
                    .getSystemService(AudioManager.class);
            if (am != null) {
                mSavedAudioMode = am.getMode();
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                Log.i(TAG, "MT AudioManager mode: "
                        + mSavedAudioMode + " → MODE_IN_COMMUNICATION");
            }
        } catch (Exception e) {
            Log.w(TAG, "MT setMode(IN_COMMUNICATION) failed", e);
        }
        try {
            mImsMedia = new HamelinPortsImsMediaSession(mRegController.getContext(),
                    mRtpSocket, mRtcpSocket,
                    mRemote.ip, mRemote.rtpPort, mRemote.rtcpPort,
                    mRemote.payloadType, mRemote.clockRate,
                    mRemote.codecName, mRemote.fmtp);
            mImsMedia.start();
        } catch (Exception e) {
            Log.e(TAG, "MT imsmedia audio engine start failed", e);
        }
        /* MT C.5 — when the incoming offer carried video, open a
         * parallel SESSION_TYPE_VIDEO imsmedia session using the
         * sockets allocated in doAccept. No VideoCallProvider yet
         * on MT; surfaces will be wired by Telecom via
         * {@code getImsVideoCallProvider()} (C.5.x). */
        if (mRemoteVideo != null && mRtpSocketVideo != null
                && !mRtpSocketVideo.isClosed()) {
            try {
                int dir = android.telephony.imsmedia.RtpConfig.MEDIA_DIRECTION_SEND_RECEIVE;
                mImsMediaVideo = new HamelinPortsImsMediaVideoSession(
                        mRegController.getContext(),
                        mRtpSocketVideo, mRtcpSocketVideo,
                        mRemoteVideo.ip, mRemoteVideo.rtpPort, mRemoteVideo.rtcpPort,
                        mRemoteVideo.payloadType, mRemoteVideo.clockRate,
                        mRemoteVideo.codecName, mRemoteVideo.fmtp,
                        dir);
                mImsMediaVideo.start();
                Log.i(TAG, "MT imsmedia video session started");
            } catch (Exception e) {
                Log.e(TAG, "MT imsmedia video engine start failed", e);
            }
        }
    }

    private void cleanup() {
        if (mImsMedia != null) { mImsMedia.stop(); mImsMedia = null; }
        if (mImsMediaVideo != null) { mImsMediaVideo.stop(); mImsMediaVideo = null; }
        if (mRtpSocket != null) { mRtpSocket.close(); mRtpSocket = null; }
        if (mRtcpSocket != null) { mRtcpSocket.close(); mRtcpSocket = null; }
        if (mRtpSocketVideo != null) { mRtpSocketVideo.close(); mRtpSocketVideo = null; }
        if (mRtcpSocketVideo != null) { mRtcpSocketVideo.close(); mRtcpSocketVideo = null; }
        /* Restore the audio mode we captured in startMediaEngine.
         * MODE_INVALID means setMode never ran, so nothing to restore. */
        if (mSavedAudioMode != AudioManager.MODE_INVALID) {
            try {
                AudioManager am = mRegController.getContext()
                        .getSystemService(AudioManager.class);
                if (am != null) {
                    am.setMode(mSavedAudioMode);
                    Log.i(TAG, "MT AudioManager mode restored to "
                            + mSavedAudioMode);
                }
            } catch (Exception e) {
                Log.w(TAG, "MT restoreMode failed", e);
            }
            mSavedAudioMode = AudioManager.MODE_INVALID;
        }
        fireGoneOnce();
    }

    private synchronized void fireGoneOnce() {
        if (mGoneFired) return;
        mGoneFired = true;
        if (mOnGone != null) {
            try { mOnGone.accept(this); } catch (Throwable ignored) {}
        }
    }

    /** Map {@link ImsReasonInfo} reject code to a SIP response code
     *  per TS 24.229 §5.1.3. Mirrors ImsPhoneCallTracker's choices. */
    private static int mapRejectReasonToSip(int reason) {
        switch (reason) {
            case ImsReasonInfo.CODE_USER_DECLINE:
                return 603;  /* Decline */
            case ImsReasonInfo.CODE_SIP_USER_MARKED_UNWANTED:
                return 607;  /* Unwanted */
            case ImsReasonInfo.CODE_USER_IGNORE:
            case ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE:
                return 480;  /* Temporarily Unavailable */
            default:
                return 486;  /* Busy Here — default framework "reject" */
        }
    }

    /** Build the SDP answer: mirror the offer's audio m-line and —
     *  when {@code video != null} — also the video m-line. RFC 3264
     *  §5.1 requires same m-line count + order in the answer. */
    private static String buildAnswerSdp(String localIp,
                                         int rtpPort, int rtcpPort,
                                         RemoteAudio remote,
                                         RemoteAudio video,
                                         int videoRtpPort, int videoRtcpPort) {
        String addrType = localIp.contains(":") ? "IP6" : "IP4";
        StringBuilder sb = new StringBuilder();
        sb.append("v=0\r\n");
        sb.append("o=- 1 1 IN ").append(addrType).append(" ").append(localIp).append("\r\n");
        sb.append("s=-\r\n");
        sb.append("c=IN ").append(addrType).append(" ").append(localIp).append("\r\n");
        sb.append("t=0 0\r\n");
        /* Keep only the codec the offer selected + telephone-event —
         * sufficient for a legal audio answer. */
        sb.append("m=audio ").append(rtpPort)
                .append(" RTP/AVP ").append(remote.payloadType);
        /* Advertise telephone-event on 97 as a courtesy — some
         * carriers require DTMF over RFC 4733 post-answer. */
        sb.append(" 97\r\n");
        sb.append("a=rtcp:").append(rtcpPort).append("\r\n");
        sb.append("a=rtpmap:").append(remote.payloadType).append(" ")
                .append(remote.codecName).append("/").append(remote.clockRate).append("\r\n");
        if (remote.fmtp != null && !remote.fmtp.isEmpty()) {
            sb.append("a=fmtp:").append(remote.payloadType).append(" ")
                    .append(remote.fmtp).append("\r\n");
        }
        sb.append("a=rtpmap:97 telephone-event/").append(remote.clockRate).append("\r\n");
        sb.append("a=fmtp:97 0-15\r\n");
        sb.append("a=sendrecv\r\n");
        sb.append("a=ptime:20\r\n");
        sb.append("a=maxptime:240\r\n");
        if (video != null) {
            /* Mirror the offer's video codec + PT + fmtp so the peer
             * can match its transmit path. sendrecv for bidirectional
             * video — the ImsCallProfile direction annex can narrow
             * this later via re-INVITE (C.4). */
            sb.append("m=video ").append(videoRtpPort)
                    .append(" RTP/AVP ").append(video.payloadType).append("\r\n");
            sb.append("a=rtcp:").append(videoRtcpPort).append("\r\n");
            sb.append("a=rtpmap:").append(video.payloadType).append(" ")
                    .append(video.codecName).append("/").append(video.clockRate).append("\r\n");
            if (video.fmtp != null && !video.fmtp.isEmpty()) {
                sb.append("a=fmtp:").append(video.payloadType).append(" ")
                        .append(video.fmtp).append("\r\n");
            }
            sb.append("a=sendrecv\r\n");
        }
        return sb.toString();
    }

    private static String stripScope(String ip) {
        int i = ip.indexOf('%');
        return i > 0 ? ip.substring(0, i) : ip;
    }

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
