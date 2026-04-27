package org.hamelinports.ims.auth;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;

import org.hamelinports.ims.sip.SipClient;

import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2: parse a 401 WWW-Authenticate AKAv1-MD5 challenge, run the AKA
 * procedure against the USIM via TelephonyManager.getIccAuthentication,
 * then compute the RFC 3310 / RFC 2617 Digest response.
 */
public final class AkaHelper {
    private static final String TAG = "HamelinPortsIms";

    public static class Challenge {
        public String realm;
        public String nonce;
        public byte[] rand;
        public byte[] autn;
        // Security-Server params
        public int spiC, spiS, portC, portS;
        public String alg;
    }

    public static class AkaResult {
        public byte[] res;
        public byte[] ck;
        public byte[] ik;
    }

    /** Extract nonce, realm, Security-Server from the 401 response text. */
    public static Challenge parseChallenge(String sipResponse) {
        Challenge c = new Challenge();

        Matcher m = Pattern.compile("nonce=\"([^\"]+)\"").matcher(sipResponse);
        if (!m.find()) { Log.e(TAG, "no nonce in 401"); return null; }
        c.nonce = m.group(1);

        m = Pattern.compile("realm=\"([^\"]+)\"").matcher(sipResponse);
        if (!m.find()) { Log.e(TAG, "no realm in 401"); return null; }
        c.realm = m.group(1);

        byte[] nonceBytes = Base64.decode(c.nonce, Base64.DEFAULT);
        Log.i(TAG, "nonce decoded: " + nonceBytes.length + " bytes");
        if (nonceBytes.length < 32) { Log.e(TAG, "nonce too short"); return null; }
        c.rand = new byte[16];
        c.autn = new byte[16];
        System.arraycopy(nonceBytes, 0, c.rand, 0, 16);
        System.arraycopy(nonceBytes, 16, c.autn, 0, 16);
        Log.i(TAG, "RAND=" + hex(c.rand) + " AUTN=" + hex(c.autn));

        m = Pattern.compile("Security-Server:.*spi-c=(\\d+)").matcher(sipResponse);
        if (m.find()) c.spiC = Integer.parseInt(m.group(1));
        m = Pattern.compile("Security-Server:.*spi-s=(\\d+)").matcher(sipResponse);
        if (m.find()) c.spiS = Integer.parseInt(m.group(1));
        m = Pattern.compile("Security-Server:.*port-c=(\\d+)").matcher(sipResponse);
        if (m.find()) c.portC = Integer.parseInt(m.group(1));
        m = Pattern.compile("Security-Server:.*port-s=(\\d+)").matcher(sipResponse);
        if (m.find()) c.portS = Integer.parseInt(m.group(1));
        m = Pattern.compile("Security-Server:.*alg=([\\w-]+)").matcher(sipResponse);
        if (m.find()) c.alg = m.group(1);
        Log.i(TAG, "Security-Server: spiC=" + c.spiC + " spiS=" + c.spiS
                + " portC=" + c.portC + " portS=" + c.portS + " alg=" + c.alg);
        return c;
    }

