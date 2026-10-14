package org.hamelinports.ims.reg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.hamelinports.ims.reg.SlotActivation.Action;
import org.hamelinports.ims.reg.SlotActivation.CredentialStatus;
import org.hamelinports.ims.reg.SlotActivation.NextStep;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SlotActivationTest {
    private static final int NONE = SlotActivation.NO_SUBSCRIPTION;
    private static final int SUB_A = 1;
    private static final int SUB_B = 2;
    private static final long S = 1000L;
    private static final long T0 = 5_000_000L;

    @Test
    public void startsOnlyWhenEnabledWithAnActiveSubscription() {
        assertEquals(Action.START, SlotActivation.decide(true, SUB_A, NONE));
        assertEquals(Action.NONE, SlotActivation.decide(false, SUB_A, NONE));
        assertEquals(Action.IDLE, SlotActivation.decide(true, NONE, NONE));
        assertEquals(Action.NONE, SlotActivation.decide(false, NONE, NONE));
    }

    @Test
    public void subscriptionZeroIsActive() {
        assertTrue(SlotActivation.isActive(0));
        assertFalse(SlotActivation.isActive(NONE));
        assertEquals(Action.START, SlotActivation.decide(true, 0, NONE));
    }

    @Test
    public void stopsWhenDisabledOrSubscriptionGone() {
        assertEquals(Action.STOP, SlotActivation.decide(false, SUB_A, SUB_A));
        assertEquals(Action.STOP, SlotActivation.decide(true, NONE, SUB_A));
        assertEquals(Action.STOP, SlotActivation.decide(false, NONE, SUB_A));
    }

    @Test
    public void subscriptionChangeRestarts() {
        assertEquals(Action.RESTART, SlotActivation.decide(true, SUB_B, SUB_A));
        assertEquals(Action.NONE, SlotActivation.decide(true, SUB_A, SUB_A));
    }

    @Test
    public void credentialStatusMapping() {
        assertEquals(NextStep.REGISTER, SlotActivation.afterCredentialRead(CredentialStatus.READY));
        assertEquals(NextStep.IDLE,
                SlotActivation.afterCredentialRead(CredentialStatus.NO_SUBSCRIPTION));
        assertEquals(NextStep.RETRY,
                SlotActivation.afterCredentialRead(CredentialStatus.SIM_NOT_READY));
    }

    /** The controller's stop sequence: running off, then invalidate (tearDown). */
    @Test
    public void idleTransitionArmsNothingAndDropsStaleCallbacks() {
        RegistrationState s = new RegistrationState();
        s.setRunning(true);
        int gen = s.beginFresh(T0);
        s.endAttempt();
        assertTrue(s.scheduleRetry(T0, 0, "attempt retry"));
        s.setRunning(false);
        s.invalidate();
        assertEquals(-1, s.retryAtMs());
        assertNull(RefreshPolicy.nextCheck(s, T0 + S));
        assertEquals(RefreshPolicy.Action.NOOP, RefreshPolicy.decide(s, T0 + 3600 * S));
        assertTrue(s.generation() > gen);
        assertFalse(s.isCurrent(gen));
    }

    /**
     * Mirrors ImsRegistrationController: activation decides whether the
     * machine runs; a fresh attempt first reads the credentials, NO_SUBSCRIPTION
     * stops the machine, anything else that cannot be sent goes through the
     * bounded retry and then the long tail.
     */
    private static final class Slot {
        final RegistrationState state = new RegistrationState();
        boolean enabled;
        int runningSubId = NONE;
        int activeSubId = NONE;
        CredentialStatus credentials = CredentialStatus.READY;
        int starts;
        int retries;
        int longTails;
        int attempts;

        void evaluate() {
            switch (SlotActivation.decide(enabled, activeSubId, runningSubId)) {
                case START:
                    start();
                    break;
                case RESTART:
                    stop();
                    start();
                    break;
                case STOP:
                    stop();
                    break;
                default:
                    break;
            }
        }

        void start() {
            state.setRunning(true);
            runningSubId = activeSubId;
            starts++;
        }

        void stop() {
            state.setRunning(false);
            runningSubId = NONE;
            state.invalidate();
        }

        /** The PDN came up (a SIM-less slot still receives the other slot's IMS PDN). */
        void pdnAvailable(long now) {
            if (state.isRunning() && state.isIdle()) fresh(now);
        }

        void fresh(long now) {
            NextStep next = SlotActivation.afterCredentialRead(credentials);
            if (next == NextStep.IDLE) {
                stop();
                return;
            }
            attempts++;
            state.beginFresh(now);
            if (next == NextStep.REGISTER) {
                state.onRegistered(now + 2 * S, 3600, 0);
                return;
            }
            state.endAttempt();
            if (state.scheduleRetry(now, 0, "attempt retry")) {
                retries++;
            } else {
                state.scheduleLongTail(now);
                longTails++;
            }
        }

        /** Runs the alarm-driven machine for {@code durationMs}; returns the alarms it armed. */
        int runFor(long from, long durationMs) {
            int alarms = 0;
            long now = from;
            while (true) {
                RefreshPolicy.Window w = RefreshPolicy.nextCheck(state, now);
                if (w == null) break;
                alarms++;
                now = Math.max(now + 1, w.startMs);
                if (now >= from + durationMs) break;
                if (RefreshPolicy.decide(state, now) == RefreshPolicy.Action.FRESH) fresh(now);
            }
            return alarms;
        }
    }

    @Test
    public void simLessSlotStaysIdleForADay() {
        Slot slot0 = new Slot();
        slot0.evaluate();                       /* listener fires at registration */
        slot0.enabled = true;                   /* onFeatureReady */
        slot0.evaluate();
        slot0.pdnAvailable(T0);
        assertEquals(0, slot0.starts);
        assertFalse(slot0.state.isRunning());
        assertEquals(0, slot0.runFor(T0, 24 * 3600 * S));
        assertEquals(0, slot0.attempts);
        assertEquals(0, slot0.retries);
        assertEquals(0, slot0.longTails);
    }

    @Test
    public void subscriptionVanishingUnderAnAttemptIdlesWithoutRetry() {
        Slot slot = new Slot();
        slot.enabled = true;
        slot.activeSubId = SUB_A;
        slot.evaluate();
        assertTrue(slot.state.isRunning());
        slot.credentials = CredentialStatus.NO_SUBSCRIPTION;   /* SIM pulled before the PDN */
        slot.pdnAvailable(T0);
        assertFalse(slot.state.isRunning());
        assertEquals(0, slot.retries);
        assertEquals(0, slot.longTails);
        assertEquals(-1, slot.state.retryAtMs());
        assertEquals(0, slot.runFor(T0, 24 * 3600 * S));
    }

    @Test
    public void simNotReadyRetriesBoundedThenLongTail() {
        Slot slot = new Slot();
        slot.enabled = true;
        slot.activeSubId = SUB_A;
        slot.evaluate();
        slot.credentials = CredentialStatus.SIM_NOT_READY;
        slot.pdnAvailable(T0);
        slot.runFor(T0, 10 * 60 * S);
        assertEquals(RefreshPolicy.REG_MAX_RETRIES, slot.retries);
        assertEquals(1, slot.longTails);
        assertEquals(RefreshPolicy.REG_MAX_RETRIES + 1, slot.attempts);
        assertTrue(slot.state.retryAtMs() > T0 + 10 * 60 * S);
    }

    @Test
    public void simInsertedLaterStartsAndRegisters() {
        Slot slot = new Slot();
        slot.enabled = true;
        slot.evaluate();
        assertFalse(slot.state.isRunning());
        slot.activeSubId = SUB_A;               /* subscription listener */
        slot.evaluate();
        assertTrue(slot.state.isRunning());
        slot.pdnAvailable(T0);
        assertTrue(slot.state.isRegistered());
        assertEquals(1, slot.starts);
    }

    @Test
    public void subscriptionSwapRestartsWithANewGeneration() {
        Slot slot = new Slot();
        slot.enabled = true;
        slot.activeSubId = SUB_A;
        slot.evaluate();
        slot.pdnAvailable(T0);
        int gen = slot.state.generation();
        slot.activeSubId = SUB_B;
        slot.evaluate();
        assertEquals(2, slot.starts);
        assertEquals(SUB_B, slot.runningSubId);
        assertFalse(slot.state.isRegistered());
        assertFalse(slot.state.isCurrent(gen));
    }

    @Test
    public void disableStopsAndSubscriptionChangesDoNotRestart() {
        Slot slot = new Slot();
        slot.enabled = true;
        slot.activeSubId = SUB_A;
        slot.evaluate();
        slot.enabled = false;                   /* disableImsForSubscription */
        slot.evaluate();
        assertFalse(slot.state.isRunning());
        slot.activeSubId = SUB_B;
        slot.evaluate();
        assertFalse(slot.state.isRunning());
        assertEquals(1, slot.starts);
    }
}
