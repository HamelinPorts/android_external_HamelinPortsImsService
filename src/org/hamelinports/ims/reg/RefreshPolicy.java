package org.hamelinports.ims.reg;

/**
 * Timing rules for keeping an IMS registration alive across deep sleep.
 *
 * <p>All times are milliseconds on the elapsed-realtime clock (since boot,
 * including suspend). Pure Java, no {@code android.*}, so the host tests
 * drive it directly.
 *
 * <p>The network answers every in-dialog refresh with {@code 503} and a
 * {@code Retry-After} of up to 599 s, so renewal in practice is a fresh
 * registration after that wait. The renewal window therefore ends 900 s
 * before expiry, which leaves at least 300 s after the longest observed wait.
 */
public final class RefreshPolicy {

    /** What the registration state machine should do now. */
    public enum Action {
        /** Nothing to do; an outcome is pending or only an external event can help. */
        NOOP,
        /** Nothing to do now; (re)arm the check alarm from {@link #nextCheck}. */
        REARM,
        /** Send an in-dialog REGISTER refresh. */
        REFRESH,
        /** Stop the stack, drop the SAs and register from scratch. */
        FRESH,
        /**
         * The outstanding transaction is overdue (it stalled in suspend or
         * the stack is stuck); handle it as failed, which goes through the
         * retry budget or the long-tail wait.
         */
        TIMED_OUT,
    }

    /** An alarm window on the elapsed-realtime clock. */
    public static final class Window {
        public final long startMs;
        public final long lengthMs;

        Window(long startMs, long lengthMs) {
            this.startMs = startMs;
            this.lengthMs = lengthMs;
        }

        public long endMs() {
            return startMs + lengthMs;
        }

        @Override
        public String toString() {
            return "[" + startMs + "+" + lengthMs + "]";
        }
    }

    /** A registration with less than this left counts as lapsed: the P-CSCF drops the SAs at expiry. */
    public static final long LAPSE_MARGIN_MS = 30_000;
    /** Floor between two fresh registrations started by the policy; UID 1000 alarms are not throttled. */
    public static final long MIN_FRESH_INTERVAL_MS = 60_000;
    /** Wakelock bound for a refresh: Timer F (64 * T1 = 32 s) plus slack. */
    public static final long REFRESH_WAKELOCK_MS = 40_000;
    /** Wakelock bound for a fresh registration: probe connect, two REGISTER transactions, AKA, SA install. */
    public static final long FRESH_WAKELOCK_MS = 80_000;
    /** Retry budget for an active registration before it is reported lost. */
    public static final int REG_MAX_RETRIES = 3;
    /** Recovery cadence after a terminal failure. */
    public static final long LONG_TAIL_RETRY_MS = 30 * 60_000L;
    /** Smallest accepted test override of the refresh point. */
    public static final int MIN_REFRESH_OVERRIDE_SEC = 30;
    /** Recheck interval for a fresh registration held back by an active call. */
    public static final long ACTIVE_CALL_RECHECK_MS = 60_000;

    static final long RENEWAL_MARGIN_MS = 900_000;
    static final long MAX_RENEWAL_WINDOW_MS = 300_000;
    static final long RETRY_WINDOW_MS = 10_000;
    static final long LONG_TAIL_WINDOW_MS = 60_000;
    static final int DEFAULT_RETRY_AFTER_SEC = 30;
    static final int MIN_RETRY_AFTER_SEC = 5;
    /** A longer Retry-After is treated as "server gone": terminal failure instead of a retry. */
    static final int MAX_RETRY_AFTER_SEC = 86_400;

    private RefreshPolicy() {}

    /**
     * Latest re-registration point per 3GPP TS 24.229 §5.1.1.4.1, relative to
     * the 200 OK: 600 s before expiry for {@code expires > 1200}, else half-time.
     */
    public static long deadlineMs(int expiresSec) {
        long expiresMs = expiresSec * 1000L;
        return expiresSec > 1200 ? expiresMs - 600_000 : expiresMs / 2;
    }

