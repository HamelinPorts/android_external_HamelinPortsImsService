package org.hamelinports.ims.sip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.hamelinports.ims.sip.PendingMoSms.Outcome;
import org.hamelinports.ims.sip.RpErrorPolicy.Status;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PendingMoSmsTest {
    private static final RpErrorPolicy ON = RpErrorPolicyTest.aospDefaults();

    private static RpError rpError(int rpMr, int cause) {
        return RpError.parse(new byte[] {0x05, (byte) rpMr, 0x01, (byte) cause});
    }

    private static void assertCompleted(Outcome o, int token, Status status, int code) {
        assertNull("ignored: " + o.ignored, o.ignored);
        assertEquals(token, o.token);
        assertEquals(status, o.status);
        assertEquals(code, o.networkCode);
    }

    private static void assertIgnored(Outcome o) {
        assertNotNull("completed token " + o.token, o.ignored);
        assertNull(o.status);
    }

    private static PendingMoSms pending(int token) {
        PendingMoSms p = new PendingMoSms();
        assertEquals(1, p.begin(token, 40 + token));
        return p;
    }

    @Test
    public void rpAckIsSuccess() {
        PendingMoSms p = pending(5);
        Outcome o = p.onRpAck(1);
        assertCompleted(o, 5, Status.OK, 0);
        assertEquals(45, o.messageRef);
        assertFalse(p.isPending(5));
    }

    @Test
    public void barredOnLteFallsBackToCs() {
        for (int cause : new int[] {8, 10, 50}) {
            PendingMoSms p = pending(5);
            assertCompleted(p.onRpError(rpError(1, cause), ON), 5, Status.ERROR_FALLBACK, cause);
        }
    }

    @Test
    public void temporaryFailureRetriesOverIms() {
        assertCompleted(pending(5).onRpError(rpError(1, 41), ON), 5, Status.ERROR_RETRY, 41);
    }

    @Test
    public void unlistedCauseIsAnError() {
        assertCompleted(pending(5).onRpError(rpError(1, 22), ON), 5, Status.ERROR, 22);
    }

    @Test
    public void mismatchedRpMrIsIgnored() {
        PendingMoSms p = pending(5);
        assertIgnored(p.onRpError(rpError(4, 10), ON));
        assertIgnored(p.onRpAck(2));
        assertTrue("still pending", p.isPending(5));
        assertCompleted(p.onRpError(rpError(1, 10), ON), 5, Status.ERROR_FALLBACK, 10);
    }

    @Test
    public void unknownRpMrNeverFallsBack() {
        assertIgnored(pending(5).onRpError(rpError(9, 10), ON));
    }

    @Test
    public void oneOutcomePerToken() {
        PendingMoSms p = pending(5);
        assertCompleted(p.onRpError(rpError(1, 10), ON), 5, Status.ERROR_FALLBACK, 10);
        assertIgnored(p.onRpError(rpError(1, 10), ON));
        assertIgnored(p.onRpAck(1));
        assertIgnored(p.onSipFailure(1, 503));
        assertIgnored(p.onTimeout(5, 1));
        assertIgnored(p.onNotSent(5));
    }

    @Test
    public void neverFallBackAfterAnRpAck() {
        PendingMoSms p = pending(5);
        assertCompleted(p.onRpAck(1), 5, Status.OK, 0);
        assertIgnored(p.onRpError(rpError(1, 10), ON));
    }

    /** Unchanged: no RP outcome in time is an optimistic success, and nothing falls back later. */
    @Test
    public void timeoutIsOptimisticSuccessAndFinal() {
        PendingMoSms p = pending(5);
        assertCompleted(p.onTimeout(5, 1), 5, Status.OK, 0);
        assertIgnored(p.onRpError(rpError(1, 10), ON));
        assertIgnored(p.onRpAck(1));
    }

    @Test
    public void staleTimeoutDoesNotCompleteTheNextSend() {
        PendingMoSms p = pending(5);
        assertCompleted(p.onRpAck(1), 5, Status.OK, 0);
        assertEquals(2, p.begin(6, 46));
        assertIgnored(p.onTimeout(5, 1));
        assertTrue(p.isPending(6));
        assertCompleted(p.onTimeout(6, 2), 6, Status.OK, 0);
    }

    @Test
    public void sipFailureIsAnErrorEvenWithTheFallbackOn() {
        PendingMoSms p = pending(5);
        assertCompleted(p.onSipFailure(1, 403), 5, Status.ERROR, 403);
        assertIgnored(p.onRpError(rpError(1, 10), ON));
    }

    @Test
    public void notQueuedIsAnError() {
        PendingMoSms p = pending(5);
        assertIgnored(p.onNotSent(6));
        assertCompleted(p.onNotSent(5), 5, Status.ERROR, 0);
    }

    @Test
    public void multipartOutcomesAreIndependentAndOutOfOrder() {
        PendingMoSms p = pending(5);
        assertEquals(2, p.begin(6, 46));
        assertCompleted(p.onRpError(rpError(2, 10), ON), 6, Status.ERROR_FALLBACK, 10);
        assertTrue(p.isPending(5));
        assertIgnored(p.onRpError(rpError(2, 10), ON));
        assertCompleted(p.onRpAck(1), 5, Status.OK, 0);
    }

    @Test
    public void lateErrorAfterTimeoutCannotFallBackTheNextSend() {
        PendingMoSms p = pending(5);
        assertCompleted(p.onTimeout(5, 1), 5, Status.OK, 0);
        assertEquals(2, p.begin(6, 46));
        assertIgnored(p.onRpError(rpError(1, 10), ON));
        assertIgnored(p.onSipFailure(1, 503));
        assertCompleted(p.onRpAck(2), 6, Status.OK, 0);
    }

    @Test
    public void sipFailureAndNotQueuedDoNotCompleteOtherParts() {
        PendingMoSms p = pending(5);
        assertEquals(2, p.begin(6, 46));
        assertEquals(3, p.begin(7, 47));
        assertCompleted(p.onSipFailure(2, 503), 6, Status.ERROR, 503);
        assertCompleted(p.onNotSent(7), 7, Status.ERROR, 0);
        assertCompleted(p.onTimeout(5, 1), 5, Status.OK, 0);
    }

    @Test
    public void reusedTokenDoesNotAcceptThePreviousTimeout() {
        PendingMoSms p = pending(5);
        assertCompleted(p.onRpAck(1), 5, Status.OK, 0);
        assertEquals(2, p.begin(5, 49));
        assertIgnored(p.onTimeout(5, 1));
        assertCompleted(p.onTimeout(5, 2), 5, Status.OK, 0);
    }

    @Test
    public void referencesSkipPendingAndRetiredOnWrapAndRefuseExhaustion() {
        long[] now = {1};
        PendingMoSms p = new PendingMoSms(() -> now[0]);
        for (int i = 1; i <= 255; i++) assertEquals(i, p.begin(i, 900 + i));
        assertEquals(-1, p.begin(256, 0));
        assertEquals(-1, p.begin(1, 0));
        assertCompleted(p.onTimeout(1, 1), 1, Status.OK, 0);
        assertEquals(-1, p.begin(256, 0));
        now[0] += 129_999;
        assertEquals(-1, p.begin(256, 0));
        now[0]++;
        assertEquals(1, p.begin(256, 22));
        Outcome o = p.onRpAck(1);
        assertCompleted(o, 256, Status.OK, 0);
        assertEquals(22, o.messageRef);
        assertTrue(p.isPending(255));
    }

    /** gta8: property off ⇒ the RP-ERROR is reported as before (ERROR with its cause). */
    @Test
    public void propertyOffReportsTheOldError() {
        assertCompleted(pending(5).onRpError(rpError(1, 10), RpErrorPolicy.off()),
                5, Status.ERROR, 10);
        assertCompleted(pending(5).onRpError(rpError(1, 41), RpErrorPolicy.off()),
                5, Status.ERROR, 41);
    }

    @Test
    public void rpErrorWithoutCauseIsAnError() {
        RpError noCause = RpError.parse(new byte[] {0x05, 0x01});
        assertCompleted(pending(5).onRpError(noCause, ON), 5, Status.ERROR, -1);
    }

    @Test
    public void nothingPending() {
        PendingMoSms p = new PendingMoSms();
        assertIgnored(p.onRpAck(1));
        assertIgnored(p.onRpError(rpError(1, 10), ON));
        assertIgnored(p.onSipFailure(1, 500));
        assertIgnored(p.onTimeout(1, 1));
        assertFalse(p.isPending(-1));
    }
}
