// SPDX-License-Identifier: Apache-2.0
package org.hamelinports.ims;

import android.net.Uri;
import android.telephony.ims.ImsVideoCallProvider;
import android.telecom.VideoProfile;
import android.util.Log;
import android.view.Surface;

/**
 * Bridges Telecom's {@link ImsVideoCallProvider} contract to our
 * imsmedia video session. Instantiated per {@link HamelinPortsCallSession}
 * (and {@link HamelinPortsIncomingCallSession} in C.5) that's a video
 * call; returned from {@code getVideoCallProvider()}.
 *
 * <p>Phase C.3 — surface + orientation wiring only. Mid-call upgrade
 * / downgrade ({@link #onSendSessionModifyRequest}) lands in C.4.
 * Camera-capability reporting ({@link #onRequestCameraCapabilities})
 * stays as a stub; libimsmedia enumerates its own list via the
 * Camera2 API when the encoder graph spins up.
 */
public final class HamelinPortsVideoCallProvider extends ImsVideoCallProvider {
    private static final String TAG = HamelinPortsImsService.TAG;

    /** Non-null for the lifetime of the call; surface events are
     *  forwarded to it. The caller wires this to the session's
     *  live preview/display surfaces once received from Telecom. */
    private final SurfaceTarget mTarget;

    public interface SurfaceTarget {
        void setCameraId(String cameraId);
        void setPreviewSurface(Surface s);
        void setDisplaySurface(Surface s);
        void setDeviceOrientation(int rotationDegrees);
        void setZoom(float value);
        void sessionModifyRequest(VideoProfile from, VideoProfile to);
        void sessionModifyResponse(VideoProfile responseProfile);
    }

    public HamelinPortsVideoCallProvider(SurfaceTarget target) {
        mTarget = target;
    }

    @Override
    public void onSetCamera(String cameraId) {
        Log.i(TAG, "VideoProvider onSetCamera cameraId=" + cameraId);
        mTarget.setCameraId(cameraId);
    }

    @Override
    public void onSetPreviewSurface(Surface surface) {
        Log.i(TAG, "VideoProvider onSetPreviewSurface " + surface);
        mTarget.setPreviewSurface(surface);
    }

    @Override
    public void onSetDisplaySurface(Surface surface) {
        Log.i(TAG, "VideoProvider onSetDisplaySurface " + surface);
        mTarget.setDisplaySurface(surface);
    }

    @Override
    public void onSetDeviceOrientation(int rotation) {
        Log.i(TAG, "VideoProvider onSetDeviceOrientation " + rotation);
        mTarget.setDeviceOrientation(rotation);
    }

    @Override
    public void onSetZoom(float value) {
        Log.i(TAG, "VideoProvider onSetZoom " + value);
        mTarget.setZoom(value);
    }

    @Override
    public void onSendSessionModifyRequest(VideoProfile fromProfile, VideoProfile toProfile) {
        Log.i(TAG, "VideoProvider onSendSessionModifyRequest "
                + fromProfile + " → " + toProfile);
        mTarget.sessionModifyRequest(fromProfile, toProfile);
        /* C.4 TODO: re-INVITE with updated SDP to flip video on/off
         * or swap direction. Currently this is a no-op handoff to
         * the session which also TODOs the same. */
    }

    @Override
    public void onSendSessionModifyResponse(VideoProfile responseProfile) {
        Log.i(TAG, "VideoProvider onSendSessionModifyResponse " + responseProfile);
        mTarget.sessionModifyResponse(responseProfile);
    }

    @Override
    public void onRequestCameraCapabilities() {
        /* libimsmedia enumerates via Camera2 internally; we don't need
         * to synthesize a fake CameraCapabilities here. */
        Log.i(TAG, "VideoProvider onRequestCameraCapabilities (no-op)");
    }

    @Override
    public void onRequestCallDataUsage() {
        /* TODO: surface RTP bytes from imsmedia once a usage-stat
         * API lands. For now return 0. */
        Log.i(TAG, "VideoProvider onRequestCallDataUsage (reporting 0)");
        changeCallDataUsage(0);
    }

    @Override
    public void onSetPauseImage(Uri uri) {
        Log.i(TAG, "VideoProvider onSetPauseImage " + uri);
        /* Pause-image overlay — imsmedia has VideoMode
         * VIDEO_MODE_PAUSE_IMAGE; wire when we have a need. */
    }
}
