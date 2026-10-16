package org.hamelinports.ims.config;

/**
 * IKE dead peer detection interval for the Wi-Fi calling tunnel, from the
 * {@value #PROPERTY} property. Unset means {@value #DEFAULT_SEC} s. A value
 * outside what IWLAN accepts is rejected here, because IWLAN would silently
 * replace it with its own 120 s default.
 *
 * <p>Pure Java, so the host tests drive it directly.
 */
public final class IwlanDpdInterval {
    /** Property a device sets to change the interval, in seconds. */
    public static final String PROPERTY = "persist.lineage.ims.dpd.sec";
    public static final int DEFAULT_SEC = 300;
    /** IWLAN's accepted range (EpdgTunnelManager IKE_DPD_DELAY_SEC_MIN/MAX). */
    public static final int MIN_SEC = 20;
    public static final int MAX_SEC = 1800;

    private final int mSeconds;
    private final boolean mRejected;

    private IwlanDpdInterval(int seconds, boolean rejected) {
        mSeconds = seconds;
        mRejected = rejected;
    }

    /** The interval for the raw property value; null or blank means unset. */
    public static IwlanDpdInterval fromProperty(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new IwlanDpdInterval(DEFAULT_SEC, false);
        }
        int seconds;
        try {
            seconds = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return new IwlanDpdInterval(DEFAULT_SEC, true);
        }
        if (seconds < MIN_SEC || seconds > MAX_SEC) {
            return new IwlanDpdInterval(DEFAULT_SEC, true);
        }
        return new IwlanDpdInterval(seconds, false);
    }

    /** The interval to put into the carrier config, in seconds. */
    public int seconds() {
        return mSeconds;
    }

    /** True if the property was set to a value IWLAN would not accept. */
    public boolean rejected() {
        return mRejected;
    }
}
