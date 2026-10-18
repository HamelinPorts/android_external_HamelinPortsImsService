package org.hamelinports.ims.sip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.hamelinports.ims.sip.RpErrorPolicy.Status;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RpErrorPolicyTest {
    /** CarrierConfigManager.ImsSms defaults (sms_rp_cause_values_to_fallback_int_array). */
    static final int[] AOSP_FALLBACK = {1, 8, 10, 11, 21, 27, 28, 29, 30, 38, 42, 47, 50, 69, 81,
            95, 96, 97, 98, 99, 111, 127};
    /** sms_rp_cause_values_to_retry_over_ims_int_array default. */
    static final int[] AOSP_RETRY = {41};

    static RpErrorPolicy aospDefaults() {
        return new RpErrorPolicy(true, AOSP_FALLBACK, AOSP_RETRY, true);
    }

    @Test
    public void barringAndUnsubscribedFallBackToCs() {
        RpErrorPolicy p = aospDefaults();
        assertEquals(Status.ERROR_FALLBACK, p.statusFor(8));
        assertEquals(Status.ERROR_FALLBACK, p.statusFor(10));
        assertEquals(Status.ERROR_FALLBACK, p.statusFor(50));
        for (int cause : AOSP_FALLBACK) {
            assertEquals("cause " + cause, Status.ERROR_FALLBACK, p.statusFor(cause));
        }
    }

    @Test
    public void temporaryFailureRetriesOverIms() {
        assertEquals(Status.ERROR_RETRY, aospDefaults().statusFor(41));
    }

    @Test
    public void unlistedCausesStayErrors() {
        RpErrorPolicy p = aospDefaults();
        assertEquals("memory capacity exceeded", Status.ERROR, p.statusFor(22));
        assertEquals(Status.ERROR, p.statusFor(0));
        assertEquals(Status.ERROR, p.statusFor(100));
        assertEquals(Status.ERROR, p.statusFor(126));
    }

    @Test
    public void missingCauseNeverFallsBack() {
        assertEquals(Status.ERROR, aospDefaults().statusFor(-1));
        RpErrorPolicy odd = new RpErrorPolicy(true, new int[] {-1, 10}, new int[] {-1}, true);
        assertEquals("even if a carrier list holds -1", Status.ERROR, odd.statusFor(-1));
    }

    @Test
    public void carrierWithoutCsFallbackGetsErrors() {
        RpErrorPolicy p = new RpErrorPolicy(true, AOSP_FALLBACK, AOSP_RETRY, false);
        assertEquals(Status.ERROR, p.statusFor(10));
        assertEquals(Status.ERROR, p.statusFor(8));
        assertEquals("a retry over IMS needs no CS", Status.ERROR_RETRY, p.statusFor(41));
    }

    /** gta8: property off ⇒ every cause is an ERROR, as before. */
    @Test
    public void propertyOffKeepsTheOldBehaviour() {
        RpErrorPolicy off = RpErrorPolicy.off();
        assertFalse(off.enabled());
        for (int cause = -1; cause <= 127; cause++) {
            assertEquals("cause " + cause, Status.ERROR, off.statusFor(cause));
        }
        RpErrorPolicy disabled = new RpErrorPolicy(false, AOSP_FALLBACK, AOSP_RETRY, true);
        assertEquals(Status.ERROR, disabled.statusFor(10));
        assertEquals(Status.ERROR, disabled.statusFor(41));
    }

    @Test
    public void noCarrierListsMeansErrors() {
        RpErrorPolicy p = new RpErrorPolicy(true, null, null, true);
        assertTrue(p.enabled());
        assertEquals(Status.ERROR, p.statusFor(10));
        assertEquals(Status.ERROR, p.statusFor(41));
    }

    @Test
    public void retryListWinsOverFallbackList() {
        RpErrorPolicy p = new RpErrorPolicy(true, new int[] {41, 10}, new int[] {41}, true);
        assertEquals(Status.ERROR_RETRY, p.statusFor(41));
        assertEquals(Status.ERROR_FALLBACK, p.statusFor(10));
    }

    @Test
    public void listsAreCopied() {
        int[] fallback = {10};
        RpErrorPolicy p = new RpErrorPolicy(true, fallback, null, true);
        fallback[0] = 22;
        assertEquals(Status.ERROR_FALLBACK, p.statusFor(10));
        assertEquals(Status.ERROR, p.statusFor(22));
    }

    @Test
    public void propertyName() {
        assertEquals("persist.lineage.ims.sms_rp_fallback", RpErrorPolicy.PROPERTY);
    }
}
