package org.hamelinports.ims;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.CallQuality;
import android.telephony.imsmedia.AmrParams;
import android.telephony.imsmedia.AudioConfig;
import android.telephony.imsmedia.AudioSessionCallback;
import android.telephony.imsmedia.ImsAudioSession;
import android.telephony.imsmedia.ImsMediaManager;
import android.telephony.imsmedia.ImsMediaSession;
import android.telephony.imsmedia.MediaQualityStatus;
import android.telephony.imsmedia.MediaQualityThreshold;
import android.telephony.imsmedia.RtcpConfig;
import android.telephony.imsmedia.RtpConfig;
import android.util.Log;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Voice media session driven by AOSP's
 * {@link android.telephony.imsmedia} framework service. Binds to
 * {@code com.android.telephony.imsmedia}, opens an {@code ImsAudioSession}
 * against the negotiated RTP/RTCP endpoints, and hands the UDP sockets
 * over via {@link ImsMediaManager#openSession}. libimsmedia then runs
 * the AMR-WB codec + RTP/RTCP state machine in native, and drives the
 * voice-call audio HAL path.
 *
 * <p>The only media engine — {@code HamelinPortsMediaEngine} (hand-rolled
 * RTP/MediaCodec) was retired 2026-04-23 after imsmedia reached
 * feature parity for AMR-WB (both octet-aligned and bandwidth-efficient
 * packetisations).
 */
public final class HamelinPortsImsMediaSession {
    private static final String TAG = HamelinPortsImsService.TAG;

    private final Context mContext;
    private final DatagramSocket mRtpSocket;
    private final DatagramSocket mRtcpSocket;
    private final String mRemoteIp;
    private final int mRemoteRtpPort;
    private final int mRemoteRtcpPort;
    private final int mPayloadType;
    private final int mClockRate;
    private final String mCodecName;
    private final String mFmtp;

    private final Executor mExecutor;
    private ImsMediaManager mManager;
    private volatile boolean mConnected;
    private ImsAudioSession mSession;
    /** True once start() has been called; clear on stop(). Gates
     *  the deferred openSession() that runs on onConnected. */
    private volatile boolean mStartRequested;

    public HamelinPortsImsMediaSession(Context context,
                                   DatagramSocket rtpSocket, DatagramSocket rtcpSocket,
                                   String remoteIp, int remoteRtpPort, int remoteRtcpPort,
                                   int payloadType, int clockRate,
                                   String codecName, String fmtp) {
        mContext = context;
        mRtpSocket = rtpSocket;
        mRtcpSocket = rtcpSocket;
        mRemoteIp = remoteIp;
        mRemoteRtpPort = remoteRtpPort;
        mRemoteRtcpPort = remoteRtcpPort;
        mPayloadType = payloadType;
        mClockRate = clockRate;
        mCodecName = codecName;
        mFmtp = fmtp;

        /* Run everything on the main looper. ImsMediaManager binds via
         * Context.bindService under the hood; the onServiceConnected
         * / onDisconnected hops land on the main thread by default.
         * Keeping our executor on the main looper avoids thread-
         * visibility races with the binder proxy. */
        final Handler h = new Handler(Looper.getMainLooper());
        mExecutor = h::post;
    }

    /** Bind to the ImsMediaService, then on connect open an
     *  {@link android.telephony.imsmedia.AudioSession} with the
     *  negotiated codec + remote endpoint. */
    public synchronized void start() {
        if (mManager != null) return;
        Log.i(TAG, "HamelinPortsImsMediaSession.start: binding ImsMediaManager "
                + "(remote rtp=" + mRemoteRtpPort + "/rtcp=" + mRemoteRtcpPort
                + " pt=" + mPayloadType + " " + mCodecName + "/" + mClockRate + ")");
        mStartRequested = true;
        /* Audio mode is driven by Telecom's CallAudioModeStateMachine.
         * On A51 the device overlay sets config_use_voip_mode_for_ims
         * true so ImsPhoneConnection flags our call as VOIP, which
         * causes Telecom to pick MODE_IN_COMMUNICATION and route
         * mic/speaker through the userspace mixer (we don't have a
         * modem audio HAL). No manual setMode needed here. */
        try {
            mManager = new ImsMediaManager(mContext, mExecutor,
                    new ImsMediaManager.OnConnectedCallback() {
                        @Override public void onConnected() {
                            mConnected = true;
                            Log.i(TAG, "ImsMediaManager: onConnected — ready");
                            openAudioSessionLocked();
                        }
                        @Override public void onDisconnected() {
                            mConnected = false;
                            Log.w(TAG, "ImsMediaManager: onDisconnected");
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "ImsMediaManager bind failed", t);
        }
    }

    public synchronized void stop() {
        Log.i(TAG, "HamelinPortsImsMediaSession.stop (connected=" + mConnected + ")");
        mStartRequested = false;
        if (mManager != null && mSession != null) {
            try { mManager.closeSession(mSession); }
            catch (Throwable t) { Log.w(TAG, "closeSession failed", t); }
            mSession = null;
        }
        if (mManager != null) {
            try { mManager.release(); }
            catch (Throwable t) { Log.w(TAG, "release failed", t); }
            mManager = null;
        }
    }

    /** Open the AudioSession now that the service is connected. Must
     *  be called with the HamelinPortsImsMediaSession lock held by the
     *  onConnected thread, but the ImsMediaManager call itself posts
     *  to our mExecutor so it's reentrant-safe. */
    private void openAudioSessionLocked() {
        if (!mStartRequested || mManager == null) return;
        if (mSession != null) return;

        /* Map the negotiated codec to AudioConfig. We currently only
         * offer AMR-WB octet-align mode 0/1/2; anything else would be
         * a SDP renegotiation we don't plan for. */
        final int amrMode = AmrParams.AMR_MODE_0
                | AmrParams.AMR_MODE_1
                | AmrParams.AMR_MODE_2;
        final boolean octetAligned = mFmtp != null
                && mFmtp.replaceAll("\\s", "").contains("octet-align=1");

        final AmrParams amr = new AmrParams.Builder()
                .setAmrMode(amrMode)
                .setOctetAligned(octetAligned)
                .setMaxRedundancyMillis(0)
                .build();

        /* RtcpConfig: 5-second interval per RFC 3550 §6.2 for typical
         * two-party audio. transmitPort=0 lets the service pick; we
         * still bound the local RTCP socket ourselves. */
        final RtcpConfig rtcp = new RtcpConfig.Builder()
                .setCanonicalName("lineageims")
                .setTransmitPort(mRemoteRtcpPort)
                .setIntervalSec(5)
                .setRtcpXrBlockTypes(RtcpConfig.FLAG_RTCPXR_NONE)
                .build();

        final InetAddress remote;
        try {
            remote = InetAddress.getByName(stripZoneId(mRemoteIp));
        } catch (Exception e) {
            Log.e(TAG, "bad remote IP " + mRemoteIp, e);
            return;
        }

        final AudioConfig audio = new AudioConfig.Builder()
                .setMediaDirection(RtpConfig.MEDIA_DIRECTION_SEND_RECEIVE)
                .setAccessNetwork(AccessNetworkType.EUTRAN)
                .setRemoteRtpAddress(new InetSocketAddress(remote, mRemoteRtpPort))
                .setRtcpConfig(rtcp)
                .setDscp((byte) 0)
                .setRxPayloadTypeNumber((byte) mPayloadType)
                .setTxPayloadTypeNumber((byte) mPayloadType)
                .setSamplingRateKHz((byte) (mClockRate / 1000))
                .setCodecType(AudioConfig.CODEC_AMR_WB)
                .setPtimeMillis((byte) 20)
                .setMaxPtimeMillis(240)
                .setDtxEnabled(true)
                .setAmrParams(amr)
                /* telephone-event PT 97 matches our SDP offer. */
                .setTxDtmfPayloadTypeNumber((byte) 97)
                .setRxDtmfPayloadTypeNumber((byte) 97)
                .setDtmfSamplingRateKHz((byte) 16)
                .build();

        Log.i(TAG, "openSession: audio direction=SEND_RECEIVE, codec=AMR_WB, "
                + "ptime=20, dtx=true, octet-aligned=" + octetAligned
                + ", remote=" + remote.getHostAddress() + ":" + mRemoteRtpPort);

        /* Pass the full config on openSession so libimsmedia's AudioManager
         * creates the RTP graph atomically with the session. Previously we
         * deferred the config via modifySession, but AudioManager::openSession
         * calls startGraph(config) inline — with a null config startGraph
         * fails silently and leaves the session in a half-initialised state
         * (no RTP TX node, mic thread running but RTP stats zeroed). */
        mManager.openSession(mRtpSocket, mRtcpSocket,
                ImsMediaSession.SESSION_TYPE_AUDIO, audio, mExecutor,
                new AudioSessionCallback() {
                    @Override
                    public void onOpenSessionSuccess(ImsMediaSession session) {
                        Log.i(TAG, "AudioSession opened: " + session);
                        synchronized (HamelinPortsImsMediaSession.this) {
                            if (!mStartRequested) {
                                /* Race: stop() arrived before the service
                                 * answered. Close the freshly-opened
                                 * session immediately. */
                                try { mManager.closeSession(session); }
                                catch (Throwable ignored) {}
                                return;
                            }
                            if (session instanceof ImsAudioSession) {
                                mSession = (ImsAudioSession) session;
                                applyMediaQualityThreshold(mSession);
                            }
                        }
                    }

                    @Override
                    public void onOpenSessionFailure(int error) {
                        Log.e(TAG, "AudioSession open failed: error=" + error);
                    }

                    @Override
                    public void onSessionClosed() {
                        Log.i(TAG, "AudioSession closed (by service)");
                    }

                    @Override
                    public void onFirstMediaPacketReceived(AudioConfig config) {
                        Log.i(TAG, "first RTP packet received: "
                                + "codec=" + config.getCodecType());
                    }

                    @Override
                    public void onCallQualityChanged(CallQuality q) {
                        Log.i(TAG, "call quality: dl=" + q.getDownlinkCallQualityLevel()
                                + " ul=" + q.getUplinkCallQualityLevel()
                                + " rtt=" + q.getAverageRoundTripTime()
                                + " jitter=" + q.getAverageRelativeJitter());
                    }

                    @Override
                    public void notifyMediaQualityStatus(MediaQualityStatus status) {
                        /* Threshold-crossing notifier (registered via
                         * applyMediaQualityThreshold). Logged only — we
                         * do not yet take action on the call (e.g.
                         * graceful BYE on long inactivity). The carrier
                         * tears the session itself if we ever stop
                         * sending RTCP. */
                        Log.w(TAG, "media-quality threshold crossed:"
                                + " rtpInactivityMs=" + status.getRtpInactivityTimeMillis()
                                + " rtcpInactivityMs=" + status.getRtcpInactivityTimeMillis()
                                + " lossRatePct=" + status.getRtpPacketLossRate()
                                + " jitterMs=" + status.getRtpJitterMillis());
                    }

                    @Override
                    public void onDtmfReceived(char digit, int durationMs) {
                        Log.i(TAG, "DTMF recv: " + digit + " (" + durationMs + "ms)");
                    }
                });
    }

    /** Phase B.7 — register MediaQualityThreshold so libimsmedia fires
     *  notifyMediaQualityStatus when one of the configured limits is
     *  crossed. Values are picked to detect classic bad-call symptoms
     *  without nagging on transient packet jitter:
     *   - 5 s of no inbound RTP (mic-up but UE→net cut)
     *   - 10 s of no inbound RTCP (full-duplex teardown)
     *   - 10% packet loss measured over a 1 s window
     *   - 60 ms relative jitter (AMR-WB ptime is 20 ms; 3× ptime is the
     *     point where the jitter buffer typically starts dropping). */
    private void applyMediaQualityThreshold(ImsAudioSession session) {
        try {
            MediaQualityThreshold t = new MediaQualityThreshold.Builder()
                    .setRtpInactivityTimerMillis(new int[] { 5000 })
                    .setRtcpInactivityTimerMillis(10000)
                    .setRtpHysteresisTimeInMillis(2000)
                    .setRtpPacketLossDurationMillis(1000)
                    .setRtpPacketLossRate(new int[] { 10 })
                    .setRtpJitterMillis(new int[] { 60 })
                    .setNotifyCurrentStatus(false)
                    .build();
            session.setMediaQualityThreshold(t);
            Log.i(TAG, "media-quality threshold set: " + t);
        } catch (Throwable e) {
            Log.w(TAG, "setMediaQualityThreshold failed", e);
        }
    }

    private static String stripZoneId(String ip) {
        int i = ip.indexOf('%');
        return i > 0 ? ip.substring(0, i) : ip;
    }
}
