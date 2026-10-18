package org.hamelinports.ims.sip;

/**
 * Which send status an MO SMS gets for a received, RP-MR-matched RP-ERROR.
 *
 * <p>The framework never reads the carrier's RP-Cause lists itself; it
 * expects the ImsService to answer SEND_STATUS_ERROR_FALLBACK (resend over
 * CS) or SEND_STATUS_ERROR_RETRY (resend over IMS, CS after its retry limit).
 * With {@value #PROPERTY} on: a cause on the retry list gives RETRY; a cause
 * on the fallback list gives FALLBACK if the carrier allows CS fallback on
 * failure, else ERROR; anything else, or a missing cause, gives ERROR. Off:
 * ERROR for every cause, as before. An RP-ERROR means the SMSC did not store
 * the message, so a resend cannot duplicate it.
 *
 * <p>Pure Java, so the host tests drive it directly.
 */
public final class RpErrorPolicy {
    /** Device switch; unset means off. */
    public static final String PROPERTY = "persist.lineage.ims.sms_rp_fallback";

    /** The send outcome, mirroring ImsSmsImplBase.SEND_STATUS_*. */
    public enum Status { OK, ERROR, ERROR_RETRY, ERROR_FALLBACK }

    private static final RpErrorPolicy OFF = new RpErrorPolicy(false, null, null, false);

    private final boolean mEnabled;
    private final int[] mFallbackCauses;
    private final int[] mRetryCauses;
    private final boolean mCsFallbackAllowed;

    /**
     * @param fallbackCauses     carrier sms_rp_cause_values_to_fallback (null = none)
     * @param retryCauses        carrier sms_rp_cause_values_to_retry_over_ims (null = none)
     * @param csFallbackAllowed  carrier sms_csfb_retry_on_failure
     */
    public RpErrorPolicy(boolean enabled, int[] fallbackCauses, int[] retryCauses,
            boolean csFallbackAllowed) {
        mEnabled = enabled;
        mFallbackCauses = fallbackCauses == null ? new int[0] : fallbackCauses.clone();
        mRetryCauses = retryCauses == null ? new int[0] : retryCauses.clone();
        mCsFallbackAllowed = csFallbackAllowed;
    }

    /** The behaviour with the property off. */
    public static RpErrorPolicy off() {
        return OFF;
    }

    public boolean enabled() {
        return mEnabled;
    }

    public Status statusFor(int cause) {
        if (!mEnabled || cause < 0) {
            return Status.ERROR;
        }
        if (contains(mRetryCauses, cause)) {
            return Status.ERROR_RETRY;
        }
        if (contains(mFallbackCauses, cause)) {
            return mCsFallbackAllowed ? Status.ERROR_FALLBACK : Status.ERROR;
        }
        return Status.ERROR;
    }

    private static boolean contains(int[] values, int v) {
        for (int x : values) {
            if (x == v) return true;
        }
        return false;
    }
}
