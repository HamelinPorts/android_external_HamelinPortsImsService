// SPDX-License-Identifier: Apache-2.0
package org.hamelinports.ims;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.imsmedia.ImsMediaManager;
import android.telephony.imsmedia.ImsMediaSession;
import android.telephony.imsmedia.ImsVideoSession;
import android.telephony.imsmedia.RtcpConfig;
import android.telephony.imsmedia.RtpConfig;
import android.telephony.imsmedia.VideoConfig;
import android.telephony.imsmedia.VideoSessionCallback;
import android.util.Log;
import android.view.Surface;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;

/**
 * Video counterpart to {@link HamelinPortsImsMediaSession}. Opens a
 * {@link android.telephony.imsmedia.ImsMediaSession#SESSION_TYPE_VIDEO}
 * imsmedia session against the negotiated H.264 endpoint — one per
 * call, running in parallel with the audio session.
 *
 * <p>Phase C.2: opens the session and wires RTP/RTCP. The
 * camera/display surfaces are supplied lazily by
 * {@link HamelinPortsVideoCallProvider} in Phase C.3 — until then the
 * session runs with no surfaces (decoder output is discarded, no
 * encoded output is produced). That still exercises the RTP graph
 * which is the focus of C.2 wire-level debugging.
 */
public final class HamelinPortsImsMediaVideoSession {
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
    private final int mDirection;        /* RtpConfig.MEDIA_DIRECTION_* */

    private final Executor mExecutor;
    private ImsMediaManager mManager;
    private volatile boolean mConnected;
    private ImsVideoSession mSession;
    private volatile boolean mStartRequested;

    /* Latched surfaces from the VideoCallProvider, if any. Applied on
     * session open (or re-applied synchronously once available). */
    private Surface mPreviewSurface;
    private Surface mDisplaySurface;

    public HamelinPortsImsMediaVideoSession(Context context,
                                        DatagramSocket rtpSocket, DatagramSocket rtcpSocket,
                                        String remoteIp, int remoteRtpPort, int remoteRtcpPort,
                                        int payloadType, int clockRate,
                                        String codecName, String fmtp,
                                        int direction) {
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
        mDirection = direction;

        final Handler h = new Handler(Looper.getMainLooper());
        mExecutor = h::post;
    }

