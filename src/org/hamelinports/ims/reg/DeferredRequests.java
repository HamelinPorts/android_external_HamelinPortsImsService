package org.hamelinports.ims.reg;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * MO requests held back while the registration is renewed. Each request is
 * released when no renewal is pending, expires {@link OutgoingGate#MAX_DEFERRAL_MS}
 * after it was queued, or is dropped once cancelled. Thread-safe: requests
 * are added and cancelled from binder threads and taken on the registration
 * thread.
 */
public final class DeferredRequests {

    /** A queued request; {@link #cancel()} guarantees neither callback runs afterwards. */
    public final class Entry {
        private final Runnable mOnReady;
        private final Runnable mOnExpired;
        private final long mDeadlineMs;

        private Entry(Runnable onReady, Runnable onExpired, long deadlineMs) {
            mOnReady = onReady;
            mOnExpired = onExpired;
            mDeadlineMs = deadlineMs;
        }

        public long deadlineMs() {
            return mDeadlineMs;
        }

        /** Removes the request; a no-op once it was taken. */
        public void cancel() {
            synchronized (DeferredRequests.this) {
                mEntries.remove(this);
            }
        }
    }

    private final List<Entry> mEntries = new ArrayList<>();

    public synchronized Entry add(Runnable onReady, Runnable onExpired, long nowMs) {
        Entry e = new Entry(onReady, onExpired, nowMs + OutgoingGate.MAX_DEFERRAL_MS);
        mEntries.add(e);
        return e;
    }

    /**
     * Removes and returns the callbacks due now: every request's ready
     * callback when no renewal is pending, else the expired callback of each
     * request past its deadline. The caller runs them outside this lock.
     */
    public synchronized List<Runnable> takeDue(long nowMs, boolean renewalPending) {
        List<Runnable> due = new ArrayList<>();
        for (Iterator<Entry> it = mEntries.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (!renewalPending) {
                it.remove();
                due.add(e.mOnReady);
            } else if (nowMs >= e.mDeadlineMs) {
                it.remove();
                due.add(e.mOnExpired);
            }
        }
        return due;
    }

    /** Earliest deadline of a queued request, or -1 if none is queued. */
    public synchronized long nextDeadlineMs() {
        long next = -1;
        for (Entry e : mEntries) {
            if (next < 0 || e.mDeadlineMs < next) next = e.mDeadlineMs;
        }
        return next;
    }

    public synchronized int size() {
        return mEntries.size();
    }
}
