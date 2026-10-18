package org.hamelinports.ims.sip;

/**
 * Java side of the native ClientRegistrationHandler. The bridge calls
 * back here from the SIP stack thread when a REGISTER round
 * completes; implementations forward to the AOSP IMS framework
 * (ImsRegistrationImplBase / MmTelFeature).
 *
 * Calls arrive on the SIP stack thread — implementations should
 * post to their normal thread before touching framework state.
 */
public interface RegistrationListener {
    /**
     * @param associatedUri the first {@code P-Associated-URI} from the
     *                      REGISTER 200 OK, e.g.
     *                      "sip:+493023125043@telefonica.de". Empty
     *                      string if the response carried none. The
     *                      host part is the carrier's public-facing
     *                      domain (used downstream for INVITE
     *                      Request-URI's phone-context).
     */
    void onRegistered(String associatedUri);
    /**
     * @param statusCode    SIP status from the failed response, or 0 when
     *                      the registration was removed.
     * @param reason        SIP reason phrase.
     * @param retryAfterSec The {@code Retry-After} header value (RFC
     *                      3261 §20.33, seconds), or 0 if not present.
     *                      Non-zero on a 5xx signals a transient
     *                      condition for which the UAC should retry
     *                      after the indicated interval
     *                      (RFC 3261 §21.5.4). Implementations may
     *                      treat this as a hint to schedule a fresh
     *                      initial registration rather than declaring
     *                      the IMS feature permanently unavailable.
     * @param fromWire      The response came from the network. False for a
     *                      response the stack generated itself (e.g. 408
     *                      on a transaction timeout, 503 when no transport
     *                      could send) and for a removal without a response.
     */
    void onDeregistered(int statusCode, String reason, int retryAfterSec, boolean fromWire);

    /** Negotiated {@code Expires} (seconds) from the REGISTER 200 OK.
     *  Fired right before {@link #onRegistered}; the Java side schedules
     *  the renewal from it. A value of 0 means the response carried no
     *  expires indication; the caller falls back to its requested value. */
    default void onExpiresReported(int expires) {}
}
