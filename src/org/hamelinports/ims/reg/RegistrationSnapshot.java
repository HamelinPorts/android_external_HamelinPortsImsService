package org.hamelinports.ims.reg;

/**
 * Immutable view of the registration for MO callers on other threads,
 * published as one object so a reader never pairs values of two states.
 * Times are on the elapsed-realtime clock.
 */
public final class RegistrationSnapshot {

    /** Nothing registered, nothing pending. */
    public static final RegistrationSnapshot NONE =
            new RegistrationSnapshot(0, Long.MAX_VALUE, false);

    private final long mUsableUntilMs;
    private final long mFreshDueAtMs;
    private final boolean mRenewalPending;

    public RegistrationSnapshot(long usableUntilMs, long freshDueAtMs, boolean renewalPending) {
        mUsableUntilMs = usableUntilMs;
        mFreshDueAtMs = freshDueAtMs;
        mRenewalPending = renewalPending;
    }

    /** The binding can carry a normal MO request: valid and no fresh registration due. */
    public boolean isUsable(long nowMs) {
        return OutgoingGate.isUsable(nowMs, mUsableUntilMs, mFreshDueAtMs);
    }

    /** The binding itself is valid (an emergency call holds the stack from its creation). */
    public boolean isBindingValid(long nowMs) {
        return nowMs < mUsableUntilMs;
    }

    /** A fresh registration runs, is about to, or has come due since this was taken. */
    public boolean isRenewalPending(long nowMs) {
        return mRenewalPending || nowMs >= mFreshDueAtMs;
    }

    public long usableUntilMs() {
        return mUsableUntilMs;
    }

    public long freshDueAtMs() {
        return mFreshDueAtMs;
    }
}
