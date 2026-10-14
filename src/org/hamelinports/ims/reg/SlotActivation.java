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

    private SlotActivation() {}

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
