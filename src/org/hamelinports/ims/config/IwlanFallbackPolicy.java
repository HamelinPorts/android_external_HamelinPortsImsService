package org.hamelinports.ims.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hand-over of IMS from Wi-Fi calling to cellular when the IWLAN data path
 * fails, from the {@value #PROPERTY} property. Unset or 0 means off; a value
 * in {@value #MIN_SEC}..{@value #MAX_SEC} is the time in seconds QNS keeps
 * IMS off Wi-Fi after the app reports the failure. Anything else is rejected
 * and means off.
 *
 * <p>The QNS rules are carrier-config string arrays. A persistent override
 * is merged into the stored one key by key, so a key cannot be removed by
 * leaving it out: with the feature off, a key that still holds rules is
 * overwritten with an empty array.
 *
 * <p>Pure Java, so the host tests drive it directly.
 */
public final class IwlanFallbackPolicy {
    /** Property a device sets to enable the fallback, in seconds. */
    public static final String PROPERTY = "persist.lineage.ims.iwlan_fallback.sec";
    public static final int MIN_SEC = 60;
    public static final int MAX_SEC = 3600;

    /**
     * IMS deregistration reason the app reports for a failed IWLAN data path
     * (ImsReasonInfo.CODE_EPDG_TUNNEL_LOST_CONNECTION). Not 131: the app
     * reports that on every idle iface swap, so a rule on it would flap.
     */
    public static final int REASON_EPDG_TUNNEL_LOST_CONNECTION = 1402;

    public static final String KEY_UNREGISTRATION_RULES =
            "qns.fallback_wwan_ims_unregistration_reason_string_array";
    public static final String KEY_INITIAL_CONNECTION_FAILURE_RULES =
            "qns.fallback_on_initial_connection_failure_string_array";

    /**
     * IMS falls back after the 2nd consecutive failed IWLAN setup (retry
     * count 1) or after 60 s of failed retries, stays on cellular for
     * 10 min, at most 3 times (QNS RestrictManager).
     */
    static final String INITIAL_CONNECTION_FAILURE_RULE = "ims:1:60000:600000:3";

    private final int mSeconds;
    private final boolean mRejected;

    private IwlanFallbackPolicy(int seconds, boolean rejected) {
        mSeconds = seconds;
        mRejected = rejected;
    }

    /** The policy for the raw property value; null, blank or 0 means off. */
    public static IwlanFallbackPolicy fromProperty(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new IwlanFallbackPolicy(0, false);
        }
        int seconds;
        try {
            seconds = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return new IwlanFallbackPolicy(0, true);
        }
        if (seconds == 0) {
            return new IwlanFallbackPolicy(0, false);
        }
        if (seconds < MIN_SEC || seconds > MAX_SEC) {
            return new IwlanFallbackPolicy(0, true);
        }
        return new IwlanFallbackPolicy(seconds, false);
    }

    public boolean enabled() {
        return mSeconds > 0;
    }

    /** Time QNS keeps IMS on cellular after a report, in seconds; 0 when off. */
    public int seconds() {
        return mSeconds;
    }

    /** True if the property was set to a value that is neither off nor in range. */
    public boolean rejected() {
        return mRejected;
    }

    /** QNS unregistration rule: reason 1402 restricts Wi-Fi for {@link #seconds()}. */
    public String unregistrationRule() {
        return "cause=" + REASON_EPDG_TUNNEL_LOST_CONNECTION + ", time=" + mSeconds * 1000L;
    }

    /**
     * Carrier-config string arrays to push. On: both rule arrays. Off: an
     * empty array for each key whose current merged value still holds rules
     * (a stale override from an earlier "on"); nothing otherwise.
     *
     * @param currentUnregistration current value of {@link #KEY_UNREGISTRATION_RULES}, or null
     * @param currentInitialFailure current value of {@link #KEY_INITIAL_CONNECTION_FAILURE_RULES}, or null
     */
    public Map<String, String[]> overrides(String[] currentUnregistration,
            String[] currentInitialFailure) {
        Map<String, String[]> out = new LinkedHashMap<>();
        if (enabled()) {
            out.put(KEY_UNREGISTRATION_RULES, new String[] {unregistrationRule()});
            out.put(KEY_INITIAL_CONNECTION_FAILURE_RULES,
                    new String[] {INITIAL_CONNECTION_FAILURE_RULE});
            return out;
        }
        if (holdsRules(currentUnregistration)) {
            out.put(KEY_UNREGISTRATION_RULES, new String[0]);
        }
        if (holdsRules(currentInitialFailure)) {
            out.put(KEY_INITIAL_CONNECTION_FAILURE_RULES, new String[0]);
        }
        return out;
    }

    private static boolean holdsRules(String[] value) {
        return value != null && value.length > 0;
    }
}
