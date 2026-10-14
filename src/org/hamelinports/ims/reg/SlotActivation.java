package org.hamelinports.ims.reg;

/**
 * Whether the registration machine of a slot runs. It runs only while the
 * framework wants IMS on the slot and the slot has an active subscription.
 * A slot without one (no SIM) stays idle: it arms no retry and no alarm and
 * never touches the process network binding.
 *
 * <p>Pure Java, so the host tests drive it directly.
 */
public final class SlotActivation {

    /** Subscription id of a slot without an active subscription. */
    public static final int NO_SUBSCRIPTION = -1;

    /** What the controller should do with its registration machine. */
    public enum Action {
        /** Nothing changes. */
        NONE,
        /** Start the machine for the active subscription. */
        START,
        /** Stop the machine: IMS is disabled, or the subscription is gone. */
        STOP,
        /** The subscription changed under the running machine: stop, then start. */
        RESTART,
        /** IMS is wanted but the slot has no active subscription: stay stopped. */
        IDLE,
    }

    /** Result of reading the SIM credentials for a registration attempt. */
    public enum CredentialStatus {
        READY,
        /** No active subscription on the slot: nothing to register, now or later. */
        NO_SUBSCRIPTION,
        /** A subscription exists but its IMSI or PLMN is not readable yet (boot, PIN lock). */
        SIM_NOT_READY,
    }

    /** What follows a credential read. */
    public enum NextStep {
        /** Register now. */
        REGISTER,
        /** The attempt fails and goes through the bounded retry. */
        RETRY,
        /** Stop the machine without a retry; a subscription change starts it again. */
        IDLE,
    }

    /** Why a STOP or RESTART waits. */
    public enum StopHold {
        /** Act now. */
        NONE,
        /** The subscription loss or change may be transient (SIM refresh, modem restart). */
        DEBOUNCE,
        /** A call holds the stack; stopping it would drop the call. */
        CALL,
    }

    /**
     * How long a lost or changed subscription must persist before the machine
     * stops or restarts. A SIM refresh or modem restart drops and restores the
     * active subscription within a burst of subscription updates lasting a few
     * seconds; a real removal or swap loses nothing by waiting this long (the
     * registration of a removed SIM is dead either way, and the framework
     * also disables IMS for it).
     */
    public static final long SUBSCRIPTION_DEBOUNCE_MS = 4_000;

    private SlotActivation() {}

    public static boolean stopsMachine(Action action) {
        return action == Action.STOP || action == Action.RESTART;
    }

    /**
     * Whether a STOP or RESTART must wait.
     *
     * @param subscriptionDriven the action follows a subscription loss or change
     *                           (IMS still enabled), not a framework disable
     * @param pendingSinceMs     when the subscription-driven action was first seen
     * @param callHoldsStack     a call needs the current stack (an emergency
     *                           session from its creation)
     */
    public static StopHold holdStop(boolean subscriptionDriven, long pendingSinceMs,
                                    long nowMs, boolean callHoldsStack) {
        if (callHoldsStack) {
            return StopHold.CALL;
        }
        if (subscriptionDriven && nowMs - pendingSinceMs < SUBSCRIPTION_DEBOUNCE_MS) {
            return StopHold.DEBOUNCE;
        }
        return StopHold.NONE;
    }

    /** When to evaluate a held STOP or RESTART again. */
    public static long recheckDelayMs(StopHold hold, long pendingSinceMs, long nowMs) {
        switch (hold) {
            case DEBOUNCE:
                return Math.max(0, pendingSinceMs + SUBSCRIPTION_DEBOUNCE_MS - nowMs);
            case CALL:
                return RefreshPolicy.ACTIVE_CALL_RECHECK_MS;
            default:
                return 0;
        }
    }

    public static boolean isActive(int subId) {
        return subId > NO_SUBSCRIPTION;
    }

    /**
     * @param imsEnabled   the framework has enabled IMS on the slot (feature
     *                     ready or enableImsForSubscription) and not disabled it
     * @param activeSubId  the slot's active subscription, or {@link #NO_SUBSCRIPTION}
     * @param runningSubId the subscription the machine runs for, or
     *                     {@link #NO_SUBSCRIPTION} while it is stopped
     */
    public static Action decide(boolean imsEnabled, int activeSubId, int runningSubId) {
        boolean running = isActive(runningSubId);
        if (!imsEnabled || !isActive(activeSubId)) {
            if (running) {
                return Action.STOP;
            }
            return imsEnabled ? Action.IDLE : Action.NONE;
        }
        if (!running) {
            return Action.START;
        }
        return runningSubId == activeSubId ? Action.NONE : Action.RESTART;
    }

    public static NextStep afterCredentialRead(CredentialStatus status) {
        switch (status) {
            case READY:
                return NextStep.REGISTER;
            case NO_SUBSCRIPTION:
                return NextStep.IDLE;
            default:
                return NextStep.RETRY;
        }
    }
}
