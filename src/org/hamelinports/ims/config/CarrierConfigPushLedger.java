package org.hamelinports.ims.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Decides whether the carrier-config override of a subscription must be
 * pushed again.
 *
 * <p>Telephony stores a persistent override on disk only while the SIM
 * records are loaded; a push before that lives in memory until the next
 * boot. With re-push on ({@value #PROPERTY}), a subscription is pushed when
 * it is new, its carrier or override content changed, or the last push ran
 * before SIM_STATE_LOADED and the SIM is loaded now: at most two pushes per
 * subscription and content. With re-push off, only a new subscription or
 * carrier is pushed.
 *
 * <p>Pure Java, so the host tests drive it directly. Not thread-safe.
 */
public final class CarrierConfigPushLedger {
    /** Device switch for the re-push; unset means off. */
    public static final String PROPERTY = "persist.lineage.ims.cc_repush";

    private static final class Push {
        final String mMccMnc;
        final String mDigest;
        final boolean mSavedWhileLoaded;

        Push(String mccMnc, String digest, boolean savedWhileLoaded) {
            mMccMnc = mccMnc;
            mDigest = digest;
            mSavedWhileLoaded = savedWhileLoaded;
        }
    }

    private final boolean mRepush;
    private final Map<Integer, Push> mPushes = new HashMap<>();

    public CarrierConfigPushLedger(boolean repush) {
        mRepush = repush;
    }

    public boolean repush() {
        return mRepush;
    }

    /**
     * False when no push can follow whatever the content: re-push off and
     * this carrier already pushed. Lets the caller skip building the bundle.
     */
    public boolean mayNeedPush(int subId, String mccMnc) {
        Push last = mPushes.get(subId);
        return mRepush || last == null || !last.mMccMnc.equals(mccMnc);
    }

    /**
     * @param digest   digest of the override content to push
     * @param simLoaded the slot's SIM is in SIM_STATE_LOADED now
     */
    public boolean shouldPush(int subId, String mccMnc, String digest, boolean simLoaded) {
        Push last = mPushes.get(subId);
        if (last == null || !last.mMccMnc.equals(mccMnc)) {
            return true;
        }
        if (!mRepush) {
            return false;
        }
        return !Objects.equals(last.mDigest, digest) || (!last.mSavedWhileLoaded && simLoaded);
    }

    /**
     * Records a successful push.
     *
     * @param simLoadedBeforePush SIM state read before the push: Telephony
     *                            saved the override to disk only if loaded
     */
    public void recordPush(int subId, String mccMnc, String digest, boolean simLoadedBeforePush) {
        mPushes.put(subId, new Push(mccMnc, digest, simLoadedBeforePush));
    }
}
