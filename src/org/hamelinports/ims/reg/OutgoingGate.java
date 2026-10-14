package org.hamelinports.ims.reg;

/**
 * Rules for MO requests (calls, SMS) while the registration is being
 * renewed. Pure Java, so the host tests drive it directly.
 */
public final class OutgoingGate {

    /** What to do with an MO request now. */
    public enum Decision {
        /** Send it on the current stack. */
        SEND,
        /** Hold it until the renewal outcome, at most {@link #MAX_DEFERRAL_MS}. */
        DEFER,
        /** Fail it now so telephony can use the circuit-switched domain. */
        FAIL,
    }

    /**
     * Longest an MO request waits for a renewal. A fresh registration takes
     * about 2 s (probe connect, two REGISTER round trips, USIM AKA, SA
     * install); 20 s leaves ample slack for that and is still a tolerable
     * call-setup delay. A renewal that needs longer is stuck behind a
     * Retry-After or a timeout, and the CS domain is the faster route.
     */
    public static final long MAX_DEFERRAL_MS = 20_000;

    /**
     * Shortest granted registration the policy can keep alive: 120 s, or the
     * requested value if a test asked for less. A grant is used as granted
     * (TS 24.229: half-time renewal at or below 1200 s). Below
     * 2 * LAPSE_MARGIN + MIN_FRESH_INTERVAL the binding lapses before the
     * fresh-attempt floor allows the next registration, so every cycle has a
     * gap and costs a fresh AKA registration; such a grant is treated as a
     * failed registration (long-tail retry, CS meanwhile). Lapse detection
     * must use the real expiry, so a short grant is never stretched.
     */
    public static final int MIN_GRANTED_EXPIRES_SEC = (int)
            ((2 * RefreshPolicy.LAPSE_MARGIN_MS + RefreshPolicy.MIN_FRESH_INTERVAL_MS) / 1000);

    private OutgoingGate() {}

    /**
     * MO call. An emergency call is never held: it goes out at once if the
     * binding is usable, else it fails so telephony redials it over CS.
     * Other calls wait for a pending renewal; without one they take the
     * normal path, where the stack reports the outcome.
     */
    public static Decision decideCall(boolean emergency, boolean usable, boolean renewalPending) {
        if (usable) {
            return Decision.SEND;
        }
        if (emergency) {
            return Decision.FAIL;
        }
        return renewalPending ? Decision.DEFER : Decision.SEND;
    }

    /**
     * MO SMS. {@code mayDefer} is false when the request already waited once,
     * which bounds the total wait to one deferral.
     */
    public static Decision decideSms(boolean usable, boolean renewalPending, boolean mayDefer) {
        if (usable) {
            return Decision.SEND;
        }
        return renewalPending && mayDefer ? Decision.DEFER : Decision.FAIL;
    }

    /**
     * Whether a call needs the current SIP stack, so a policy-driven fresh
     * registration must wait for it. A normal MO session that has not sent
     * its INVITE does not: it waits for that registration itself. An
     * emergency MO session does from its creation: it never waits, so the
     * stack must not be stopped under its setup.
     */
    public static boolean callHoldsStack(boolean moSession, boolean moInviteSent,
                                         boolean moEmergency, boolean mtSession) {
        return (moSession && (moInviteSent || moEmergency)) || mtSession;
    }

    /**
     * Whether the binding can carry a normal MO request now: not lapsed and
     * no fresh registration due. Callers on other threads evaluate it from
     * published times, so a retry that came due since the last publish counts
     * at once.
     */
    public static boolean isUsable(long nowMs, long usableUntilMs, long freshDueAtMs) {
        return nowMs < usableUntilMs && nowMs < freshDueAtMs;
    }

    /** Whether a granted Expires is long enough to schedule renewals on. */
    public static boolean isGrantedExpiresAcceptable(int grantedSec, int requestedSec) {
        return grantedSec >= Math.min(MIN_GRANTED_EXPIRES_SEC, requestedSec);
    }
}
