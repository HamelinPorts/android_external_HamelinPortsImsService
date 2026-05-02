package org.hamelinports.ims.net;

import android.util.Log;

/**
 * Workaround for kernels where xfrm output re-route doesn't carry the
 * socket's fwmark. Adds a source-based ip6 rule so ESP-encapsulated
 * packets from the IMS PDN address can find the cellular routing table.
 */
public final class EspRoutingFix {
    private static final String TAG = "HamelinPortsIms";

    public static void addRule(String localIpv6) {
        try {
            Runtime.getRuntime().exec(new String[]{
                    "ip", "-6", "rule", "add", "from", localIpv6 + "/128",
                    "lookup", "rmnet1", "prio", "9000"
            }).waitFor();
            Log.i(TAG, "ESP routing rule added for " + localIpv6);
        } catch (Exception e) {
            Log.w(TAG, "ESP routing rule failed (may already exist)", e);
        }
    }

    /**
     * Install the four TS 33.203 §7.4 IPsec xfrm policies by poking the
     * ims_xfrm root helper via init property-trigger. All eight parameters
     * (two IPv6 addrs, four ports, two server SPIs) are passed through
     * separate lineage.ims.xfrm.&lt;slot&gt;.* properties, and the helper
     * reads them via __system_property_get on the matching slot prefix —
     * this avoids any init.rc argument-expansion injection.
     *
     * The prop bag is per-slot so two concurrent registrations (dual-SIM)
     * cannot interleave at the property level. The whole eight-prop write
     * + trigger bump runs under a class-wide lock so two slots cannot
     * even contend on the property service round-trip in the wrong order.
     *
     * Parameters correspond to Security-Client and Security-Server in the
     * REGISTER CSeq 2 / 401 exchange:
     *   uePortC/uePortS     = UE Security-Client port-c / port-s
     *   pcscfPortC/pcscfPortS = P-CSCF Security-Server port-c / port-s
     *   serverSpiC/serverSpiS = P-CSCF Security-Server spi-c / spi-s
     */
    public static void addXfrmPolicy(int slotId, String localIpv6, String pcscfIpv6,
                                     int uePortC, int uePortS,
                                     int pcscfPortC, int pcscfPortS,
                                     int serverSpiC, int serverSpiS) {
        if (slotId != 0 && slotId != 1) {
            Log.w(TAG, "xfrm: refusing addXfrmPolicy with slotId=" + slotId);
            return;
        }
        synchronized (EspRoutingFix.class) {
            try {
                String p = "lineage.ims.xfrm." + slotId + ".";
                android.os.SystemProperties.set(p + "pcscf",       pcscfIpv6);
                android.os.SystemProperties.set(p + "local",       localIpv6);
                android.os.SystemProperties.set(p + "ue_portc",    Integer.toString(uePortC));
                android.os.SystemProperties.set(p + "ue_ports",    Integer.toString(uePortS));
                android.os.SystemProperties.set(p + "pcscf_portc", Integer.toString(pcscfPortC));
                android.os.SystemProperties.set(p + "pcscf_ports", Integer.toString(pcscfPortS));
                android.os.SystemProperties.set(p + "server_spic", String.format("0x%08x", serverSpiC));
                android.os.SystemProperties.set(p + "server_spis", String.format("0x%08x", serverSpiS));
                /* Trigger — monotonic value so the property always changes
                 * and init re-fires the on-property action. */
                android.os.SystemProperties.set(p + "trigger",
                        String.valueOf(System.currentTimeMillis()));
                Log.i(TAG, "xfrm 4-policy trigger set: slot=" + slotId + " "
                        + pcscfIpv6 + "[" + pcscfPortC + "/" + pcscfPortS + "] <-> "
                        + localIpv6 + "[" + uePortC + "/" + uePortS + "]");
            } catch (Exception e) {
                Log.w(TAG, "xfrm policy trigger failed", e);
            }
        }
    }

    /**
     * Tear down the four xfrm policies for {@code slotId}. The helper reads
     * the same per-slot prop bag the install used, so the selectors match
     * exactly and the kernel can locate them. Called from
     * {@code HamelinPortsAkaProviderImpl.close()} to keep the policy table
     * from accumulating one quartet per REGISTER cycle.
     */
    public static void removeXfrmPolicy(int slotId) {
        if (slotId != 0 && slotId != 1) return;
        synchronized (EspRoutingFix.class) {
            try {
                String p = "lineage.ims.xfrm." + slotId + ".";
                android.os.SystemProperties.set(p + "flush",
                        String.valueOf(System.currentTimeMillis()));
                Log.i(TAG, "xfrm flush trigger set: slot=" + slotId);
            } catch (Exception e) {
                Log.w(TAG, "xfrm flush trigger failed", e);
            }
        }
    }

    public static void removeRule(String localIpv6) {
        try {
            Runtime.getRuntime().exec(new String[]{
                    "ip", "-6", "rule", "del", "from", localIpv6 + "/128",
                    "lookup", "rmnet1", "prio", "9000"
            }).waitFor();
        } catch (Exception ignored) {}
    }

    private EspRoutingFix() {}
}
