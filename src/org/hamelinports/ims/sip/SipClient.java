package org.hamelinports.ims.sip;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.Random;

/**
 * Minimal SIP REGISTER client. Phase 1: plain TCP to the P-CSCF, single
 * outbound REGISTER with AKAv1-MD5 + sec-agree placeholders (no nonce, no
 * IPsec yet), read whatever comes back.
 *
 * Success for the spike = any bytes received from the P-CSCF. A 401
 * Unauthorized with a Security-Server header means the server likes our
 * framing — then Phase 2 (AKA response) and Phase 3 (IPsec) are just code.
 */
public final class SipClient {
    private static final String TAG = "HamelinPortsIms";

    public static class UeSecurityParams {
        public int spiC, spiS, portC, portS;
        public UeSecurityParams() {
            Random r = new Random();
            spiC = 10000 + r.nextInt(50000);
            spiS = spiC + 1;
            portC = 7100 + r.nextInt(900) * 2 + 1;
            portS = portC - 1;
        }
    }

    // Build an o2 DE / Telefonica_DE style REGISTER.
    // Domain is mnc/mcc-derived per 3GPP TS 23.003 §13.
    public static String makeDomain(String mccMnc) {
        // mccMnc is 5-6 digits, mcc=first3, mnc=rest (pad to 3 digits with 0).
        String mcc = mccMnc.substring(0, 3);
        String mnc = mccMnc.substring(3);
        if (mnc.length() == 2) mnc = "0" + mnc;
        return "ims.mnc" + mnc + ".mcc" + mcc + ".3gppnetwork.org";
    }

    /* REGISTER + INVITE/ACK/BYE moved to reSIProcate; see
     * jni/hamelinports_ims_jni.cpp (HamelinPortsImsAuthManager + HamelinPortsInviteHandler
     * + ImsDecorator). MO SMS (buildSipMessage) is the last hand-rolled
     * builder, pending task 9. */

