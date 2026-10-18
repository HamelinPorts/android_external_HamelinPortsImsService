package org.hamelinports.ims.reg;

/**
 * Detects a dead IWLAN data path and decides when to report it, so QNS
 * moves IMS to cellular while the tunnel itself still looks up.
 *
 * <p>Evidence is a transport-class failure while IMS runs over IWLAN: a
 * REGISTER failure the stack generated itself (503 or 408, no response from
 * the network) or a P-CSCF probe that timed out or found no route. Any
 * response from the network, a working probe or an iface change clears it.
 * {@value #FAILURES_TO_REPORT} failures at least
 * {@value #MIN_FAILURE_SPACING_MS} ms apart report once, unless WLAN is not
 * CONNECTED, a call needs the stack, or the last report is younger than
 * {@value #MIN_REPORT_INTERVAL_MS} ms. A report stays pending for
 * {@value #PENDING_MS} ms: taken if IMS moves off IWLAN by then, else not
 * taken. Every report needs new evidence.
 *
 * <p>All times are on the elapsed-realtime clock. Pure Java, so the host
 * tests drive it directly. Not thread-safe: owned by the registration thread.
 */
public final class IwlanFallbackTracker {
    public static final int FAILURES_TO_REPORT = 2;
    public static final long MIN_FAILURE_SPACING_MS = 30_000;
    public static final long MIN_REPORT_INTERVAL_MS = 120_000;
    public static final long PENDING_MS = 60_000;

    /** Outcome of the TCP probe to the P-CSCF before a fresh REGISTER. */
    public enum ProbeResult { CONNECTED, REFUSED, TIMED_OUT, UNREACHABLE, OTHER }

    /** What a transport failure leads to. */
    public enum Decision {
        /** Not counted: the fallback is off, IMS is not on IWLAN, or a report is pending. */
        IGNORED,
        /** Counted; below the threshold. */
        COUNTED,
        /** Less than {@link #MIN_FAILURE_SPACING_MS} after the last counted failure; not counted. */
        TOO_SOON,
        HELD_WLAN_NOT_CONNECTED,
        HELD_CALL_ON_STACK,
        HELD_RATE_LIMIT,
        /** Report the failed data path now. */
        REPORT,
    }

    private int mFailures;
    private long mLastFailureMs = -1;
    private long mLastReportMs = -1;
    private long mPendingUntilMs = -1;
    private int mReports;
    private int mTaken;
    private int mNotTaken;

    /** A REGISTER failure the stack generated without a network response. */
    public static boolean isLocalTransportFailure(int statusCode, boolean fromWire) {
        return !fromWire && (statusCode == 503 || statusCode == 408);
    }

    public static boolean isTransportFailure(ProbeResult result) {
        return result == ProbeResult.TIMED_OUT || result == ProbeResult.UNREACHABLE;
    }

    /** The probe reached the P-CSCF (a refusal is an answer too). */
    public static boolean provesPath(ProbeResult result) {
        return result == ProbeResult.CONNECTED || result == ProbeResult.REFUSED;
    }

    /**
     * Counts a transport failure and decides whether to report.
     *
     * @param enabled       the fallback property is on
     * @param onIwlan       IMS is bound to the IWLAN tunnel
     * @param wlanConnected the IMS PDN on the WLAN transport is CONNECTED
     * @param callOnStack   a call (emergency included) needs the current stack
     */
    public Decision onTransportFailure(long nowMs, boolean enabled, boolean onIwlan,
            boolean wlanConnected, boolean callOnStack) {
        if (!enabled || !onIwlan) {
            clearEvidence();
            return Decision.IGNORED;
        }
        if (isPending(nowMs)) {
            return Decision.IGNORED;
        }
        if (mLastFailureMs >= 0 && nowMs - mLastFailureMs < MIN_FAILURE_SPACING_MS) {
            return Decision.TOO_SOON;
        }
        mFailures = Math.min(mFailures + 1, FAILURES_TO_REPORT);
        mLastFailureMs = nowMs;
        if (mFailures < FAILURES_TO_REPORT) {
            return Decision.COUNTED;
        }
        if (!wlanConnected) {
            return Decision.HELD_WLAN_NOT_CONNECTED;
        }
        if (callOnStack) {
            return Decision.HELD_CALL_ON_STACK;
        }
        if (mLastReportMs >= 0 && nowMs - mLastReportMs < MIN_REPORT_INTERVAL_MS) {
            return Decision.HELD_RATE_LIMIT;
        }
        mReports++;
        mLastReportMs = nowMs;
        mPendingUntilMs = nowMs + PENDING_MS;
        clearEvidence();
        return Decision.REPORT;
    }

    /** The network answered (any response) or the probe reached the P-CSCF. */
    public void onPathProven() {
        clearEvidence();
    }

    /**
     * The IMS PDN moved to another iface. Returns true if this takes a
     * pending report, i.e. IMS left IWLAN in time.
     */
    public boolean onIfaceChanged(long nowMs, boolean nowOnIwlan) {
        clearEvidence();
        if (!nowOnIwlan && isPending(nowMs)) {
            mPendingUntilMs = -1;
            mTaken++;
            return true;
        }
        return false;
    }

    /** Returns true once when a pending report ran out without IMS leaving IWLAN. */
    public boolean expirePending(long nowMs) {
        if (mPendingUntilMs < 0 || nowMs < mPendingUntilMs) {
            return false;
        }
        mPendingUntilMs = -1;
        mNotTaken++;
        return true;
    }

    /** The registration machine stopped: drop evidence and any pending report. */
    public void reset() {
        clearEvidence();
        mPendingUntilMs = -1;
    }

    public boolean isPending(long nowMs) {
        return mPendingUntilMs >= 0 && nowMs < mPendingUntilMs;
    }

    /** End of the pending report, or -1. */
    public long pendingUntilMs() {
        return mPendingUntilMs;
    }

    public int failures() { return mFailures; }
    public int reports() { return mReports; }
    public int taken() { return mTaken; }
    public int notTaken() { return mNotTaken; }

    /** One-line summary for dumpsys. */
    public String describe(long nowMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("failures=").append(mFailures).append('/').append(FAILURES_TO_REPORT)
                .append(" reports=").append(mReports)
                .append(" taken=").append(mTaken)
                .append(" notTaken=").append(mNotTaken)
                .append(" lastReport=");
        if (mLastReportMs < 0) {
            sb.append("never");
        } else {
            sb.append((nowMs - mLastReportMs) / 1000).append("s ago");
        }
        if (isPending(nowMs)) {
            sb.append(" pendingS=").append((mPendingUntilMs - nowMs) / 1000);
        }
        return sb.toString();
    }

    private void clearEvidence() {
        mFailures = 0;
        mLastFailureMs = -1;
    }
}
