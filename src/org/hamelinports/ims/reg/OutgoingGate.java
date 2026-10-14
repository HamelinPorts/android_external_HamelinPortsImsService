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
     * Shortest granted registration the policy accepts: 600 s, or the
     * requested value if a test asked for less. Lapse detection must use the
     * real expiry, so a shorter grant cannot be stretched; at 600 s the
     * half-time rule renews every 300 s at most (12 per hour), while a
     * shorter grant would mean a fresh AKA registration every minute.
     * Below it the registration is treated as failed and retried after the
     * long-tail wait; meanwhile telephony uses the CS domain.
     */
    public static final int MIN_GRANTED_EXPIRES_SEC = 600;

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
     * registration must wait for it. An MO session that has not sent its
     * INVITE does not: it is itself waiting for that registration.
     */
    public static boolean callHoldsStack(boolean moSession, boolean moInviteSent,
                                         boolean mtSession) {
        return (moSession && moInviteSent) || mtSession;
    }

    /** Whether a granted Expires is long enough to schedule renewals on. */
    public static boolean isGrantedExpiresAcceptable(int grantedSec, int requestedSec) {
        return grantedSec >= Math.min(MIN_GRANTED_EXPIRES_SEC, requestedSec);
    }
}
