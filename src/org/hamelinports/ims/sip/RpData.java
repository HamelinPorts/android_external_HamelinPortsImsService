package org.hamelinports.ims.sip;

import android.telephony.PhoneNumberUtils;

import java.io.ByteArrayOutputStream;

/**
 * RP-DATA PDU encoder/decoder per 3GPP TS 24.011 §7.3.1.
 * Wraps/unwraps SMS TPDUs for SIP MESSAGE with Content-Type application/vnd.3gpp.sms.
 */
public final class RpData {

    /** Wrap a TPDU in MO RP-DATA for sending to SMSC. */
    public static byte[] wrapMo(int messageRef, String smscNumber, byte[] tpdu) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // RP-MTI = 0x00 (RP-DATA from MS to network — 3GPP TS 24.011 §7.3.1)
        out.write(0x00);
        // RP-MR
        out.write(messageRef & 0xFF);
        // RP-OA: empty (length = 0)
        out.write(0x00);
        // RP-DA: SMSC address as called-party BCD (3GPP TS 24.008 §10.5.4.7)
        byte[] smscBcd = PhoneNumberUtils.numberToCalledPartyBCD(
                smscNumber, PhoneNumberUtils.BCD_EXTENDED_TYPE_CALLED_PARTY);
        if (smscBcd != null && smscBcd.length > 0) {
            out.write(smscBcd.length);
            out.write(smscBcd, 0, smscBcd.length);
        } else {
            out.write(0x00);
        }
        // RP-UD: TPDU
        out.write(tpdu.length);
        out.write(tpdu, 0, tpdu.length);
        return out.toByteArray();
    }

    /** RP-MTI values (3GPP TS 24.011 §8.2.2). */
    public static final int RP_MTI_DATA_MS_TO_N = 0;
    public static final int RP_MTI_DATA_N_TO_MS = 1;
    public static final int RP_MTI_ACK_MS_TO_N  = 2;
    public static final int RP_MTI_ACK_N_TO_MS  = 3;
    public static final int RP_MTI_ERROR_MS_TO_N = 4;
    public static final int RP_MTI_ERROR_N_TO_MS = 5;

    /** Result of parsing a received RP-PDU on the MT path. */
    public static final class Parsed {
        public final int rpMti;     // one of RP_MTI_*
        public final int rpMr;      // RP Message Reference (matches our MO's RP-MR)
        public final byte[] tpdu;   // TP-UD payload (may be null)
        public final int rpCause;   // RP-Cause from RP-ERROR (TS 24.011 §8.2.5.4); -1 otherwise
        Parsed(int rpMti, int rpMr, byte[] tpdu, int rpCause) {
            this.rpMti = rpMti; this.rpMr = rpMr; this.tpdu = tpdu; this.rpCause = rpCause;
        }
    }

    /**
     * Parse an RP-PDU received on the MT path. Handles both RP-DATA (n→ms,
     * MTI=1) which carries the SMS-DELIVER TPDU, and RP-ACK (n→ms, MTI=3)
     * which carries an optional SMS-SUBMIT-REPORT or SMS-STATUS-REPORT TPDU
     * as the RP-User-Data element (IEI 0x41) acknowledging our prior MO.
     *
     * Returns null on malformed input. The caller should inspect .rpMti to
     * decide whether to deliver .tpdu to the framework:
     *   RP-DATA  → deliver as MT SMS (framework parses TP-MTI = DELIVER)
     *   RP-ACK   → do NOT deliver; treat as our MO's completion notification
     */
    public static Parsed parseMt(byte[] rpData) {
        if (rpData == null || rpData.length < 2) return null;
        int mti = rpData[0] & 0x07;
        int mr  = rpData[1] & 0xFF;
        int idx = 2;
        switch (mti) {
            case RP_MTI_DATA_N_TO_MS: {
                // Layout: MTI, MR, RP-OA (L-V), RP-DA (L-V), RP-UD (L-V of TPDU)
                if (idx >= rpData.length) return null;
                int oaLen = rpData[idx++] & 0xFF;
                idx += oaLen;
                if (idx >= rpData.length) return null;
                int daLen = rpData[idx++] & 0xFF;
                idx += daLen;
                if (idx >= rpData.length) return null;
                int udLen = rpData[idx++] & 0xFF;
                if (idx + udLen > rpData.length) udLen = rpData.length - idx;
                byte[] tpdu = new byte[udLen];
                System.arraycopy(rpData, idx, tpdu, 0, udLen);
                return new Parsed(mti, mr, tpdu, -1);
            }
            case RP_MTI_ACK_N_TO_MS: {
                // Layout: MTI, MR, [optional RP-User-Data IE: tag 0x41, len, TPDU].
                // The inner TPDU (if present) is an SMS-SUBMIT-REPORT-FOR-RP-ACK
                // acknowledging our MO. Expose it for debug but the caller
                // normally just treats RP-ACK as "SMSC accepted our SUBMIT".
                byte[] tpdu = null;
                if (idx + 2 <= rpData.length && (rpData[idx] & 0xFF) == 0x41) {
                    int tpduLen = rpData[idx + 1] & 0xFF;
                    int start = idx + 2;
                    if (start + tpduLen <= rpData.length) {
                        tpdu = new byte[tpduLen];
                        System.arraycopy(rpData, start, tpdu, 0, tpduLen);
                    }
                }
                return new Parsed(mti, mr, tpdu, -1);
            }
            case RP_MTI_ERROR_N_TO_MS: {
                // Layout: MTI, MR, RP-Cause (L-V), [optional RP-User-Data IE].
                // RP-Cause values per TS 24.011 §8.2.5.4 (e.g. 22 = memory cap,
                // 27 = destination out of order, 28 = unidentified subscriber).
                if (idx >= rpData.length) return new Parsed(mti, mr, null, -1);
                int causeLen = rpData[idx++] & 0xFF;
                int cause = -1;
                if (causeLen > 0 && idx < rpData.length) {
                    cause = rpData[idx] & 0x7F; // cause value, strip ext bit
                }
                return new Parsed(mti, mr, null, cause);
            }
            default:
                return new Parsed(mti, mr, null, -1);
        }
    }

    /** Back-compat wrapper: returns the DELIVER TPDU for RP-DATA, null
     *  otherwise. Callers should prefer {@link #parseMt} so they can
     *  differentiate RP-DATA from RP-ACK. */
    public static byte[] unwrapMt(byte[] rpData) {
        Parsed p = parseMt(rpData);
        return (p != null && p.rpMti == RP_MTI_DATA_N_TO_MS) ? p.tpdu : null;
    }

    private static void writeAddress(ByteArrayOutputStream out, String number) {
        // Strip + prefix
        boolean international = number.startsWith("+");
        String digits = international ? number.substring(1) : number;
        // BCD-encode digits (nibble-swapped pairs)
        byte[] bcd = stringToBcd(digits);
        // Length = 1 (TOA) + bcd.length
        out.write(1 + bcd.length);
        // TOA: 0x91 = international, 0x81 = unknown
        out.write(international ? 0x91 : 0x81);
        out.write(bcd, 0, bcd.length);
    }

    private static byte[] stringToBcd(String number) {
        // Pad to even length
        if (number.length() % 2 != 0) number += "F";
        byte[] result = new byte[number.length() / 2];
        for (int i = 0; i < number.length(); i += 2) {
            int low = Character.digit(number.charAt(i), 16);
            int high = Character.digit(number.charAt(i + 1), 16);
            result[i / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private RpData() {}
}