    /**
     * Renewal window relative to the 200 OK. It ends at
     * {@code max(E - 900 s, E / 2)}, which never passes {@link #deadlineMs},
     * and is {@code min(300 s, end / 4)} long. For E = 3600 s that is
     * [2400 s, 2700 s]. A valid override moves the end earlier (tests only).
     */
    public static Window renewalWindow(int expiresSec, int overrideSec) {
        long expiresMs = expiresSec * 1000L;
        long endMs = Math.max(expiresMs - RENEWAL_MARGIN_MS, expiresMs / 2);
        if (overrideSec >= MIN_REFRESH_OVERRIDE_SEC && overrideSec * 1000L < endMs) {
            endMs = overrideSec * 1000L;
        }
        long lengthMs = Math.min(MAX_RENEWAL_WINDOW_MS, endMs / 4);
        return new Window(endMs - lengthMs, lengthMs);
    }

    /** Wakelock bound for an operation in flight. */
    public static long wakelockBoundMs(RegistrationState.Op op) {
        switch (op) {
            case REFRESH:
                return REFRESH_WAKELOCK_MS;
            case FRESH:
                return FRESH_WAKELOCK_MS;
            default:
                return 0;
        }
    }

    /** Earliest time the policy may start the next fresh registration. */
    public static long freshFloorMs(long lastFreshMs) {
        return lastFreshMs < 0 ? Long.MIN_VALUE : lastFreshMs + MIN_FRESH_INTERVAL_MS;
    }

    /**
     * When to register fresh after a failed renewal: at least
     * {@code Retry-After} (RFC 3261 §21.5.4; default 30 s, minimum 5 s),
     * never before the fresh-attempt floor. Returns -1 if Retry-After exceeds
     * the sanity cap.
     */
    public static long retryAtMs(long nowMs, int retryAfterSec, long lastFreshMs) {
        int waitSec = retryAfterSec > 0 ? retryAfterSec : DEFAULT_RETRY_AFTER_SEC;
        if (waitSec > MAX_RETRY_AFTER_SEC) {
            return -1;
        }
        waitSec = Math.max(waitSec, MIN_RETRY_AFTER_SEC);
        return Math.max(nowMs + waitSec * 1000L, freshFloorMs(lastFreshMs));
    }

    /**
     * The registration age rule. Order matters: a pending outcome first, then
     * a pending retry, then the age of the binding. A due fresh registration
     * waits for {@link RegistrationState#freshNotBeforeMs()}.
     */
    public static Action decide(RegistrationState s, long nowMs) {
        if (!s.isRunning()) {
            return Action.NOOP;
        }
        boolean freshAllowed = nowMs >= s.freshNotBeforeMs();
        if (s.inFlight() != RegistrationState.Op.NONE) {
            return nowMs - s.inFlightSentMs() < wakelockBoundMs(s.inFlight())
                    ? Action.NOOP : Action.TIMED_OUT;
        }
        if (s.retryAtMs() >= 0) {
            /* Honour Retry-After even if the binding runs out meanwhile. */
            return nowMs >= s.retryAtMs() && freshAllowed ? Action.FRESH : Action.REARM;
        }
        if (!s.isRegistered()) {
            return Action.NOOP;
        }
        if (s.isLapsed(nowMs)) {
            return freshAllowed ? Action.FRESH : Action.REARM;
        }
        if (s.ageMs(nowMs) >= s.renewalWindow().startMs) {
            return Action.REFRESH;
        }
        return Action.REARM;
    }

    /** When the state machine must run next, or null if only external events can move it. */
    public static Window nextCheck(RegistrationState s, long nowMs) {
        if (!s.isRunning()) {
            return null;
        }
        long floorMs = s.freshNotBeforeMs();
        if (s.inFlight() != RegistrationState.Op.NONE) {
            return new Window(s.inFlightSentMs() + wakelockBoundMs(s.inFlight()),
                    RETRY_WINDOW_MS);
        }
        if (s.retryAtMs() >= 0) {
            return new Window(Math.max(s.retryAtMs(), floorMs), s.retryWindowMs());
        }
        if (!s.isRegistered()) {
            return null;
        }
        if (s.isLapsed(nowMs)) {
            return new Window(Math.max(nowMs, floorMs), RETRY_WINDOW_MS);
        }
        Window w = s.renewalWindow();
        if (s.ageMs(nowMs) < w.startMs) {
            return new Window(s.regOkMs() + w.startMs, w.lengthMs);
        }
        return new Window(s.lapseAtMs(), RETRY_WINDOW_MS);
    }
}
