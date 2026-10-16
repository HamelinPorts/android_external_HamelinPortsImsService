package org.hamelinports.ims.reg;

/**
 * Whether a registration attempt may be sent. Every SIP socket is pinned to
 * the IMS network and nothing else routes SIP traffic there, so an attempt
 * without that network's handle is not sent: like any other unsent attempt
 * it fails and the registration policy retries it.
 *
 * <p>Pure Java, so the host tests drive it directly.
 */
public final class AttemptReadiness {

    /** Handle of no network (NETWORK_UNSPECIFIED in android/multinetwork.h). */
    public static final long NO_NETWORK_HANDLE = 0L;

    /** What keeps an attempt from being sent. */
    public enum Blocker { NONE, NO_NETWORK, NO_PCSCF, NO_CREDENTIALS }

    private AttemptReadiness() {}

    public static Blocker blocker(long networkHandle, int pcscfCount, boolean credentialsReady) {
        if (networkHandle == NO_NETWORK_HANDLE) {
            return Blocker.NO_NETWORK;
        }
        if (pcscfCount <= 0) {
            return Blocker.NO_PCSCF;
        }
        if (!credentialsReady) {
            return Blocker.NO_CREDENTIALS;
        }
        return Blocker.NONE;
    }
}
