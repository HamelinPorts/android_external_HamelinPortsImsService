package org.hamelinports.ims.sip;

/**
 * The MO SMS awaiting its RP-ACK or RP-ERROR, and the one outcome it gets.
 *
 * <p>Whatever completes first (RP-ACK, RP-ERROR, a SIP failure, the
 * optimistic-success timeout) clears the send, so every later event for it
 * is ignored: a token is completed at most once, and nothing falls back after
 * an RP-ACK or the timeout. Only an RP-ERROR whose RP-MR equals the pending
 * one can give RETRY or FALLBACK.
 *
 * <p>Pure Java, so the host tests drive it directly. Thread-safe.
 */
public final class PendingMoSms {

    /** The completion of a send, or why an event was ignored. */
    public static final class Outcome {
        public final int token;
        public final int messageRef;
        public final RpErrorPolicy.Status status;
        /** RP-Cause for an RP-ERROR, the SIP status for a SIP failure, else 0. */
        public final int networkCode;
        /** Non-null if the event completed nothing. */
        public final String ignored;

        private Outcome(int token, int messageRef, RpErrorPolicy.Status status, int networkCode,
                String ignored) {
            this.token = token;
            this.messageRef = messageRef;
            this.status = status;
            this.networkCode = networkCode;
            this.ignored = ignored;
        }

        static Outcome ignored(String why) {
            return new Outcome(-1, -1, null, 0, why);
        }
    }

    private static final int NONE = -1;

    private int mToken = NONE;
    private int mMessageRef = NONE;
    private int mRpMr = NONE;

    /**
     * Starts tracking a send. Returns the token it supersedes without an
     * outcome, or -1 (only one send is tracked).
     */
    public synchronized int begin(int token, int messageRef, int rpMr) {
        int superseded = mToken;
        mToken = token;
        mMessageRef = messageRef;
        mRpMr = rpMr;
        return superseded;
    }

    public synchronized boolean isPending(int token) {
        return mToken != NONE && mToken == token;
    }

    /** RP-ACK: the SMSC stored the message. */
    public synchronized Outcome onRpAck(int rpMr) {
        String why = mismatch("RP-ACK", rpMr);
        if (why != null) return Outcome.ignored(why);
        return complete(RpErrorPolicy.Status.OK, 0);
    }

    /** RP-ERROR: the SMSC did not store the message; the policy picks the status. */
    public synchronized Outcome onRpError(RpError error, RpErrorPolicy policy) {
        String why = mismatch("RP-ERROR", error.rpMr);
        if (why != null) return Outcome.ignored(why);
        RpErrorPolicy.Status status = mRpMr == NONE
                ? RpErrorPolicy.Status.ERROR : policy.statusFor(error.cause);
        return complete(status, error.cause);
    }

    /** A SIP final failure for the MESSAGE (no RP outcome). */
    public synchronized Outcome onSipFailure(int sipStatus) {
        if (mToken == NONE) return Outcome.ignored("SIP failure with no pending MO");
        return complete(RpErrorPolicy.Status.ERROR, sipStatus);
    }

    /** No RP outcome in time after the SIP 2xx: optimistic success, if still pending. */
    public synchronized Outcome onTimeout(int token) {
        if (mToken == NONE || mToken != token) {
            return Outcome.ignored("timeout of token " + token + " already completed");
        }
        return complete(RpErrorPolicy.Status.OK, 0);
    }

    /** The MESSAGE could not be queued. */
    public synchronized Outcome onNotSent(int token) {
        if (mToken != token) return Outcome.ignored("token " + token + " not pending");
        return complete(RpErrorPolicy.Status.ERROR, 0);
    }

    private String mismatch(String what, int rpMr) {
        if (mToken == NONE) {
            return what + " RP-MR=" + rpMr + " with no pending MO (already completed?)";
        }
        if (mRpMr != NONE && rpMr != mRpMr) {
            return what + " RP-MR=" + rpMr + " != pending " + mRpMr;
        }
        return null;
    }

    private Outcome complete(RpErrorPolicy.Status status, int networkCode) {
        Outcome o = new Outcome(mToken, mMessageRef, status, networkCode, null);
        mToken = NONE;
        mMessageRef = NONE;
        mRpMr = NONE;
        return o;
    }
}
