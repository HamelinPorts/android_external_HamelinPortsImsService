package org.hamelinports.ims.reg;

/**
 * When the IMS PDN watchdog forces a teardown. It runs once per episode in
 * which no transport has the IMS PDN CONNECTED: it checks after
 * {@value #CHECK_MS} ms, keeps re-checking while a transport is still
 * connecting or handing over, up to {@value #BRINGUP_BOUND_MS} ms into the
 * episode, then tears down. It is never armed while an IWLAN fallback report
 * is pending: re-requesting the network could release the IMS PDN, and a PDN
 * disconnect lifts the Wi-Fi restriction QNS just applied.
 *
 * <p>All times are on the elapsed-realtime clock. Pure Java, so the host
 * tests drive it directly.
 */
public final class ImsPdnWatchdogPolicy {
    public static final long CHECK_MS = 15_000;
    public static final long BRINGUP_BOUND_MS = 90_000;

    /** What the watchdog does when its alarm fires. */
    public enum Verdict {
        /** Nothing: a transport is CONNECTED again or a fallback report is pending. */
        STAND_DOWN,
        /** A bring-up or handover is still running: check again at {@link #recheckAtMs}. */
        RECHECK,
        /** Tear down and request the IMS network again. */
        TEAR_DOWN,
    }

    private ImsPdnWatchdogPolicy() {}

    public static boolean shouldArm(boolean anyConnected, boolean armed, boolean fallbackPending) {
        return !anyConnected && !armed && !fallbackPending;
    }

    /**
     * @param episodeStartMs   when the watchdog was armed for this episode
     * @param bringupInProgress a transport is CONNECTING or HANDOVER_IN_PROGRESS
     */
    public static Verdict onFire(long nowMs, long episodeStartMs, boolean anyConnected,
            boolean bringupInProgress, boolean fallbackPending) {
        if (anyConnected || fallbackPending) {
            return Verdict.STAND_DOWN;
        }
        if (bringupInProgress && nowMs - episodeStartMs < BRINGUP_BOUND_MS) {
            return Verdict.RECHECK;
        }
        return Verdict.TEAR_DOWN;
    }

    /** Next check of a running bring-up: one interval on, never past the bound. */
    public static long recheckAtMs(long nowMs, long episodeStartMs) {
        return Math.min(nowMs + CHECK_MS, episodeStartMs + BRINGUP_BOUND_MS);
    }
}
