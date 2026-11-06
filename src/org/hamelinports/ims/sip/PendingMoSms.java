package org.hamelinports.ims.sip;

/**
 * Concurrent MO sends, each awaiting its own RP-ACK or RP-ERROR.
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

    /** Keep completed references unavailable for TR1M to reject delayed replies. */
    private static final long RETIRE_MS = 130_000L;
    private final java.util.Map<Integer, Outcome> mPending = new java.util.HashMap<>();
    private final long[] mRetiredUntil = new long[256];
    private final java.util.function.LongSupplier mClock;
    private int mNext = 1;

    public PendingMoSms() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    PendingMoSms(java.util.function.LongSupplier clock) {
        mClock = clock;
    }

    /** Returns an unused RP-MR in 1..255, or -1 without disturbing any pending send. */
    public synchronized int begin(int token, int messageRef) {
        if (isPending(token)) return -1;
        long now = mClock.getAsLong();
        for (int n = 0; n < 255; n++) {
            int rpMr = mNext;
            mNext = mNext == 255 ? 1 : mNext + 1;
            if (!mPending.containsKey(rpMr) && now >= mRetiredUntil[rpMr]) {
                mPending.put(rpMr, new Outcome(token, messageRef, null, 0, null));
                return rpMr;
            }
        }
        return -1;
    }

    public synchronized boolean isPending(int token) {
        return rpMrFor(token) != -1;
    }

    public synchronized Outcome onRpAck(int rpMr) {
        return complete(rpMr, RpErrorPolicy.Status.OK, 0);
    }

    public synchronized Outcome onRpError(RpError error, RpErrorPolicy policy) {
        return complete(error.rpMr, policy.statusFor(error.cause), error.cause);
    }

    public synchronized Outcome onSipFailure(int rpMr, int sipStatus) {
        return complete(rpMr, RpErrorPolicy.Status.ERROR, sipStatus);
    }

    public synchronized Outcome onTimeout(int token, int rpMr) {
        Outcome pending = mPending.get(rpMr);
        if (pending == null || pending.token != token) {
            return Outcome.ignored("timeout of completed token=" + token + " RP-MR=" + rpMr);
        }
        return complete(rpMr, RpErrorPolicy.Status.OK, 0);
    }

    public synchronized Outcome onNotSent(int token) {
        return complete(rpMrFor(token), RpErrorPolicy.Status.ERROR, 0);
    }

    private int rpMrFor(int token) {
        for (java.util.Map.Entry<Integer, Outcome> entry : mPending.entrySet()) {
            if (entry.getValue().token == token) return entry.getKey();
        }
        return -1;
    }

    private Outcome complete(int rpMr, RpErrorPolicy.Status status, int code) {
        Outcome pending = mPending.remove(rpMr);
        if (pending == null) return Outcome.ignored("RP-MR=" + rpMr + " has no pending MO");
        mRetiredUntil[rpMr] = mClock.getAsLong() + RETIRE_MS;
        return new Outcome(pending.token, pending.messageRef, status, code, null);
    }
}
