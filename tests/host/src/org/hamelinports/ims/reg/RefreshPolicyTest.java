package org.hamelinports.ims.reg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.hamelinports.ims.reg.RefreshPolicy.Action;
import org.hamelinports.ims.reg.RefreshPolicy.Window;
import org.hamelinports.ims.reg.RegistrationState.Op;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RefreshPolicyTest {
    private static final int[] EXPIRES_SEC = {60, 600, 1200, 1201, 1800, 3600, 7200};
    private static final long S = 1000L;
    /** Arbitrary boot-relative start, far from zero so "never" sentinels cannot collide. */
    private static final long T0 = 5_000_000L;

    /** A running state registered at {@code regOkMs} after one fresh attempt 2 s earlier. */
    private static RegistrationState registeredAt(long regOkMs, int expiresSec, int overrideSec) {
        RegistrationState s = new RegistrationState();
        s.setRunning(true);
        s.beginFresh(regOkMs - 2 * S);
        s.onRegistered(regOkMs, expiresSec, overrideSec);
        return s;
    }

    @Test
    public void deadlineFollows3gpp() {
        assertEquals(300 * S, RefreshPolicy.deadlineMs(600));
        assertEquals(600 * S, RefreshPolicy.deadlineMs(1200));
        assertEquals(601 * S, RefreshPolicy.deadlineMs(1201));
        assertEquals(3000 * S, RefreshPolicy.deadlineMs(3600));
        assertEquals(6600 * S, RefreshPolicy.deadlineMs(7200));
    }

    @Test
    public void windowForOneHourIsOptionB() {
        Window w = RefreshPolicy.renewalWindow(3600, 0);
        assertEquals(2400 * S, w.startMs);
        assertEquals(2700 * S, w.endMs());
    }

    @Test
    public void windowValues() {
        Window w7200 = RefreshPolicy.renewalWindow(7200, 0);
        assertEquals(6000 * S, w7200.startMs);
        assertEquals(6300 * S, w7200.endMs());
        Window w1800 = RefreshPolicy.renewalWindow(1800, 0);
        assertEquals(675 * S, w1800.startMs);
        assertEquals(900 * S, w1800.endMs());
        Window w600 = RefreshPolicy.renewalWindow(600, 0);
        assertEquals(225 * S, w600.startMs);
        assertEquals(300 * S, w600.endMs());
        Window w60 = RefreshPolicy.renewalWindow(60, 0);
        assertEquals(22_500, w60.startMs);
        assertEquals(30 * S, w60.endMs());
    }

    @Test
    public void windowNeverPassesDeadlineOrLapse() {
        for (int e : EXPIRES_SEC) {
            Window w = RefreshPolicy.renewalWindow(e, 0);
            String m = "E=" + e;
            assertTrue(m, w.startMs > 0);
            assertTrue(m, w.lengthMs > 0 && w.lengthMs <= 300 * S);
            assertTrue(m, w.endMs() <= RefreshPolicy.deadlineMs(e));
            assertTrue(m, w.endMs() <= e * S - RefreshPolicy.LAPSE_MARGIN_MS);
        }
    }

    @Test
    public void longestRetryAfterLandsBeforeExpiry() {
        /* A 503 with Retry-After 599 at the window end still leaves 300 s. */
        for (int e : new int[] {1800, 3600, 7200}) {
            Window w = RefreshPolicy.renewalWindow(e, 0);
            long regOk = T0;
            long refreshAt = regOk + w.endMs();
            long retryAt = RefreshPolicy.retryAtMs(refreshAt, 599, regOk - 2 * S);
            assertTrue("E=" + e, retryAt <= regOk + e * S - 300 * S);
        }
    }

    @Test
    public void overrideMovesWindowEarlierOnly() {
        Window w = RefreshPolicy.renewalWindow(3600, 120);
        assertEquals(90 * S, w.startMs);
        assertEquals(120 * S, w.endMs());
        assertEquals(2700 * S, RefreshPolicy.renewalWindow(3600, 29).endMs());
        assertEquals(2700 * S, RefreshPolicy.renewalWindow(3600, 5000).endMs());
    }

    @Test
    public void ageRuleAtOneHour() {
        RegistrationState s = registeredAt(T0, 3600, 0);
        assertEquals(Action.REARM, RefreshPolicy.decide(s, T0));
        assertEquals(Action.REARM, RefreshPolicy.decide(s, T0 + 2399 * S));
        assertEquals(Action.REFRESH, RefreshPolicy.decide(s, T0 + 2400 * S));
        Window next = RefreshPolicy.nextCheck(s, T0 + 100 * S);
        assertEquals(T0 + 2400 * S, next.startMs);
        assertEquals(300 * S, next.lengthMs);
    }

    @Test
    public void lapseBoundary() {
        RegistrationState s = registeredAt(T0, 3600, 0);
        long expiry = T0 + 3600 * S;
        assertEquals(Action.REFRESH, RefreshPolicy.decide(s, expiry - 31 * S));
        assertFalse(s.isLapsed(expiry - 31 * S));
        assertEquals(Action.FRESH, RefreshPolicy.decide(s, expiry - 30 * S));
        assertEquals(Action.FRESH, RefreshPolicy.decide(s, expiry));
        assertEquals(Action.FRESH, RefreshPolicy.decide(s, expiry + S));
        assertTrue(s.isLapsed(expiry + S));
    }

    @Test
    public void inFlightSuppressesThenTimesOut() {
        RegistrationState s = registeredAt(T0, 3600, 0);
        long sent = T0 + 2500 * S;
        s.beginRefresh(sent);
        assertEquals(Action.NOOP, RefreshPolicy.decide(s, sent + 39_999));
        assertEquals(Action.TIMED_OUT, RefreshPolicy.decide(s, sent + 40 * S));
        assertEquals(sent + 40 * S, RefreshPolicy.nextCheck(s, sent).startMs);

        long freshAt = T0 + 9000 * S;
        s.beginFresh(freshAt);
        assertEquals(Action.NOOP, RefreshPolicy.decide(s, freshAt + 79_999));
        assertEquals(Action.TIMED_OUT, RefreshPolicy.decide(s, freshAt + 80 * S));
        /* Nothing else is started while the outcome is pending. */
        assertEquals(Action.NOOP, RefreshPolicy.decide(s, freshAt + S));
    }

    @Test
    public void freshFloorHoldsLapseBack() {
        /* E = 60: lapse 30 s after the 200 OK, but the last fresh attempt was 2 s before it. */
        long regOk = T0;
        RegistrationState s = registeredAt(regOk, 60, 0);
        long lapse = regOk + 30 * S;
        assertEquals(Action.REARM, RefreshPolicy.decide(s, lapse));
        Window next = RefreshPolicy.nextCheck(s, lapse);
        assertEquals(regOk - 2 * S + RefreshPolicy.MIN_FRESH_INTERVAL_MS, next.startMs);
        assertEquals(Action.FRESH, RefreshPolicy.decide(s, next.startMs));
    }

    @Test
    public void retryAfterHandling() {
        long last = T0 - 3000 * S;
        assertEquals(T0 + 599 * S, RefreshPolicy.retryAtMs(T0, 599, last));
        assertEquals(T0 + 30 * S, RefreshPolicy.retryAtMs(T0, 0, last));
        assertEquals(T0 + 5 * S, RefreshPolicy.retryAtMs(T0, 1, last));
        assertEquals(T0 + 86_400 * S, RefreshPolicy.retryAtMs(T0, 86_400, last));
        assertEquals(-1, RefreshPolicy.retryAtMs(T0, 86_401, last));
        /* The floor wins over a short Retry-After. */
        assertEquals(T0 + 50 * S, RefreshPolicy.retryAtMs(T0, 5, T0 - 10 * S));
        /* No fresh attempt yet: no floor. */
        assertEquals(T0 + 5 * S, RefreshPolicy.retryAtMs(T0, 5, -1));
    }

    @Test
    public void retryFiresAtRetryAfter() {
        RegistrationState s = registeredAt(T0, 3600, 0);
        long refreshAt = T0 + 2700 * S;
        s.beginRefresh(refreshAt);
        assertTrue(s.scheduleRetry(refreshAt + S, 599, "5xx retry"));
        long retryAt = refreshAt + S + 599 * S;
        assertEquals(Op.NONE, s.inFlight());
        assertTrue("binding still usable meanwhile", s.isRegistered());
        assertEquals(Action.REARM, RefreshPolicy.decide(s, retryAt - S));
        assertEquals(Action.FRESH, RefreshPolicy.decide(s, retryAt));
        Window w = RefreshPolicy.nextCheck(s, refreshAt);
        assertEquals(retryAt, w.startMs);
        assertEquals(10 * S, w.lengthMs);
    }

    @Test
    public void retryHonouredEvenIfBindingLapses() {
        RegistrationState s = registeredAt(T0, 3600, 0);
        long refreshAt = T0 + 3500 * S;
        s.beginRefresh(refreshAt);
        assertTrue(s.scheduleRetry(refreshAt, 599, "5xx retry"));
        long lapsedAt = T0 + 3580 * S;
        assertTrue(s.isLapsed(lapsedAt));
        assertEquals(Action.REARM, RefreshPolicy.decide(s, lapsedAt));
        assertTrue("MO waits for the retry", s.isRenewalPending(lapsedAt));
        assertEquals(Action.FRESH, RefreshPolicy.decide(s, refreshAt + 599 * S));
    }

    @Test
    public void retryBudgetIsBounded() {
        RegistrationState s = registeredAt(T0, 3600, 0);
        for (int i = 0; i < RefreshPolicy.REG_MAX_RETRIES; i++) {
            assertTrue(s.scheduleRetry(T0 + i * S, 30, "5xx retry"));
        }
        assertFalse(s.scheduleRetry(T0 + 10 * S, 30, "5xx retry"));
        assertEquals(RefreshPolicy.REG_MAX_RETRIES, s.retryCount());
        s.onRegistered(T0 + 100 * S, 3600, 0);
        assertEquals(0, s.retryCount());
        assertFalse("cap exceeded", s.scheduleRetry(T0 + 200 * S, 86_401, "5xx retry"));
    }

    @Test
    public void longTailIsAWakeupRetry() {
        RegistrationState s = registeredAt(T0, 3600, 0);
        s.scheduleLongTail(T0 + 10 * S);
        assertFalse(s.isRegistered());
        assertFalse(s.isIdle());
        long due = T0 + 10 * S + RefreshPolicy.LONG_TAIL_RETRY_MS;
        assertEquals(Action.REARM, RefreshPolicy.decide(s, due - S));
        assertEquals(Action.FRESH, RefreshPolicy.decide(s, due));
        assertEquals(due, RefreshPolicy.nextCheck(s, T0).startMs);
    }

    @Test
    public void stuckStackCostsOneAttemptPerLongTail() {
        /* The network never answers: count fresh attempts over 6 h of
         * simulated time, following the controller's handling of each action. */
        RegistrationState s = new RegistrationState();
        s.setRunning(true);
        long now = T0;
        s.beginFresh(now);
        int attempts = 1;
        long end = T0 + 6 * 3600 * S;
        while (now < end) {
            Window w = RefreshPolicy.nextCheck(s, now);
            assertNotNull(w);
            now = Math.max(now, w.startMs);
            switch (RefreshPolicy.decide(s, now)) {
                case FRESH:
                    s.beginFresh(now);
                    attempts++;
                    break;
                case TIMED_OUT:
                    s.scheduleLongTail(now);
                    break;
                default:
                    now += S;
                    break;
            }
        }
        assertTrue("attempts=" + attempts, attempts <= 13);
    }

    @Test
    public void notRunningDoesNothing() {
        RegistrationState s = registeredAt(T0, 3600, 0);
        s.setRunning(false);
        assertEquals(Action.NOOP, RefreshPolicy.decide(s, T0 + 4000 * S));
        assertNull(RefreshPolicy.nextCheck(s, T0));
    }

    @Test
    public void unregisteredIdleWaitsForEvents() {
        RegistrationState s = new RegistrationState();
        s.setRunning(true);
        assertTrue(s.isIdle());
        assertEquals(Action.NOOP, RefreshPolicy.decide(s, T0));
        assertNull(RefreshPolicy.nextCheck(s, T0));
    }

    @Test
    public void activeCallDefersFresh() {
        RegistrationState s = registeredAt(T0, 3600, 0);
        long lapse = T0 + 3600 * S - 30 * S;
        s.deferFresh(lapse + RefreshPolicy.ACTIVE_CALL_RECHECK_MS);
        assertEquals(Action.REARM, RefreshPolicy.decide(s, lapse));
        assertEquals(lapse + RefreshPolicy.ACTIVE_CALL_RECHECK_MS,
                RefreshPolicy.nextCheck(s, lapse).startMs);
        assertEquals(Action.FRESH,
                RefreshPolicy.decide(s, lapse + RefreshPolicy.ACTIVE_CALL_RECHECK_MS));
        /* A fresh attempt clears the hold; only the floor remains. */
        long freshAt = lapse + RefreshPolicy.ACTIVE_CALL_RECHECK_MS;
        s.deferFresh(freshAt + 3600 * S);
        s.beginFresh(freshAt);
        assertEquals(freshAt + RefreshPolicy.MIN_FRESH_INTERVAL_MS, s.freshNotBeforeMs());
    }

    @Test
    public void staleGenerationIsDropped() {
        RegistrationState s = new RegistrationState();
        int g0 = s.generation();
        int g1 = s.beginFresh(T0);
        assertFalse(s.isCurrent(g0));
        assertTrue(s.isCurrent(g1));
        s.onRegistered(T0 + S, 3600, 0);
        assertTrue("a 200 OK keeps the generation", s.isCurrent(g1));
        s.beginRefresh(T0 + 2500 * S);
        assertTrue("a refresh keeps the generation", s.isCurrent(g1));
        int g2 = s.invalidate();
        assertFalse(s.isCurrent(g1));
        assertTrue(s.isCurrent(g2));
    }

    @Test
    public void wakelockBounds() {
        assertEquals(40 * S, RefreshPolicy.wakelockBoundMs(Op.REFRESH));
        assertEquals(80 * S, RefreshPolicy.wakelockBoundMs(Op.FRESH));
        assertEquals(0, RefreshPolicy.wakelockBoundMs(Op.NONE));
        assertTrue(RefreshPolicy.REFRESH_WAKELOCK_MS <= 40 * S);
        assertTrue(RefreshPolicy.FRESH_WAKELOCK_MS <= 80 * S);
    }

    @Test
    public void moSeesRenewalPending() {
        RegistrationState s = registeredAt(T0, 3600, 0);
        assertEquals(T0 + 3570 * S, s.usableUntilMs());
        assertFalse(s.isRenewalPending(T0 + 3000 * S));
        assertTrue("lapsed", s.isRenewalPending(T0 + 3570 * S));
        s.beginFresh(T0 + 3570 * S);
        assertEquals(0, s.usableUntilMs());
        assertTrue("fresh in flight", s.isRenewalPending(T0 + 3571 * S));
        s.onRegistered(T0 + 3572 * S, 3600, 0);
        assertFalse(s.isRenewalPending(T0 + 3573 * S));
    }
}
