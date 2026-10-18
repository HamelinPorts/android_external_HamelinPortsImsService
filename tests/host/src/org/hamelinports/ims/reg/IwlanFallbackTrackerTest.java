package org.hamelinports.ims.reg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.hamelinports.ims.reg.IwlanFallbackTracker.Decision;
import org.hamelinports.ims.reg.IwlanFallbackTracker.ProbeResult;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class IwlanFallbackTrackerTest {
    private static final long T0 = 1_000_000L;
    private static final long SPACING = 30_000L;

    /** A failure on IWLAN with every gate open. */
    private static Decision fail(IwlanFallbackTracker t, long nowMs) {
        return t.onTransportFailure(nowMs, true, true, true, false);
    }

    /** Two failures that report, the second at {@code atMs}. */
    private static void report(IwlanFallbackTracker t, long atMs) {
        assertEquals(Decision.COUNTED, fail(t, atMs - SPACING));
        assertEquals(Decision.REPORT, fail(t, atMs));
    }

    @Test
    public void localTransportFailuresCount() {
        assertTrue(IwlanFallbackTracker.isLocalTransportFailure(503, false));
        assertTrue(IwlanFallbackTracker.isLocalTransportFailure(408, false));
    }

    /** A server 503 (with or without Retry-After) or 408 is an answer: the path works. */
    @Test
    public void responsesFromTheNetworkNeverCount() {
        assertFalse(IwlanFallbackTracker.isLocalTransportFailure(503, true));
        assertFalse(IwlanFallbackTracker.isLocalTransportFailure(408, true));
        assertFalse(IwlanFallbackTracker.isLocalTransportFailure(500, false));
        assertFalse(IwlanFallbackTracker.isLocalTransportFailure(504, false));
        assertFalse(IwlanFallbackTracker.isLocalTransportFailure(480, false));
        assertFalse(IwlanFallbackTracker.isLocalTransportFailure(0, false));
        assertFalse(IwlanFallbackTracker.isLocalTransportFailure(401, false));
    }

    @Test
    public void probeClassification() {
        assertTrue(IwlanFallbackTracker.isTransportFailure(ProbeResult.TIMED_OUT));
        assertTrue(IwlanFallbackTracker.isTransportFailure(ProbeResult.UNREACHABLE));
        assertFalse(IwlanFallbackTracker.isTransportFailure(ProbeResult.REFUSED));
        assertFalse(IwlanFallbackTracker.isTransportFailure(ProbeResult.CONNECTED));
        assertFalse(IwlanFallbackTracker.isTransportFailure(ProbeResult.OTHER));
        assertTrue(IwlanFallbackTracker.provesPath(ProbeResult.CONNECTED));
        assertTrue(IwlanFallbackTracker.provesPath(ProbeResult.REFUSED));
        assertFalse(IwlanFallbackTracker.provesPath(ProbeResult.TIMED_OUT));
        assertFalse(IwlanFallbackTracker.provesPath(ProbeResult.UNREACHABLE));
        assertFalse(IwlanFallbackTracker.provesPath(ProbeResult.OTHER));
    }

    /** The night of 2026-10-17: local 503 at 03:51:22.6, probe timeout at 03:52:12.9. */
    @Test
    public void twoFailuresThirtySecondsApartReport() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        assertEquals(Decision.COUNTED, fail(t, T0));
        assertEquals(1, t.failures());
        assertEquals(Decision.REPORT, fail(t, T0 + 50_300));
        assertEquals(1, t.reports());
        assertEquals(0, t.failures());
        assertTrue(t.isPending(T0 + 50_300));
        assertEquals(T0 + 50_300 + 60_000, t.pendingUntilMs());
    }

    @Test
    public void failuresCloserThanThirtySecondsDoNotCount() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        assertEquals(Decision.COUNTED, fail(t, T0));
        assertEquals(Decision.TOO_SOON, fail(t, T0 + 29_999));
        assertEquals(1, t.failures());
        assertEquals(Decision.REPORT, fail(t, T0 + 30_000));
    }

    @Test
    public void oneFailureNeverReports() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        assertEquals(Decision.COUNTED, fail(t, T0));
        assertEquals(0, t.reports());
        assertFalse(t.isPending(T0));
    }

    /** gta8: property unset — nothing is counted, nothing reported. */
    @Test
    public void inertWhileDisabled() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        for (int i = 0; i < 10; i++) {
            assertEquals(Decision.IGNORED,
                    t.onTransportFailure(T0 + i * 60_000L, false, true, true, false));
        }
        assertEquals(0, t.failures());
        assertEquals(0, t.reports());
        assertFalse(t.isPending(T0 + 600_000));
    }

    @Test
    public void onlyCountsOnIwlan() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        assertEquals(Decision.COUNTED, fail(t, T0));
        assertEquals(Decision.IGNORED, t.onTransportFailure(T0 + 40_000, true, false, true, false));
        assertEquals("a failure on cellular clears the evidence", 0, t.failures());
        assertEquals(Decision.COUNTED, fail(t, T0 + 80_000));
    }

    @Test
    public void anAnswerFromTheNetworkClearsTheEvidence() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        assertEquals(Decision.COUNTED, fail(t, T0));
        t.onPathProven();
        assertEquals(0, t.failures());
        assertEquals(Decision.COUNTED, fail(t, T0 + 40_000));
        assertEquals(Decision.REPORT, fail(t, T0 + 80_000));
    }

    @Test
    public void anIfaceChangeClearsTheEvidence() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        assertEquals(Decision.COUNTED, fail(t, T0));
        assertFalse(t.onIfaceChanged(T0 + 10_000, true));
        assertEquals(0, t.failures());
        assertEquals(Decision.COUNTED, fail(t, T0 + 40_000));
    }

    @Test
    public void heldWhileWlanIsNotConnected() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        assertEquals(Decision.COUNTED, t.onTransportFailure(T0, true, true, false, false));
        assertEquals(Decision.HELD_WLAN_NOT_CONNECTED,
                t.onTransportFailure(T0 + SPACING, true, true, false, false));
        assertEquals(0, t.reports());
        assertFalse(t.isPending(T0 + SPACING));
        assertEquals("the evidence stays", Decision.REPORT, fail(t, T0 + 2 * SPACING));
    }

    /** No report while a call (emergency included) needs the stack, however long it lasts. */
    @Test
    public void noReportWithACallOnTheStack() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        assertEquals(Decision.COUNTED, t.onTransportFailure(T0, true, true, true, true));
        for (int i = 1; i <= 20; i++) {
            assertEquals(Decision.HELD_CALL_ON_STACK,
                    t.onTransportFailure(T0 + i * SPACING, true, true, true, true));
        }
        assertEquals(0, t.reports());
        assertFalse(t.isPending(T0 + 20 * SPACING));
        assertEquals("the count stays at the threshold", 2, t.failures());
        assertEquals("reported once the call has ended",
                Decision.REPORT, fail(t, T0 + 21 * SPACING));
    }

    @Test
    public void pendingReportIgnoresFurtherFailures() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        report(t, T0);
        assertEquals(Decision.IGNORED, fail(t, T0 + 30_000));
        assertEquals(Decision.IGNORED, fail(t, T0 + 59_999));
        assertEquals(0, t.failures());
        assertEquals(1, t.reports());
    }

    @Test
    public void takenWhenImsLeavesIwlanInTime() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        report(t, T0);
        assertFalse("a swap to another tunnel does not take it", t.onIfaceChanged(T0 + 1_000, true));
        assertTrue(t.isPending(T0 + 1_000));
        assertTrue(t.onIfaceChanged(T0 + 3_000, false));
        assertEquals(1, t.taken());
        assertFalse(t.isPending(T0 + 3_000));
        assertFalse("nothing left to expire", t.expirePending(T0 + 120_000));
        assertEquals(0, t.notTaken());
    }

    @Test
    public void notTakenAfterSixtySeconds() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        report(t, T0);
        assertFalse(t.expirePending(T0 + 59_999));
        assertTrue(t.isPending(T0 + 59_999));
        assertFalse(t.isPending(T0 + 60_000));
        assertTrue(t.expirePending(T0 + 60_000));
        assertFalse("once", t.expirePending(T0 + 60_001));
        assertEquals(1, t.notTaken());
        assertFalse("a late swap is not counted as taken", t.onIfaceChanged(T0 + 70_000, false));
        assertEquals(0, t.taken());
    }

    @Test
    public void aSwapAfterTheDeadlineIsNotTaken() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        report(t, T0);
        assertFalse(t.onIfaceChanged(T0 + 60_000, false));
        assertEquals(0, t.taken());
        assertTrue("still expires as not taken", t.expirePending(T0 + 60_000));
        assertEquals(1, t.notTaken());
    }

    @Test
    public void atMostOneReportPerTwoMinutes() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        report(t, T0);
        assertTrue(t.expirePending(T0 + 60_000));
        assertEquals(Decision.COUNTED, fail(t, T0 + 60_000));
        assertEquals(Decision.HELD_RATE_LIMIT, fail(t, T0 + 90_000));
        assertEquals(Decision.TOO_SOON, fail(t, T0 + 100_000));
        assertEquals(Decision.REPORT, fail(t, T0 + 120_000));
        assertEquals(2, t.reports());
    }

    @Test
    public void rateLimitHoldsJustBeforeTwoMinutes() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        report(t, T0);
        assertTrue(t.expirePending(T0 + 60_000));
        assertEquals(Decision.COUNTED, fail(t, T0 + 89_999));
        assertEquals(Decision.HELD_RATE_LIMIT, fail(t, T0 + 119_999));
        assertEquals(1, t.reports());
    }

    @Test
    public void everyReportNeedsNewEvidence() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        report(t, T0);
        assertTrue(t.onIfaceChanged(T0 + 3_000, false));
        /* Back on IWLAN after the QNS hold. */
        assertFalse(t.onIfaceChanged(T0 + 1_800_000, true));
        assertEquals(Decision.COUNTED, fail(t, T0 + 1_810_000));
        assertEquals(1, t.reports());
        assertEquals(Decision.REPORT, fail(t, T0 + 1_840_000));
        assertEquals(2, t.reports());
    }

    @Test
    public void resetDropsEvidenceAndThePendingReport() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        report(t, T0);
        t.reset();
        assertFalse(t.isPending(T0 + 1_000));
        assertFalse(t.expirePending(T0 + 60_000));
        assertEquals(1, t.reports());
        assertEquals(Decision.COUNTED, fail(t, T0 + 200_000));
        t.reset();
        assertEquals(0, t.failures());
    }

    @Test
    public void describeForDumpsys() {
        IwlanFallbackTracker t = new IwlanFallbackTracker();
        assertEquals("failures=0/2 reports=0 taken=0 notTaken=0 lastReport=never",
                t.describe(T0));
        report(t, T0);
        assertEquals("failures=0/2 reports=1 taken=0 notTaken=0 lastReport=10s ago pendingS=50",
                t.describe(T0 + 10_000));
    }

    @Test
    public void constants() {
        assertEquals(2, IwlanFallbackTracker.FAILURES_TO_REPORT);
        assertEquals(30_000L, IwlanFallbackTracker.MIN_FAILURE_SPACING_MS);
        assertEquals(120_000L, IwlanFallbackTracker.MIN_REPORT_INTERVAL_MS);
        assertEquals(60_000L, IwlanFallbackTracker.PENDING_MS);
    }
}
