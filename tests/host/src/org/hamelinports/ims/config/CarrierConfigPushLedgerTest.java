package org.hamelinports.ims.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CarrierConfigPushLedgerTest {
    private static final int SUB = 1;
    private static final String O2 = "26203";

    /** Re-push off (gta8): exactly the old "once per subId and MCCMNC" rule. */
    @Test
    public void withoutRepushOnlyANewCarrierIsPushed() {
        CarrierConfigPushLedger l = new CarrierConfigPushLedger(false);
        assertFalse(l.repush());
        assertTrue(l.mayNeedPush(SUB, O2));
        assertTrue(l.shouldPush(SUB, O2, "a", false));
        l.recordPush(SUB, O2, "a", false);

        assertFalse(l.mayNeedPush(SUB, O2));
        assertFalse(l.shouldPush(SUB, O2, "a", false));
        assertFalse(l.shouldPush(SUB, O2, "a", true));
        assertFalse(l.shouldPush(SUB, O2, "b", true));

        assertTrue(l.mayNeedPush(SUB, "26207"));
        assertTrue(l.shouldPush(SUB, "26207", "a", false));
        assertTrue(l.mayNeedPush(2, O2));
        assertTrue(l.shouldPush(2, O2, "a", true));
    }

    @Test
    public void repushAfterThePushBeforeSimLoaded() {
        CarrierConfigPushLedger l = new CarrierConfigPushLedger(true);
        assertTrue(l.repush());
        assertTrue(l.shouldPush(SUB, O2, "a", false));
        l.recordPush(SUB, O2, "a", false);

        assertTrue(l.mayNeedPush(SUB, O2));
        assertFalse("SIM still not loaded", l.shouldPush(SUB, O2, "a", false));
        assertTrue("SIM loaded now", l.shouldPush(SUB, O2, "a", true));
        l.recordPush(SUB, O2, "a", true);

        assertFalse("saved: our own CARRIER_CONFIG_CHANGED must not loop",
                l.shouldPush(SUB, O2, "a", true));
        assertFalse(l.shouldPush(SUB, O2, "a", false));
    }

    @Test
    public void repushWhenTheContentChanges() {
        CarrierConfigPushLedger l = new CarrierConfigPushLedger(true);
        l.recordPush(SUB, O2, "a", true);
        assertFalse(l.shouldPush(SUB, O2, "a", true));
        assertTrue(l.shouldPush(SUB, O2, "b", true));
        assertTrue(l.shouldPush(SUB, O2, "b", false));
        l.recordPush(SUB, O2, "b", true);
        assertFalse(l.shouldPush(SUB, O2, "b", true));
    }

    @Test
    public void repushForANewCarrierOrSubscription() {
        CarrierConfigPushLedger l = new CarrierConfigPushLedger(true);
        l.recordPush(SUB, O2, "a", true);
        assertTrue(l.shouldPush(SUB, "26207", "a", true));
        assertTrue(l.shouldPush(2, O2, "a", true));
        assertFalse(l.shouldPush(SUB, O2, "a", true));
    }

    /** At most two pushes per subscription and content: before and after SIM_STATE_LOADED. */
    @Test
    public void atMostTwoPushesPerContent() {
        CarrierConfigPushLedger l = new CarrierConfigPushLedger(true);
        boolean[] simStates = {false, false, true, true, false, true, true};
        int pushes = 0;
        for (boolean loaded : simStates) {
            if (l.shouldPush(SUB, O2, "a", loaded)) {
                l.recordPush(SUB, O2, "a", loaded);
                pushes++;
            }
        }
        assertEquals(2, pushes);
    }

    @Test
    public void propertyName() {
        assertEquals("persist.lineage.ims.cc_repush", CarrierConfigPushLedger.PROPERTY);
    }
}
