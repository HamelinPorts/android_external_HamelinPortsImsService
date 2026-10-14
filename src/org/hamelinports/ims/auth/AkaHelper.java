package org.hamelinports.ims.auth;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;

/**
 * Runs the IMS AKA procedure against the USIM via
 * TelephonyManager.getIccAuthentication.
 *
 * <p>Never log the challenge or the RES/CK/IK result: they authenticate the
 * subscriber and key the IPsec SAs.
 */
public final class AkaHelper {
    private static final String TAG = "HamelinPortsIms";

    public static class Challenge {
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

        Log.i(TAG, "calling getIccAuthentication USIM EAP_AKA");
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
            Log.e(TAG, "USIM reported a sequence-number sync failure — needs resync");
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

        Log.i(TAG, "AKA success: lengths " + resLen + "/" + ckLen + "/" + ikLen);

        AkaResult r = new AkaResult();
        r.res = res;
        r.ck = ck;
        r.ik = ik;
        return r;
    }

    private AkaHelper() {}
}
