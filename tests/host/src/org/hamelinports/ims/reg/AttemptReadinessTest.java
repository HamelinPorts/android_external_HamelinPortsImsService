package org.hamelinports.ims.reg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.hamelinports.ims.reg.AttemptReadiness.Blocker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AttemptReadinessTest {
    private static final long S = 1000L;
    private static final long T0 = 5_000_000L;
    /** Network.getNetworkHandle() of netId 101: netId << 32 | 0xcafed00d. */
    private static final long IMS_HANDLE = (101L << 32) | 0xcafed00dL;

    @Test
    public void unsetHandleBlocksEvenWhenEverythingElseIsReady() {
        assertEquals(Blocker.NO_NETWORK,
                AttemptReadiness.blocker(AttemptReadiness.NO_NETWORK_HANDLE, 2, true));
        assertEquals(0L, AttemptReadiness.NO_NETWORK_HANDLE);
    }

    @Test
    public void eachMissingInputBlocks() {
        assertEquals(Blocker.NO_PCSCF, AttemptReadiness.blocker(IMS_HANDLE, 0, true));
        assertEquals(Blocker.NO_CREDENTIALS, AttemptReadiness.blocker(IMS_HANDLE, 1, false));
        assertEquals(Blocker.NO_NETWORK,
                AttemptReadiness.blocker(AttemptReadiness.NO_NETWORK_HANDLE, 0, false));
    }

    @Test
    public void readyAttemptIsSent() {
        assertEquals(Blocker.NONE, AttemptReadiness.blocker(IMS_HANDLE, 1, true));
        assertEquals(Blocker.NONE, AttemptReadiness.blocker(IMS_HANDLE, 3, true));
        /* A netId above 2^31 makes the handle negative; it is still a network. */
        assertEquals(Blocker.NONE,
                AttemptReadiness.blocker((0x80000001L << 32) | 0xcafed00dL, 1, true));
    }

    /**
     * Mirrors ImsRegistrationController.startFreshRegistration: an attempt
     * that is not sent ends, then the bounded retry, then the long tail.
     */
    private static final class Attempts {
        final RegistrationState state = new RegistrationState();
        long networkHandle = AttemptReadiness.NO_NETWORK_HANDLE;
        int sent;
        int notSent;
        int longTails;

        Attempts() {
            state.setRunning(true);
        }

        void fresh(long now) {
            state.beginFresh(now);
            if (AttemptReadiness.blocker(networkHandle, 1, true) == Blocker.NONE) {
                sent++;
                state.onRegistered(now + 2 * S, 3600, 0);
                return;
            }
            notSent++;
            state.endAttempt();
            if (!state.scheduleRetry(now, 0, "attempt retry")) {
                state.scheduleLongTail(now);
                longTails++;
            }
        }

        void runUntil(long from, long untilMs) {
            long now = from;
            while (true) {
                RefreshPolicy.Window w = RefreshPolicy.nextCheck(state, now);
                if (w == null) return;
                now = Math.max(now + 1, w.startMs);
                if (now >= untilMs) return;
                if (RefreshPolicy.decide(state, now) == RefreshPolicy.Action.FRESH) fresh(now);
            }
        }
    }

    @Test
    public void unsetHandleFailsTheAttemptIntoTheRetryPolicy() {
        Attempts a = new Attempts();
        a.fresh(T0);
        assertEquals(0, a.sent);
        assertEquals(1, a.notSent);
        assertFalse(a.state.isRegistered());
        assertEquals(RegistrationState.Op.NONE, a.state.inFlight());
        assertEquals("attempt retry", a.state.retryReason());
        assertTrue(a.state.retryAtMs() > T0);
    }

    @Test
    public void unsetHandleRetriesBoundedThenLongTail() {
        Attempts a = new Attempts();
        a.fresh(T0);
        a.runUntil(T0, T0 + 10 * 60 * S);
        assertEquals(0, a.sent);
        assertEquals(RefreshPolicy.REG_MAX_RETRIES + 1, a.notSent);
        assertEquals(1, a.longTails);
        assertEquals("long-tail retry", a.state.retryReason());
    }

    @Test
    public void retryRegistersOnceTheNetworkIsSet() {
        Attempts a = new Attempts();
        a.fresh(T0);
        a.networkHandle = IMS_HANDLE;
        a.runUntil(T0, T0 + 10 * 60 * S);
        assertEquals(1, a.notSent);
        assertEquals(1, a.sent);
        assertTrue(a.state.isRegistered());
        assertEquals(0, a.longTails);
    }
}
