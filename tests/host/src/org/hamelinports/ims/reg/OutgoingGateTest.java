package org.hamelinports.ims.reg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.hamelinports.ims.reg.OutgoingGate.Decision;
import org.hamelinports.ims.reg.RefreshPolicy.Action;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class OutgoingGateTest {
    private static final long S = 1000L;
    private static final long T0 = 5_000_000L;

    /** Records which callback of a deferred request ran. */
    private static final class Outcome {
        final List<String> events = new ArrayList<>();
        Runnable ready() { return () -> events.add("ready"); }
        Runnable expired() { return () -> events.add("expired"); }
    }

    private static void runAll(List<Runnable> due) {
        for (Runnable r : due) r.run();
    }

    // F1: an emergency call is never held.
    @Test
    public void emergencyCallIsNeverDeferred() {
        for (boolean usable : new boolean[] {false, true}) {
            for (boolean pending : new boolean[] {false, true}) {
                assertNotEquals("usable=" + usable + " pending=" + pending,
                        Decision.DEFER, OutgoingGate.decideCall(true, usable, pending));
            }
        }
        assertEquals(Decision.SEND, OutgoingGate.decideCall(true, true, true));
        assertEquals(Decision.FAIL, OutgoingGate.decideCall(true, false, true));
        assertEquals(Decision.FAIL, OutgoingGate.decideCall(true, false, false));
    }

    @Test
    public void normalCallWaitsOnlyForAPendingRenewal() {
        assertEquals(Decision.SEND, OutgoingGate.decideCall(false, true, true));
        assertEquals(Decision.DEFER, OutgoingGate.decideCall(false, false, true));
        assertEquals("no renewal: the stack reports the outcome",
                Decision.SEND, OutgoingGate.decideCall(false, false, false));
    }

    // F2: a session that has not sent its INVITE does not hold the stack.
    @Test
    public void unsentCallDoesNotHoldTheStack() {
        assertFalse(OutgoingGate.callHoldsStack(true, false, false));
        assertTrue(OutgoingGate.callHoldsStack(true, true, false));
        assertTrue(OutgoingGate.callHoldsStack(false, false, true));
        assertFalse(OutgoingGate.callHoldsStack(false, false, false));
    }

    /**
     * F2 scenario, following the controller: the binding has lapsed and the
     * user dials. The deferred call must get its fresh registration and be
     * released, not hold that registration back until it expires.
     */
    @Test
    public void deferredCallGetsItsFreshRegistration() {
        RegistrationState s = new RegistrationState();
        s.setRunning(true);
        s.beginFresh(T0 - 2 * S);
        s.onRegistered(T0, 3600, 0);
        long now = T0 + 3580 * S;
        assertTrue(s.isLapsed(now));

        DeferredRequests q = new DeferredRequests();
        Outcome call = new Outcome();
        assertEquals(Decision.DEFER,
                OutgoingGate.decideCall(false, false, s.isRenewalPending(now)));
        q.add(call.ready(), call.expired(), now);

        for (long t = now; t <= now + OutgoingGate.MAX_DEFERRAL_MS; t += S) {
            if (RefreshPolicy.decide(s, t) == Action.FRESH) {
                boolean holds = OutgoingGate.callHoldsStack(true, false, false);
                if (holds) {
                    s.deferFresh(t + RefreshPolicy.ACTIVE_CALL_RECHECK_MS);
                } else {
                    s.beginFresh(t);
                }
            }
            if (s.inFlight() == RegistrationState.Op.FRESH && t - s.inFlightSentMs() >= 2 * S) {
                s.onRegistered(t, 3600, 0);
            }
            runAll(q.takeDue(t, s.isRenewalPending(t)));
            if (!call.events.isEmpty()) break;
        }
        assertEquals(List.of("ready"), call.events);
    }

    // F3: a cancelled request never runs.
    @Test
    public void cancelledRequestNeverRuns() {
        DeferredRequests q = new DeferredRequests();
        Outcome call = new Outcome();
        DeferredRequests.Entry e = q.add(call.ready(), call.expired(), T0);
        e.cancel();
        runAll(q.takeDue(T0 + S, false));
        runAll(q.takeDue(T0 + OutgoingGate.MAX_DEFERRAL_MS + S, true));
        assertTrue(call.events.isEmpty());
        assertEquals(0, q.size());
        e.cancel();   // after removal: no-op
    }

    @Test
    public void takenRequestRunsExactlyOnce() {
        DeferredRequests q = new DeferredRequests();
        Outcome sms = new Outcome();
        q.add(sms.ready(), sms.expired(), T0);
        runAll(q.takeDue(T0 + S, false));
        runAll(q.takeDue(T0 + 2 * S, false));
        runAll(q.takeDue(T0 + OutgoingGate.MAX_DEFERRAL_MS, true));
        assertEquals(List.of("ready"), sms.events);
    }

    // F4: the wait is bounded.
    @Test
    public void deferralExpiresAtTheBound() {
        assertTrue(OutgoingGate.MAX_DEFERRAL_MS <= 20 * S);
        assertTrue("longer than a normal fresh registration", OutgoingGate.MAX_DEFERRAL_MS > 2 * S);
        DeferredRequests q = new DeferredRequests();
        Outcome call = new Outcome();
        DeferredRequests.Entry e = q.add(call.ready(), call.expired(), T0);
        assertEquals(T0 + OutgoingGate.MAX_DEFERRAL_MS, e.deadlineMs());
        runAll(q.takeDue(T0 + OutgoingGate.MAX_DEFERRAL_MS - 1, true));
        assertTrue(call.events.isEmpty());
        runAll(q.takeDue(T0 + OutgoingGate.MAX_DEFERRAL_MS, true));
        assertEquals(List.of("expired"), call.events);
        runAll(q.takeDue(T0 + OutgoingGate.MAX_DEFERRAL_MS + S, false));
        assertEquals(List.of("expired"), call.events);
    }

    @Test
    public void smsWaitsAtMostOnce() {
        assertEquals(Decision.SEND, OutgoingGate.decideSms(true, true, true));
        assertEquals(Decision.DEFER, OutgoingGate.decideSms(false, true, true));
        assertEquals(Decision.FAIL, OutgoingGate.decideSms(false, true, false));
        assertEquals(Decision.FAIL, OutgoingGate.decideSms(false, false, true));
        assertEquals(Decision.SEND, OutgoingGate.decideSms(true, false, false));
    }

    // F6: a terminal failure drops late outcomes of the failed stack.
    @Test
    public void terminalFailureStartsANewGeneration() {
        RegistrationState s = new RegistrationState();
        s.setRunning(true);
        int gen = s.beginFresh(T0);
        s.scheduleLongTail(T0 + 5 * S);
        assertFalse(s.isCurrent(gen));
    }

    // F7: a grant too short to schedule on is refused.
    @Test
    public void shortGrantIsRefused() {
        assertTrue(OutgoingGate.isGrantedExpiresAcceptable(3600, 3600));
        assertTrue(OutgoingGate.isGrantedExpiresAcceptable(600, 3600));
        assertFalse(OutgoingGate.isGrantedExpiresAcceptable(599, 3600));
        assertFalse(OutgoingGate.isGrantedExpiresAcceptable(30, 3600));
        /* A test that requests less gets what it asked for. */
        assertTrue(OutgoingGate.isGrantedExpiresAcceptable(120, 120));
        assertFalse(OutgoingGate.isGrantedExpiresAcceptable(119, 120));
    }
}