    /** Picks the IMSI + mccMnc from the active subscription on the
     *  given physical slot. Returns null when no active SIM sits on
     *  that slot — this is how a dual-SIM-capable device with only
     *  one SIM present skips REGISTER on the empty slot. */
    public static Credentials readSimCredentials(Context ctx, int slotId) {
        TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
        SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        if (sm != null) {
            android.telephony.SubscriptionInfo info =
                    sm.getActiveSubscriptionInfoForSimSlotIndex(slotId);
            if (info != null) subId = info.getSubscriptionId();
        }
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            Log.i(TAG, "no active subscription on slot " + slotId);
            return null;
        }
        TelephonyManager stm = tm.createForSubscriptionId(subId);
        String imsi = stm.getSubscriberId();
        String mccMnc = stm.getSimOperator();
        if (imsi == null || mccMnc == null || mccMnc.isEmpty()) {
            Log.e(TAG, "sim not ready: imsi=" + (imsi != null) + " mccMnc=" + mccMnc);
            return null;
        }
        Credentials c = new Credentials();
        c.imsi = imsi;
        c.mccMnc = mccMnc;
        c.domain = makeDomain(mccMnc);
        // Get IMEI for +sip.instance in REGISTER
        try {
            String imei = stm.getImei();
            if (imei != null && imei.length() >= 15) {
                // Format: <urn:gsma:imei:TTTTTTTT-SSSSSS-C> — RFC 5626 §4.2
                // requires the URN to be wrapped in <...> inside the
                // quoted +sip.instance value. reSIProcate's
                // setInstanceId stores the value verbatim, so we add
                // the brackets here.
                c.imeiUrn = "<urn:gsma:imei:" + imei.substring(0, 8) + "-"
                        + imei.substring(8, 14) + "-" + imei.charAt(14) + ">";
                Log.i(TAG, "IMEI: " + redact(imei) + " URN: " + c.imeiUrn);
            }
        } catch (Exception e) {
            Log.w(TAG, "couldn't get IMEI", e);
        }
        if (c.imeiUrn == null) {
            c.imeiUrn = "<urn:gsma:imei:35235111-516680-0>"; // fallback
        }
        Log.i(TAG, "creds imsi=" + redact(imsi) + " mccMnc=" + mccMnc + " domain=" + c.domain);
        return c;
    }

    private static String redact(String s) {
        if (s == null || s.length() < 6) return "***";
        return s.substring(0, 3) + "***" + s.substring(s.length() - 2);
    }

    public static class Credentials {
        public String imsi;
        public String mccMnc;
        public String domain;
        public String imeiUrn; // urn:gsma:imei:TTTTTTTT-SSSSSS-C format
    }

    /* All hand-rolled SIP builders are gone. REGISTER, INVITE, ACK,
     * BYE, MESSAGE all run through reSIProcate via HamelinPortsSipStack
     * (see jni/hamelinports_ims_jni.cpp). What remains in this class is
     * SIM/credential reading + helpers used by the Java-side bits
     * that still touch SIP-level data (toNationalForm for the INVITE
     * Request-URI, getCellIdForPani for the PANI value the native
     * decorator stamps). */

    /**
     * Convert an E.164 / dialled number to its national form per the SIM's
     * region. Used when emitting Request-URI / To headers with phone-context
     * (RFC 3966 §5.1.5): e.g. `015732220078` from `+4915732220078` when the
     * SIM's country code is 49 and trunk prefix is `0`. Returns the input
     * unchanged if we can't identify the region's country calling code or
     * trunk prefix.
     *
     * The table below covers the OEM-agnostic set we are likely to run this
     * stack on; add more entries freely — the key property is that we MUST
     * emit the form the carrier's TAS expects, not what's linguistically
     * "more canonical". Carriers that use global (+) form directly will need
     * a per-carrier override; Telefonica DE's TAS rejects the + form with
     * phone-context and wants national form only.
     */
    /**
     * Current cell's PANI value per TS 24.229 §7.2A.4:
     *   utran-cell-id-3gpp=<MCC><MNC><TAC-hex-4><CID-hex-7>
     * (for E-UTRAN; 16 hex characters total after MCC+MNC).
     *
     * Example: `2620359da07fa629` =
     *   MCC 262, MNC 03, TAC 0x59da, Cell ID 0x07fa629.
     *
     * Returns null if we can't resolve a registered LTE cell (no ServiceState,
     * wrong RAT, missing TAC/CI). Caller omits the PANI header in that case.
     */
    /** Refresh the P-Access-Network-Info cache on the supplied
     *  {@link HamelinPortsSipStack} with the right flavour for the
     *  current IMS PDN underlying access. Wi-Fi Calling carries
     *  IEEE-802.11 with the AP BSSID; cellular IMS carries
     *  3GPP-E-UTRAN-FDD with the LTE cell-id-3gpp. The decorator on
     *  the native side picks whichever cache field is non-empty.
     *
     *  The {@code iface} comes from
     *  {@link ImsRegistrationController#getLastBoundIface()}; AOSP names
     *  IpSec tunnel interfaces {@code ipsecN} and cellular IMS PDNs
     *  {@code rmnetN}. Callers (HamelinPortsCallSession, HamelinPortsSmsImpl)
     *  call this immediately before each MO INVITE / MO MESSAGE / RP-ACK,
     *  passing their slot's stack instance.
     */
    public static void refreshPaniForOutbound(Context ctx, String iface,
                                              HamelinPortsSipStack stack) {
        if (stack == null) return;
        if (iface != null && iface.startsWith("ipsec")) {
            String node = getWifiBssidForPani(ctx);
            if (node != null) {
                stack.setIwlanNodeIdForPani(node);
            } else {
                /* No BSSID — still emit the IEEE-802.11 token (with an
                 * empty i-wlan-node-id parameter) rather than fall back
                 * to the stale 3GPP-E-UTRAN-FDD value. The carrier may
                 * accept it; if it doesn't, we'll see the rejection and
                 * iterate. The wrong access token is worse than a
                 * missing parameter. */
                stack.setIwlanNodeIdForPani("");
            }
        } else {
            String cellId = getCellIdForPani(ctx);
            if (cellId != null) {
                stack.setCellIdForPani(cellId);
            }
        }
    }

    /** AP BSSID of the currently associated Wi-Fi network, formatted for
     *  the i-wlan-node-id parameter (colons stripped, uppercase, e.g.
     *  "B6FC7D11A6B0"). Returns null if Wi-Fi isn't connected, the API
     *  caller has insufficient privileges to read the real BSSID, or
     *  the framework returned the anonymised "02:00:00:00:00:00".
     *
     *  Per TS 24.229 §7.2A.4 the value is the WLAN AP MAC. */
    public static String getWifiBssidForPani(Context ctx) {
        try {
            android.net.wifi.WifiManager wm =
                    ctx.getSystemService(android.net.wifi.WifiManager.class);
            if (wm == null) return null;
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String bssid = info.getBSSID();
            if (bssid == null
                    || bssid.isEmpty()
                    || "02:00:00:00:00:00".equals(bssid)) {
                return null;
            }
            return bssid.replace(":", "").toUpperCase();
        } catch (Exception e) {
            Log.w(TAG, "getWifiBssidForPani failed: " + e);
            return null;
        }
    }

    public static String getCellIdForPani(Context ctx) {
        try {
            android.telephony.TelephonyManager tm =
                    ctx.getSystemService(android.telephony.TelephonyManager.class);
            if (tm == null) return null;
            android.telephony.ServiceState ss = tm.getServiceState();
            if (ss == null) return null;
            for (android.telephony.NetworkRegistrationInfo nri :
                    ss.getNetworkRegistrationInfoList()) {
                if (nri.getDomain() != android.telephony.NetworkRegistrationInfo.DOMAIN_PS) continue;
                android.telephony.CellIdentity id = nri.getCellIdentity();
                if (!(id instanceof android.telephony.CellIdentityLte)) continue;
                android.telephony.CellIdentityLte lte = (android.telephony.CellIdentityLte) id;
                String mcc = lte.getMccString();
                String mnc = lte.getMncString();
                int tac = lte.getTac();
                int ci = lte.getCi();
                if (mcc == null || mnc == null
                        || tac == Integer.MAX_VALUE || ci == Integer.MAX_VALUE) continue;
                return String.format("%s%s%04x%07x", mcc, mnc, tac, ci);
            }
        } catch (Exception e) {
            Log.w(TAG, "getCellIdForPani failed: " + e);
        }
        return null;
    }

    public static String toNationalForm(String number, String regionIso) {
        if (number == null || !number.startsWith("+") || regionIso == null) return number;
        String iso = regionIso.toUpperCase();
        int cc; String trunk;
        switch (iso) {
            case "DE": cc = 49; trunk = "0"; break;
            case "AT": cc = 43; trunk = "0"; break;
            case "CH": cc = 41; trunk = "0"; break;
            case "FR": cc = 33; trunk = "0"; break;
            case "IT": cc = 39; trunk = "0"; break;
            case "ES": cc = 34; trunk = "0"; break;
            case "NL": cc = 31; trunk = "0"; break;
            case "BE": cc = 32; trunk = "0"; break;
            case "GB": cc = 44; trunk = "0"; break;
            case "DK": cc = 45; trunk = "";  break;  // Denmark has no trunk prefix
            case "NO": cc = 47; trunk = "";  break;
            case "FI": cc = 358; trunk = "0"; break;
            case "SE": cc = 46; trunk = "0"; break;
            case "PL": cc = 48; trunk = "";  break;
            case "US": case "CA": cc = 1; trunk = "1"; break;  // NANP trunk = 1 (LD prefix)
            default: return number;
        }
        String prefix = "+" + cc;
        if (!number.startsWith(prefix)) return number;
        return trunk + number.substring(prefix.length());
    }

    private SipClient() {}
}
