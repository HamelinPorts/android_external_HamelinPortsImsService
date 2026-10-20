package org.hamelinports.ims.call;

/**
 * Lifecycle of one incoming (MT) call and what each event leads to.
 *
 * <p>The framework hears of the call's end exactly once: the first terminal
 * event (local hang-up, reject, remote cancel, PDN loss, the dialog's
 * termination or failure) ends it, and every later one is ignored. A failure
 * of an accepted dialog also ends the dialog on the network, since it may
 * still be up there. A "failure" with a success status cannot happen on a
 * correctly dispatched listener and is refused without changing anything.
 *
 * <p>Pure Java, so the host tests drive it directly. Thread-safe: listener
 * events arrive on the SIP stack thread, framework calls on binder threads.
 */
public final class MtCallState {

    /** What the session does for a listener event. */
    public enum Action {
        /** Nothing: a duplicate or late event. */
        NONE,
        /** An impossible event (e.g. onFailure with a success status): log an error only. */
        REFUSE,
        /** The call is up: report it initiated and start media. */
        ESTABLISH,
        /** Release media and report the call terminated. */
        TERMINATE,
        /** End the dialog on the network (BYE), release media, report the call terminated. */
        END_DIALOG_AND_TERMINATE,
    }

    private enum Phase { OFFERED, ACCEPTED, ESTABLISHED, TERMINATED }

    private Phase mPhase = Phase.OFFERED;
    private boolean mAccepted;

    /** The user accepted. Returns true the first time, while the call is still offered. */
    public synchronized boolean accept() {
        if (mPhase != Phase.OFFERED) return false;
        mPhase = Phase.ACCEPTED;
        mAccepted = true;
        return true;
    }

    /** The call was accepted at some point (it may have ended since). */
    public synchronized boolean isAccepted() {
        return mAccepted;
    }

    public synchronized boolean isTerminated() {
        return mPhase == Phase.TERMINATED;
    }

    /**
     * A local end (hang-up, reject, accept error, remote cancel, PDN loss).
     * Returns true if this ends the call, i.e. the caller reports it.
     */
    public synchronized boolean endLocally() {
        if (mPhase == Phase.TERMINATED) return false;
        mPhase = Phase.TERMINATED;
        return true;
    }

    /** The dialog is up (our 200 OK was sent). */
    public synchronized Action onConnected() {
        switch (mPhase) {
            case ACCEPTED:
                mPhase = Phase.ESTABLISHED;
                return Action.ESTABLISH;
            case OFFERED:
                return Action.REFUSE;
            default:
                return Action.NONE;
        }
    }

    /** The dialog failed with a final {@code sipStatus}. */
    public synchronized Action onFailure(int sipStatus) {
        if (!SipStatus.isFailure(sipStatus)) return Action.REFUSE;
        if (mPhase == Phase.TERMINATED) return Action.NONE;
        mPhase = Phase.TERMINATED;
        return mAccepted ? Action.END_DIALOG_AND_TERMINATE : Action.TERMINATE;
    }

    /** The dialog ended (BYE, CANCEL, timeout). */
    public synchronized Action onTerminated() {
        if (mPhase == Phase.TERMINATED) return Action.NONE;
        mPhase = Phase.TERMINATED;
        return Action.TERMINATE;
    }
}
