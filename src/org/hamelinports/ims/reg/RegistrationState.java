package org.hamelinports.ims.reg;

/**
 * Registration state of one SIM slot, on the elapsed-realtime clock.
 *
 * <p>Not thread-safe: owned by the slot's registration thread. Every fresh
 * attempt and every teardown starts a new generation; callbacks carrying an
 * older generation belong to a stack that no longer exists and are dropped.
 */
public final class RegistrationState {

    /** REGISTER transaction whose final response is outstanding. */
    public enum Op { NONE, REFRESH, FRESH }

    private boolean mRunning;
    private boolean mRegistered;
    private long mRegOkMs = -1;
    private int mExpiresSec;
    private int mRefreshOverrideSec;
    private Op mInFlight = Op.NONE;
    private long mInFlightSentMs = -1;
    private long mLastFreshMs = -1;
    private long mFreshDeferredUntilMs = -1;
    private long mRetryAtMs = -1;
    private long mRetryWindowMs;
    private String mRetryReason = "";
    private int mRetryCount;
    private int mGeneration;

    public boolean isRunning() { return mRunning; }
    public boolean isRegistered() { return mRegistered; }
    public long regOkMs() { return mRegOkMs; }
    public int expiresSec() { return mExpiresSec; }
    public Op inFlight() { return mInFlight; }
    public long inFlightSentMs() { return mInFlightSentMs; }
    public long lastFreshMs() { return mLastFreshMs; }
    public long retryAtMs() { return mRetryAtMs; }
    public long retryWindowMs() { return mRetryWindowMs; }
    public String retryReason() { return mRetryReason; }
    public int retryCount() { return mRetryCount; }
    public int generation() { return mGeneration; }

    /** Earliest time the policy may start a fresh registration. */
    public long freshNotBeforeMs() {
        return Math.max(RefreshPolicy.freshFloorMs(mLastFreshMs), mFreshDeferredUntilMs);
    }

    public boolean isCurrent(int generation) {
        return generation == mGeneration;
    }

    /** No registration, no attempt and no retry pending: a PDN event may start one. */
    public boolean isIdle() {
        return !mRegistered && mInFlight == Op.NONE && mRetryAtMs < 0;
    }

    public long ageMs(long nowMs) {
        return nowMs - mRegOkMs;
    }

    public long remainingMs(long nowMs) {
        return mExpiresSec * 1000L - ageMs(nowMs);
    }

    /** Time from which the binding counts as lapsed. */
    public long lapseAtMs() {
        return mRegOkMs + mExpiresSec * 1000L - RefreshPolicy.LAPSE_MARGIN_MS;
    }

    public boolean isLapsed(long nowMs) {
        return mRegistered && nowMs >= lapseAtMs();
    }

    public RefreshPolicy.Window renewalWindow() {
        return RefreshPolicy.renewalWindow(mExpiresSec, mRefreshOverrideSec);
    }

    /** Until when the binding can carry an MO request, or 0 if it cannot. */
    public long usableUntilMs() {
        return mRegistered ? lapseAtMs() : 0;
    }

    /**
     * Time from which a scheduled fresh registration may stop the stack, or
     * {@code Long.MAX_VALUE}: the retry time, or later if the fresh-attempt
     * floor or a call hold keeps it back (no fresh registration starts before
     * then, so the binding stays usable). Its alarm fires up to a window
     * later; from this time MO requests wait for it.
     */
    public long freshDueAtMs() {
        return mRetryAtMs >= 0 ? Math.max(mRetryAtMs, freshNotBeforeMs()) : Long.MAX_VALUE;
    }

    /** One immutable view for MO callers on other threads. */
    public RegistrationSnapshot snapshot(long nowMs) {
        return new RegistrationSnapshot(usableUntilMs(), freshDueAtMs(), isRenewalPending(nowMs));
    }

    /** MO requests should wait: a fresh registration runs or is about to. */
    public boolean isRenewalPending(long nowMs) {
        return mInFlight == Op.FRESH || isLapsed(nowMs) || nowMs >= freshDueAtMs();
    }

    public void setRunning(boolean running) {
        mRunning = running;
    }

    /** Begins a fresh attempt; returns its generation. The old binding is gone with the old stack. */
    public int beginFresh(long nowMs) {
        mGeneration++;
        mRegistered = false;
        mInFlight = Op.FRESH;
        mInFlightSentMs = nowMs;
        mLastFreshMs = nowMs;
        mFreshDeferredUntilMs = -1;
        clearRetry();
        return mGeneration;
    }