    public synchronized void start() {
        if (mManager != null) return;
        Log.i(TAG, "HamelinPortsImsMediaVideoSession.start: binding ImsMediaManager "
                + "(remote rtp=" + mRemoteRtpPort + "/rtcp=" + mRemoteRtcpPort
                + " pt=" + mPayloadType + " " + mCodecName + "/" + mClockRate
                + " fmtp=[" + mFmtp + "]"
                + " dir=" + directionName(mDirection) + ")");
        mStartRequested = true;
        try {
            mManager = new ImsMediaManager(mContext, mExecutor, new ImsMediaManager.OnConnectedCallback() {
                @Override public void onConnected() {
                    Log.i(TAG, "VideoImsMediaManager: onConnected — ready");
                    synchronized (HamelinPortsImsMediaVideoSession.this) {
                        mConnected = true;
                        openVideoSessionLocked();
                    }
                }
                @Override public void onDisconnected() {
                    Log.w(TAG, "VideoImsMediaManager: onDisconnected");
                    synchronized (HamelinPortsImsMediaVideoSession.this) {
                        mConnected = false;
                        mSession = null;
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "VideoImsMediaManager bind failed", t);
            mManager = null;
        }
    }

    public synchronized void stop() {
        mStartRequested = false;
        if (mSession != null) {
            try { mManager.closeSession(mSession); }
            catch (Throwable t) { Log.w(TAG, "video closeSession failed", t); }
            mSession = null;
        }
        if (mManager != null) {
            try { mManager.release(); }
            catch (Throwable t) { Log.w(TAG, "video release failed", t); }
            mManager = null;
        }
        mPreviewSurface = null;
        mDisplaySurface = null;
    }

    /** Wire the camera preview surface. Safe to call any time — if
     *  the session isn't open yet we latch it and apply on
     *  onOpenSessionSuccess. Called from
     *  {@link HamelinPortsVideoCallProvider} (Phase C.3). */
    public synchronized void setPreviewSurface(Surface s) {
        mPreviewSurface = s;
        if (mSession != null && s != null) {
            Log.i(TAG, "video setPreviewSurface (session live)");
            try { mSession.setPreviewSurface(s); }
            catch (Throwable t) { Log.w(TAG, "setPreviewSurface threw", t); }
        } else {
            Log.i(TAG, "video setPreviewSurface latched (session="
                    + (mSession != null) + ")");
        }
    }

    public synchronized void setDisplaySurface(Surface s) {
        mDisplaySurface = s;
        if (mSession != null && s != null) {
            Log.i(TAG, "video setDisplaySurface (session live)");
            try { mSession.setDisplaySurface(s); }
            catch (Throwable t) { Log.w(TAG, "setDisplaySurface threw", t); }
        } else {
            Log.i(TAG, "video setDisplaySurface latched (session="
                    + (mSession != null) + ")");
        }
    }

    private void openVideoSessionLocked() {
        if (!mStartRequested || mManager == null) return;
        if (mSession != null) return;

        /* Parse resolution hints from fmtp if present (imageattr /
         * max-fs). GSMA PRD IR.94 §6.2.1 default is 480p (640x480)
         * at 30fps for the common MMTel-video profile. Carrier may
         * cap below this via max-mbps; we start at 480p and let
         * imsmedia renegotiate down on QoS feedback. */
        int width  = 640;
        int height = 480;
        int fps    = 30;
        int bitrateKbps = 384;

        final RtcpConfig rtcp = new RtcpConfig.Builder()
                .setCanonicalName("lineageims-video")
                .setTransmitPort(mRemoteRtcpPort)
                .setIntervalSec(5)
                .setRtcpXrBlockTypes(RtcpConfig.FLAG_RTCPXR_NONE)
                .build();

        final InetAddress remote;
        try {
            remote = InetAddress.getByName(stripZoneId(mRemoteIp));
        } catch (Exception e) {
            Log.e(TAG, "video bad remote IP " + mRemoteIp, e);
            return;
        }

        final VideoConfig video = new VideoConfig.Builder()
                .setMediaDirection(mDirection)
                .setAccessNetwork(AccessNetworkType.EUTRAN)
                .setRemoteRtpAddress(new InetSocketAddress(remote, mRemoteRtpPort))
                .setRtcpConfig(rtcp)
                .setDscp((byte) 0)
                .setRxPayloadTypeNumber((byte) mPayloadType)
                .setTxPayloadTypeNumber((byte) mPayloadType)
                .setSamplingRateKHz((byte) 90)         /* H.264 clock = 90 kHz */
                .setCodecType(VideoConfig.VIDEO_CODEC_AVC)
                .setVideoMode(VideoConfig.VIDEO_MODE_RECORDING)
                .setResolutionWidth(width)
                .setResolutionHeight(height)
                .setFramerate(fps)
                .setBitrate(bitrateKbps)
                .build();

        Log.i(TAG, "video openSession: codec=H264 "
                + width + "x" + height + "@" + fps + " "
                + bitrateKbps + "kbps direction="
                + directionName(mDirection)
                + " remote=" + remote.getHostAddress() + ":" + mRemoteRtpPort);

        mManager.openSession(mRtpSocket, mRtcpSocket,
                ImsMediaSession.SESSION_TYPE_VIDEO, video, mExecutor,
                new VideoSessionCallback() {
                    @Override
                    public void onOpenSessionSuccess(ImsMediaSession session) {
                        Log.i(TAG, "VideoSession opened: " + session);
                        synchronized (HamelinPortsImsMediaVideoSession.this) {
                            if (!mStartRequested) {
                                try { mManager.closeSession(session); }
                                catch (Throwable ignored) {}
                                return;
                            }
                            if (session instanceof ImsVideoSession) {
                                mSession = (ImsVideoSession) session;
                                /* Surfaces were latched before open — apply now. */
                                if (mPreviewSurface != null) {
                                    try { mSession.setPreviewSurface(mPreviewSurface); }
                                    catch (Throwable t) { Log.w(TAG, "setPreviewSurface deferred-apply threw", t); }
                                }
                                if (mDisplaySurface != null) {
                                    try { mSession.setDisplaySurface(mDisplaySurface); }
                                    catch (Throwable t) { Log.w(TAG, "setDisplaySurface deferred-apply threw", t); }
                                }
                            }
                        }
                    }
                    @Override public void onOpenSessionFailure(int error) {
                        Log.e(TAG, "VideoSession open FAILED: error=" + error);
                    }
                    @Override public void onModifySessionResponse(VideoConfig config, int result) {
                        Log.i(TAG, "VideoSession modify response: result=" + result);
                    }
                    @Override public void onPeerDimensionChanged(int width, int height) {
                        Log.i(TAG, "VideoSession peer dimension " + width + "x" + height);
                    }
                    @Override public void onFirstMediaPacketReceived(VideoConfig config) {
                        Log.i(TAG, "VideoSession first RTP packet received");
                    }
                    @Override public void notifyBitrate(int bitrate) {
                        Log.i(TAG, "VideoSession bitrate notify: " + bitrate);
                    }
                    @Override public void notifyVideoDataUsage(long bytes) {
                        /* Called frequently — suppress to keep log clean. */
                    }
                });
    }

    private static String stripZoneId(String ip) {
        if (ip == null) return null;
        int pct = ip.indexOf('%');
        return pct > 0 ? ip.substring(0, pct) : ip;
    }

    private static String directionName(int d) {
        switch (d) {
            case RtpConfig.MEDIA_DIRECTION_SEND_RECEIVE: return "SENDRECV";
            case RtpConfig.MEDIA_DIRECTION_SEND_ONLY:    return "SENDONLY";
            case RtpConfig.MEDIA_DIRECTION_RECEIVE_ONLY: return "RECVONLY";
            case RtpConfig.MEDIA_DIRECTION_INACTIVE:     return "INACTIVE";
            case RtpConfig.MEDIA_DIRECTION_NO_FLOW:      return "NOFLOW";
            default:                                      return "?(" + d + ")";
        }
    }
}