    /**
     * Run AKA against USIM: send RAND||AUTN, get back RES||CK||IK.
     * Uses only the public TelephonyManager API.
     */
    public static AkaResult runUsimAka(Context ctx, Challenge challenge) {
        TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
        int subId = SubscriptionManager.getDefaultSubscriptionId();
        TelephonyManager stm = tm.createForSubscriptionId(subId);

        // Build the AUTHENTICATE data: len(RAND) || RAND || len(AUTN) || AUTN
        byte[] authData = new byte[34];
        authData[0] = 0x10;
        System.arraycopy(challenge.rand, 0, authData, 1, 16);
        authData[17] = 0x10;
        System.arraycopy(challenge.autn, 0, authData, 18, 16);
        String authB64 = Base64.encodeToString(authData, Base64.NO_WRAP);

        Log.i(TAG, "calling getIccAuthentication USIM EAP_AKA data=" + authB64);
        String result;
        try {
            result = stm.getIccAuthentication(
                    TelephonyManager.APPTYPE_USIM,
                    TelephonyManager.AUTHTYPE_EAP_AKA,
                    authB64);
        } catch (SecurityException e) {
            Log.e(TAG, "getIccAuthentication denied — missing READ_PRIVILEGED_PHONE_STATE?", e);
            return null;
        }

        if (result == null) {
            Log.e(TAG, "getIccAuthentication returned null (USIM rejected challenge?)");
            return null;
        }

        byte[] resp = Base64.decode(result, Base64.DEFAULT);
        Log.i(TAG, "USIM response: " + resp.length + " bytes, tag=0x"
                + String.format("%02x", resp[0]));

        if (resp[0] == (byte) 0xDB) {
            // Success: DB len RES lenCK CK lenIK IK [lenKc Kc]
            return parseSuccessResponse(resp);
        } else if (resp[0] == (byte) 0xDC) {
            // Sync failure: DC len AUTS
            Log.e(TAG, "USIM returned AUTS (sync failure) — needs resync");
            return null;
        } else {
            Log.e(TAG, "unexpected USIM response tag: 0x" + String.format("%02x", resp[0]));
            return null;
        }
    }

    private static AkaResult parseSuccessResponse(byte[] resp) {
        // DB [lenRES] [RES...] [lenCK] [CK(16)] [lenIK] [IK(16)] [lenKc] [Kc(8)]
        int idx = 1;
        int resLen = resp[idx++] & 0xFF;
        byte[] res = new byte[resLen];
        System.arraycopy(resp, idx, res, 0, resLen);
        idx += resLen;

        int ckLen = resp[idx++] & 0xFF;
        byte[] ck = new byte[ckLen];
        System.arraycopy(resp, idx, ck, 0, ckLen);
        idx += ckLen;

        int ikLen = resp[idx++] & 0xFF;
        byte[] ik = new byte[ikLen];
        System.arraycopy(resp, idx, ik, 0, ikLen);

        Log.i(TAG, "AKA success: RES(" + resLen + ")=" + hex(res)
                + " CK(" + ckLen + ")=" + hex(ck)
                + " IK(" + ikLen + ")=" + hex(ik));

        AkaResult r = new AkaResult();
        r.res = res;
        r.ck = ck;
        r.ik = ik;
        return r;
    }

    /**
     * Compute the RFC 3310 AKAv1-MD5 digest response.
     *
     * For AKAv1-MD5 without qop:
     *   password = base64(RES)  (some impls use hex — we try base64 first)
     *   HA1 = MD5(username ":" realm ":" password)
     *   HA2 = MD5("REGISTER" ":" uri)
     *   response = MD5(HA1 ":" nonce ":" HA2)
     */
    public static String computeDigestResponse(SipClient.Credentials creds,
                                         Challenge challenge, AkaResult aka) {
        try {
            String username = creds.imsi + "@" + creds.domain;
            String uri = "sip:" + creds.domain;

            // RFC 3310 §3: AKAv1 password = raw RES bytes.
            // HA1 = MD5(username ":" realm ":" RES_raw_bytes)
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(username.getBytes("UTF-8"));
            md5.update((byte) ':');
            md5.update(challenge.realm.getBytes("UTF-8"));
            md5.update((byte) ':');
            md5.update(aka.res);
            String ha1 = hex(md5.digest());

            md5.reset();
            String ha2 = hex(md5.digest(("REGISTER:" + uri).getBytes("UTF-8")));

            md5.reset();
            String response = hex(md5.digest((ha1 + ":" + challenge.nonce + ":" + ha2).getBytes("UTF-8")));
            Log.i(TAG, "Digest HA1=" + ha1 + " HA2=" + ha2 + " response=" + response);
            return response;
        } catch (Exception e) {
            Log.e(TAG, "digest computation failed", e);
            return null;
        }
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x", v & 0xFF));
        return sb.toString();
    }

    private AkaHelper() {}
}
