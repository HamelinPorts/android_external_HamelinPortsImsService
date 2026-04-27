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
     * separate lineage.ims.xfrm.* properties, and the helper reads them via
     * __system_property_get — this avoids any init.rc argument-expansion
     * injection and is the same pattern used for ims_ipsec_setup.
     *
     * Parameters correspond to Security-Client and Security-Server in the
     * REGISTER CSeq 2 / 401 exchange:
     *   uePortC/uePortS     = UE Security-Client port-c / port-s
     *   pcscfPortC/pcscfPortS = P-CSCF Security-Server port-c / port-s
     *   serverSpiC/serverSpiS = P-CSCF Security-Server spi-c / spi-s
     */
    public static void addXfrmPolicy(String localIpv6, String pcscfIpv6,
                                     int uePortC, int uePortS,
                                     int pcscfPortC, int pcscfPortS,
                                     int serverSpiC, int serverSpiS) {
        try {
            android.os.SystemProperties.set("lineage.ims.xfrm.pcscf", pcscfIpv6);
            android.os.SystemProperties.set("lineage.ims.xfrm.local", localIpv6);
            android.os.SystemProperties.set("lineage.ims.xfrm.ue_portc",    Integer.toString(uePortC));
            android.os.SystemProperties.set("lineage.ims.xfrm.ue_ports",    Integer.toString(uePortS));
            android.os.SystemProperties.set("lineage.ims.xfrm.pcscf_portc", Integer.toString(pcscfPortC));
            android.os.SystemProperties.set("lineage.ims.xfrm.pcscf_ports", Integer.toString(pcscfPortS));
            android.os.SystemProperties.set("lineage.ims.xfrm.server_spic", String.format("0x%08x", serverSpiC));
            android.os.SystemProperties.set("lineage.ims.xfrm.server_spis", String.format("0x%08x", serverSpiS));
            // Trigger — monotonic value so the property always changes
            android.os.SystemProperties.set("lineage.ims.xfrm.trigger",
                    String.valueOf(System.currentTimeMillis()));
            Log.i(TAG, "xfrm 4-policy trigger set: "
                    + pcscfIpv6 + "[" + pcscfPortC + "/" + pcscfPortS + "] <-> "
                    + localIpv6 + "[" + uePortC + "/" + uePortS + "]");
        } catch (Exception e) {
            Log.w(TAG, "xfrm policy trigger failed", e);
        }
    }

    private static void exec(String... cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(cmd);
        int rc = p.waitFor();
        if (rc != 0) {
            byte[] err = p.getErrorStream().readAllBytes();
            Log.w(TAG, "cmd rc=" + rc + ": " + new String(err));
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