    /** Holds a due fresh registration back, e.g. while a call needs the current stack. */
    public void deferFresh(long untilMs) {
        mFreshDeferredUntilMs = untilMs;
    }

    public void beginRefresh(long nowMs) {
        mInFlight = Op.REFRESH;
        mInFlightSentMs = nowMs;
    }

    /** A 200 OK on REGISTER: the binding is fresh for {@code expiresSec}. */
    public void onRegistered(long nowMs, int expiresSec, int refreshOverrideSec) {
        mRegistered = true;
        mRegOkMs = nowMs;
        mExpiresSec = expiresSec;
        mRefreshOverrideSec = refreshOverrideSec;
        mInFlight = Op.NONE;
        mRetryCount = 0;
        clearRetry();
    }

    /**
     * Schedules a fresh registration after a failed renewal of a live
     * binding. Returns false when the budget is spent or Retry-After exceeds
     * the cap; the caller then treats the failure as terminal.
     */
    public boolean scheduleRetry(long nowMs, int retryAfterSec, String reason) {
        if (mRetryCount >= RefreshPolicy.REG_MAX_RETRIES) {
            return false;
        }
        long atMs = RefreshPolicy.retryAtMs(nowMs, retryAfterSec, mLastFreshMs);
        if (atMs < 0) {
            return false;
        }
        mRetryCount++;
        mInFlight = Op.NONE;
        mRetryAtMs = atMs;
        mRetryWindowMs = RefreshPolicy.RETRY_WINDOW_MS;
        mRetryReason = reason;
        return true;
    }

    /**
     * Terminal failure: the binding is lost; try again after the long-tail
     * wait. Starts a new generation so a late 200 OK of the failed stack,
     * which is stopped shortly after, cannot mark it registered.
     */
    public void scheduleLongTail(long nowMs) {
        mGeneration++;
        mRegistered = false;
        mInFlight = Op.NONE;
        mRetryCount = 0;
        mRetryAtMs = nowMs + RefreshPolicy.LONG_TAIL_RETRY_MS;
        mRetryWindowMs = RefreshPolicy.LONG_TAIL_WINDOW_MS;
        mRetryReason = "long-tail retry";
    }

    /** An attempt ended without a REGISTER outcome (failover follows, or nothing was sent). */
    public void endAttempt() {
        mRegistered = false;
        mInFlight = Op.NONE;
    }

    /** Drops everything tied to the current stack; returns the new generation. */
    public int invalidate() {
        mGeneration++;
        mRegistered = false;
        mInFlight = Op.NONE;
        mRetryCount = 0;
        mFreshDeferredUntilMs = -1;
        clearRetry();
        return mGeneration;
    }

    /** Forgets pending retries so a new transport registers at once. */
    public void clearRecovery() {
        mRetryCount = 0;
        clearRetry();
    }

    private void clearRetry() {
        mRetryAtMs = -1;
        mRetryWindowMs = 0;
        mRetryReason = "";
    }

    /** One-line summary for dumpsys. */
    public String describe(long nowMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("gen=").append(mGeneration)
                .append(" running=").append(mRunning)
                .append(" registered=").append(mRegistered)
                .append(" inFlight=").append(mInFlight);
        if (mInFlight != Op.NONE) {
            sb.append(" inFlightAgeS=").append((nowMs - mInFlightSentMs) / 1000);
        }
        if (mRegistered) {
            sb.append(" ageS=").append(ageMs(nowMs) / 1000)
                    .append(" remainingS=").append(remainingMs(nowMs) / 1000)
                    .append(" expiresS=").append(mExpiresSec);
        }
        if (mRetryAtMs >= 0) {
            sb.append(" retry=").append(mRetryReason)
                    .append(" inS=").append((mRetryAtMs - nowMs) / 1000);
        }
        sb.append(" retries=").append(mRetryCount).append('/').append(RefreshPolicy.REG_MAX_RETRIES);
        if (mLastFreshMs >= 0) {
            sb.append(" lastFreshAgoS=").append((nowMs - mLastFreshMs) / 1000);
        }
        if (mFreshDeferredUntilMs > nowMs) {
            sb.append(" freshDeferredS=").append((mFreshDeferredUntilMs - nowMs) / 1000);
        }
        return sb.toString();
    }
}
