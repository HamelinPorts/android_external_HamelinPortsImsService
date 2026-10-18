package org.hamelinports.ims.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class IwlanFallbackPolicyTest {
    private static final String UNREG = "qns.fallback_wwan_ims_unregistration_reason_string_array";
    private static final String INITIAL = "qns.fallback_on_initial_connection_failure_string_array";

    private static void assertOff(String raw, boolean rejected) {
        IwlanFallbackPolicy p = IwlanFallbackPolicy.fromProperty(raw);
        assertFalse("enabled for '" + raw + "'", p.enabled());
        assertEquals("seconds for '" + raw + "'", 0, p.seconds());
        assertEquals("rejected for '" + raw + "'", rejected, p.rejected());
    }

    private static void assertOn(String raw, int seconds) {
        IwlanFallbackPolicy p = IwlanFallbackPolicy.fromProperty(raw);
        assertTrue("disabled for '" + raw + "'", p.enabled());
        assertEquals("seconds for '" + raw + "'", seconds, p.seconds());
        assertFalse("rejected '" + raw + "'", p.rejected());
    }

    @Test
    public void unsetAndZeroAreOffWithoutAWarning() {
        assertOff(null, false);
        assertOff("", false);
        assertOff("  ", false);
        assertOff("0", false);
        assertOff(" 0 ", false);
    }

    @Test
    public void acceptsTheWholeRange() {
        assertOn("60", 60);
        assertOn("180", 180);
        assertOn("1800", 1800);
        assertOn(" 1800 ", 1800);
        assertOn("3600", 3600);
    }

    @Test
    public void rejectsOutOfRangeAndGarbageAsOff() {
        assertOff("59", true);
        assertOff("1", true);
        assertOff("3601", true);
        assertOff("-1800", true);
        assertOff("1800s", true);
        assertOff("on", true);
        assertOff("true", true);
        assertOff("99999999999", true);
    }

    @Test
    public void unregistrationRuleIsReason1402ForTheHoldTime() {
        assertEquals("cause=1402, time=1800000",
                IwlanFallbackPolicy.fromProperty("1800").unregistrationRule());
        assertEquals("cause=1402, time=180000",
                IwlanFallbackPolicy.fromProperty("180").unregistrationRule());
        assertEquals("cause=1402, time=3600000",
                IwlanFallbackPolicy.fromProperty("3600").unregistrationRule());
        assertEquals("cause=1402, time=60000",
                IwlanFallbackPolicy.fromProperty("60").unregistrationRule());
    }

    /** 131 is reported on every idle iface swap; a rule on it would flap. */
    @Test
    public void reason131NeverTriggersAFallback() {
        for (String raw : new String[] {"60", "1800", "3600"}) {
            Map<String, String[]> o = IwlanFallbackPolicy.fromProperty(raw).overrides(null, null);
            for (String[] rules : o.values()) {
                for (String rule : rules) {
                    assertFalse(rule, rule.matches(".*\\b131\\b.*"));
                }
            }
            String unreg = o.get(UNREG)[0];
            assertTrue(unreg, unreg.startsWith("cause=1402,"));
        }
        assertEquals(1402, IwlanFallbackPolicy.REASON_EPDG_TUNNEL_LOST_CONNECTION);
    }

    @Test
    public void onPushesBothRuleArrays() {
        Map<String, String[]> o = IwlanFallbackPolicy.fromProperty("1800").overrides(null, null);
        assertEquals(2, o.size());
        assertArrayEquals(new String[] {"cause=1402, time=1800000"}, o.get(UNREG));
        assertArrayEquals(new String[] {"ims:1:60000:600000:3"}, o.get(INITIAL));
    }

    @Test
    public void onIgnoresWhatTheConfigHoldsNow() {
        Map<String, String[]> o = IwlanFallbackPolicy.fromProperty("600")
                .overrides(new String[] {"cause=1402, time=1800000"}, new String[0]);
        assertArrayEquals(new String[] {"cause=1402, time=600000"}, o.get(UNREG));
        assertArrayEquals(new String[] {"ims:1:60000:600000:3"}, o.get(INITIAL));
    }

    /** gta8: property unset, the keys never pushed: nothing to add. */
    @Test
    public void offWithNoRulesPushesNothing() {
        IwlanFallbackPolicy off = IwlanFallbackPolicy.fromProperty(null);
        assertTrue(off.overrides(null, null).isEmpty());
        assertTrue(off.overrides(new String[0], new String[0]).isEmpty());
        assertTrue(IwlanFallbackPolicy.fromProperty("garbage")
                .overrides(null, null).isEmpty());
    }

    @Test
    public void offClearsOnlyKeysThatStillHoldRules() {
        IwlanFallbackPolicy off = IwlanFallbackPolicy.fromProperty("0");
        Map<String, String[]> both = off.overrides(new String[] {"cause=1402, time=1800000"},
                new String[] {"ims:1:60000:600000:3"});
        assertEquals(2, both.size());
        assertArrayEquals(new String[0], both.get(UNREG));
        assertArrayEquals(new String[0], both.get(INITIAL));

        Map<String, String[]> unregOnly = off.overrides(
                new String[] {"cause=1402, time=1800000"}, null);
        assertEquals(Arrays.asList(UNREG), Arrays.asList(unregOnly.keySet().toArray()));
        assertArrayEquals(new String[0], unregOnly.get(UNREG));

        Map<String, String[]> initialOnly = off.overrides(new String[0],
                new String[] {"ims:1:60000:600000:3"});
        assertEquals(Arrays.asList(INITIAL), Arrays.asList(initialOnly.keySet().toArray()));
        assertArrayEquals(new String[0], initialOnly.get(INITIAL));
    }

    /** Off: rules of another shape belong to the carrier and are never blanked. */
    @Test
    public void offLeavesForeignRulesAlone() {
        IwlanFallbackPolicy off = IwlanFallbackPolicy.fromProperty(null);
        assertTrue(off.overrides(new String[] {"cause=321~378|1503, time=60000, preference=cell"},
                new String[] {"ims:3:60000:10000:2"}).isEmpty());
        assertTrue("1402 for the carrier's own reasons, another shape", off.overrides(
                new String[] {"cause=1402|1403, time=60000"}, null).isEmpty());
        assertTrue("trailing preference", off.overrides(
                new String[] {"cause=1402, time=1800000, preference=wifi"}, null).isEmpty());
        assertTrue("mixed with ours", off.overrides(
                new String[] {"cause=1402, time=1800000", "cause=240, time=90000"},
                new String[] {"ims:1:60000:600000:3", "mms:1:10000:60000:2"}).isEmpty());
        assertTrue("null item", off.overrides(new String[] {null}, new String[] {null}).isEmpty());
    }

    @Test
    public void offClearsOurRulesForAnyHoldTime() {
        IwlanFallbackPolicy off = IwlanFallbackPolicy.fromProperty("0");
        Map<String, String[]> o = off.overrides(new String[] {"cause=1402, time=180000"},
                new String[] {"ims:1:60000:600000:3", "ims:1:60000:600000:3"});
        assertArrayEquals(new String[0], o.get(UNREG));
        assertArrayEquals(new String[0], o.get(INITIAL));
        assertTrue(off.overrides(new String[] {"cause=1402, time="}, null).isEmpty());
        assertTrue(off.overrides(new String[] {"cause=1402, time=1800000x"}, null).isEmpty());
        assertTrue(off.overrides(null, new String[] {"ims:1:60000:600000:30"}).isEmpty());
    }

    @Test
    public void namesMatchQnsAndTheDevice() {
        assertEquals("persist.lineage.ims.iwlan_fallback.sec", IwlanFallbackPolicy.PROPERTY);
        assertEquals(UNREG, IwlanFallbackPolicy.KEY_UNREGISTRATION_RULES);
        assertEquals(INITIAL, IwlanFallbackPolicy.KEY_INITIAL_CONNECTION_FAILURE_RULES);
        assertEquals(60, IwlanFallbackPolicy.MIN_SEC);
        assertEquals(3600, IwlanFallbackPolicy.MAX_SEC);
    }
}
