package org.hamelinports.ims.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class IwlanDpdIntervalTest {

    private static void assertAccepted(String raw, int seconds) {
        IwlanDpdInterval dpd = IwlanDpdInterval.fromProperty(raw);
        assertEquals("seconds for '" + raw + "'", seconds, dpd.seconds());
        assertFalse("rejected '" + raw + "'", dpd.rejected());
    }

    private static void assertRejected(String raw) {
        IwlanDpdInterval dpd = IwlanDpdInterval.fromProperty(raw);
        assertEquals("fallback for '" + raw + "'", 300, dpd.seconds());
        assertTrue("accepted '" + raw + "'", dpd.rejected());
    }

    @Test
    public void unsetKeepsTheDefaultWithoutAWarning() {
        assertAccepted(null, 300);
        assertAccepted("", 300);
        assertAccepted("  ", 300);
    }

    @Test
    public void acceptsTheWholeIwlanRange() {
        assertAccepted("20", 20);
        assertAccepted("300", 300);
        assertAccepted("900", 900);
        assertAccepted("1800", 1800);
        assertAccepted(" 1800 ", 1800);
    }

    @Test
    public void rejectsValuesIwlanWouldReplaceWith120() {
        assertRejected("19");
        assertRejected("1801");
        assertRejected("3600");
        assertRejected("0");
        assertRejected("-300");
    }

    @Test
    public void rejectsNonNumbers() {
        assertRejected("1800s");
        assertRejected("30m");
        assertRejected("disabled");
        assertRejected("99999999999");
        assertRejected("18 00");
    }

    @Test
    public void boundsMatchIwlan() {
        assertEquals(20, IwlanDpdInterval.MIN_SEC);
        assertEquals(1800, IwlanDpdInterval.MAX_SEC);
        assertEquals(300, IwlanDpdInterval.DEFAULT_SEC);
        assertEquals("persist.lineage.ims.dpd.sec", IwlanDpdInterval.PROPERTY);
    }
}
