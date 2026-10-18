package org.hamelinports.ims.reg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.hamelinports.ims.reg.ImsPdnWatchdogPolicy.Verdict;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ImsPdnWatchdogPolicyTest {
    private static final long T0 = 5_000_000L;

    @Test
    public void armsOncePerEpisodeWithoutAConnectedPdn() {
        assertTrue(ImsPdnWatchdogPolicy.shouldArm(false, false, false));
        assertFalse("already armed", ImsPdnWatchdogPolicy.shouldArm(false, true, false));
        assertFalse("connected", ImsPdnWatchdogPolicy.shouldArm(true, false, false));
    }

    @Test
    public void neverArmsWhileAFallbackIsPending() {
        assertFalse(ImsPdnWatchdogPolicy.shouldArm(false, false, true));
    }

    @Test
    public void tearsDownAfterFifteenSecondsWithoutABringup() {
        assertEquals(Verdict.TEAR_DOWN,
                ImsPdnWatchdogPolicy.onFire(T0 + 15_000, T0, false, false, false));
    }

    @Test
    public void standsDownWhenConnectedOrPending() {
        assertEquals(Verdict.STAND_DOWN,
                ImsPdnWatchdogPolicy.onFire(T0 + 15_000, T0, true, false, false));
        assertEquals(Verdict.STAND_DOWN,
                ImsPdnWatchdogPolicy.onFire(T0 + 15_000, T0, false, false, true));
        assertEquals(Verdict.STAND_DOWN,
                ImsPdnWatchdogPolicy.onFire(T0 + 200_000, T0, false, true, true));
    }

    /** A bring-up mid-flight is not dropped (the request release at 04:12:33). */
    @Test
    public void rechecksARunningBringupUntilTheBound() {
        assertEquals(Verdict.RECHECK,
                ImsPdnWatchdogPolicy.onFire(T0 + 15_000, T0, false, true, false));
        assertEquals(Verdict.RECHECK,
                ImsPdnWatchdogPolicy.onFire(T0 + 89_999, T0, false, true, false));
        assertEquals(Verdict.TEAR_DOWN,
                ImsPdnWatchdogPolicy.onFire(T0 + 90_000, T0, false, true, false));
    }

    @Test
    public void recheckNeverPassesTheBound() {
        assertEquals(T0 + 30_000, ImsPdnWatchdogPolicy.recheckAtMs(T0 + 15_000, T0));
        assertEquals(T0 + 90_000, ImsPdnWatchdogPolicy.recheckAtMs(T0 + 80_000, T0));
        assertEquals(T0 + 90_000, ImsPdnWatchdogPolicy.recheckAtMs(T0 + 90_000, T0));
    }

    @Test
    public void constants() {
        assertEquals(15_000L, ImsPdnWatchdogPolicy.CHECK_MS);
        assertEquals(90_000L, ImsPdnWatchdogPolicy.BRINGUP_BOUND_MS);
    }
}
