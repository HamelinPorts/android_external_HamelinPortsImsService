package org.hamelinports.ims.sip;

/**
 * An RP-ERROR (network to MS, TS 24.011 §7.3.4) answering an MO SMS: the
 * RP-Cause with its optional diagnostic (§8.2.5.4) and, if present, the
 * SMS-SUBMIT-REPORT for RP-ERROR carried as RP-User-Data (TS 23.040
 * §9.2.2.2a: TP-FCS, TP-PI, TP-SCTS, optional TP-PID/DCS/UDL).
 *
 * <p>Neither element carries an address, so the raw octets kept for the log
 * hold no phone number; {@link #parse} accepts nothing but an RP-ERROR.
 *
 * <p>Pure Java, so the host tests drive it directly.
 */
public final class RpError {
    public static final int RP_MTI_ERROR_N_TO_MS = 5;
    private static final int IEI_RP_USER_DATA = 0x41;
    private static final int TP_MTI_SUBMIT_REPORT = 0x01;

    /** RP Message Reference; matches the RP-DATA it answers. */
    public final int rpMr;
    /** RP-Cause value (7 bits), or -1 if the cause element is missing or empty. */
    public final int cause;
    /** Diagnostic octet of the RP-Cause, or -1 if absent. */
    public final int diagnostic;
    /** The SMS-SUBMIT-REPORT is present and parsed. */
    public final boolean hasReport;
    /** TP-FCS of the report, or -1. */
    public final int failureCause;
    /** TP-PI of the report, or -1. */
    public final int parameterIndicator;
    /** TP-SCTS as semi-octet digits "YYMMDDhhmmss+TZ", or null. */
    public final String serviceCentreTime;
    /** TP-UDL of the report, or -1 if TP-PI announces no user data. */
    public final int userDataLength;
    private final String mOctets;

    private RpError(int rpMr, int cause, int diagnostic, boolean hasReport, int failureCause,
            int parameterIndicator, String serviceCentreTime, int userDataLength, String octets) {
        this.rpMr = rpMr;
        this.cause = cause;
        this.diagnostic = diagnostic;
        this.hasReport = hasReport;
        this.failureCause = failureCause;
        this.parameterIndicator = parameterIndicator;
        this.serviceCentreTime = serviceCentreTime;
        this.userDataLength = userDataLength;
        mOctets = octets;
    }

    /** Decodes an RP-ERROR (n→ms); null for any other RP-PDU or one too short for MTI and MR. */
    public static RpError parse(byte[] pdu) {
        if (pdu == null || pdu.length < 2 || (pdu[0] & 0x07) != RP_MTI_ERROR_N_TO_MS) {
            return null;
        }
        int rpMr = pdu[1] & 0xFF;
        int cause = -1;
        int diagnostic = -1;
        int i = 2;
        if (i < pdu.length) {
            int causeLen = pdu[i++] & 0xFF;
            int end = Math.min(pdu.length, i + causeLen);
            if (causeLen >= 1 && i < end) cause = pdu[i] & 0x7F;
            if (causeLen >= 2 && i + 1 < end) diagnostic = pdu[i + 1] & 0xFF;
            i = end;
        }
        boolean hasReport = false;
        int fcs = -1;
        int pi = -1;
        String scts = null;
        int udl = -1;
        if (i + 2 <= pdu.length && (pdu[i] & 0xFF) == IEI_RP_USER_DATA) {
            int len = pdu[i + 1] & 0xFF;
            int start = i + 2;
            int end = Math.min(pdu.length, start + len);
            /* MTI octet, TP-FCS, TP-PI and the 7-octet TP-SCTS are mandatory. */
            if (end - start >= 10 && (pdu[start] & 0x03) == TP_MTI_SUBMIT_REPORT) {
                hasReport = true;
                fcs = pdu[start + 1] & 0xFF;
                pi = pdu[start + 2] & 0xFF;
                scts = semiOctets(pdu, start + 3, 6) + timeZone(pdu[start + 9] & 0xFF);
                int j = start + 10;
                if ((pi & 0x01) != 0) j++;            // TP-PID
                if ((pi & 0x02) != 0) j++;            // TP-DCS
                if ((pi & 0x04) != 0 && j < end) udl = pdu[j] & 0xFF;
            }
        }
        return new RpError(rpMr, cause, diagnostic, hasReport, fcs, pi, scts, udl, octets(pdu));
    }

    /** One log line: the decoded fields and the raw RP-ERROR octets. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("RP-MR=").append(rpMr)
                .append(" cause=").append(cause).append(" (").append(causeName(cause)).append(')')
                .append(" diagnostic=").append(diagnostic < 0 ? "none" : twoDigits(diagnostic));
        if (hasReport) {
            sb.append(" SUBMIT-REPORT TP-FCS=0x").append(twoDigits(failureCause))
                    .append(" TP-PI=0x").append(twoDigits(parameterIndicator))
                    .append(" TP-SCTS=").append(serviceCentreTime)
                    .append(" TP-UDL=").append(userDataLength < 0 ? "none" : userDataLength);
        } else {
            sb.append(" no SUBMIT-REPORT");
        }
        sb.append(" octets=").append(mOctets);
        return sb.toString();
    }

    /** TS 24.011 Table 8.4 names for the causes an MO SMS can get. */
    static String causeName(int cause) {
        switch (cause) {
            case 1: return "unassigned number";
            case 8: return "operator determined barring";
            case 10: return "call barred";
            case 11: return "reserved";
            case 21: return "short message transfer rejected";
            case 27: return "destination out of order";
            case 28: return "unidentified subscriber";
            case 29: return "facility rejected";
            case 30: return "unknown subscriber";
            case 38: return "network out of order";
            case 41: return "temporary failure";
            case 42: return "congestion";
            case 47: return "resources unavailable";
            case 50: return "requested facility not subscribed";
            case 69: return "requested facility not implemented";
            case 81: return "invalid short message transfer reference value";
            case 95: return "semantically incorrect message";
            case 96: return "invalid mandatory information";
            case 97: return "message type non-existent";
            case 98: return "message not compatible with protocol state";
            case 99: return "information element non-existent";
            case 111: return "protocol error";
            case 127: return "interworking";
            case -1: return "missing";
            default: return "unspecified";
        }
    }

    private static String semiOctets(byte[] b, int from, int n) {
        StringBuilder sb = new StringBuilder(2 * n);
        for (int k = 0; k < n; k++) {
            int v = b[from + k] & 0xFF;
            sb.append(Character.forDigit(v & 0x0F, 16)).append(Character.forDigit(v >> 4, 16));
        }
        return sb.toString();
    }

    /** TP-SCTS time zone: quarter hours in swapped BCD, bit 3 of the first nibble the sign. */
    private static String timeZone(int v) {
        int quarters = (v & 0x07) * 10 + (v >> 4);
        return ((v & 0x08) != 0 ? "-" : "+") + quarters * 15 + "min";
    }

    private static String twoDigits(int v) {
        return String.format("%02X", v);
    }

    private static String octets(byte[] b) {
        StringBuilder sb = new StringBuilder(2 * b.length);
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }
}
