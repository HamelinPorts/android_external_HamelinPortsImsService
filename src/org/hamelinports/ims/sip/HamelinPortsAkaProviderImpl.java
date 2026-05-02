package org.hamelinports.ims.sip;

import android.content.Context;
import android.util.Log;

import org.hamelinports.ims.auth.AkaHelper;
import org.hamelinports.ims.net.EspRoutingFix;
import org.hamelinports.ims.net.IpsecHelper;

import java.net.InetAddress;

/**
 * AkaProvider implementation that bridges between the native
 * HamelinPortsImsAuthManager and Android's existing AKA + IPsec helpers.
 *
 * Per call cycle (one onAuthChallenge per REGISTER cycle):
 *   1. Run USIM AKA via AkaHelper.runUsimAka — returns RES, CK, IK
 *   2. Install the four TS 33.203 §7.4 IPsec SAs into the kernel
 *      via IpsecHelper.setupTransportMode (keyed by IK)
 *   3. Install the four port-pair kernel xfrm policies via
 *      EspRoutingFix.addXfrmPolicy (referencing the SAs by SPI)
 *   4. Return AkaResult{res, ck, ik} — native uses RES for digest,
 *      CK/IK aren't used downstream but ride home for symmetry.
 *
 * The IpsecResult is held alive for the lifetime of the provider
 * (== lifetime of the registration cycle) so the kernel SAs stay
 * installed. close() releases them on stop().
 */
public final class HamelinPortsAkaProviderImpl
        implements AkaProvider, AutoCloseable {

    private static final String TAG = "HamelinPortsIms";

    private final Context  mContext;
    private final int      mSlotId;
    private final SipClient.UeSecurityParams mUeSec;
    private final InetAddress mPcscf;
    private final InetAddress mLocalAddr;
    private final String   mLocalIp;
    private IpsecHelper.IpsecResult mIpsec;

    public HamelinPortsAkaProviderImpl(Context ctx,
                                  int slotId,
                                  SipClient.UeSecurityParams ueSec,
                                  InetAddress pcscf,
                                  InetAddress localAddr,
                                  String localIp) {
        mContext   = ctx;
        mSlotId    = slotId;
        mUeSec     = ueSec;
        mPcscf     = pcscf;
        mLocalAddr = localAddr;
        mLocalIp   = localIp;
    }

    @Override
    public AkaResult onAuthChallenge(byte[] rand, byte[] autn,
                                     long serverSpiC, long serverSpiS,
                                     int  serverPortC, int serverPortS,
                                     String alg) {
        AkaHelper.Challenge ch = new AkaHelper.Challenge();
        ch.rand  = rand;
        ch.autn  = autn;
        ch.spiC  = (int) serverSpiC;
        ch.spiS  = (int) serverSpiS;
        ch.portC = serverPortC;
        ch.portS = serverPortS;
        ch.alg   = alg;

        AkaHelper.AkaResult aka = AkaHelper.runUsimAka(mContext, ch);
        if (aka == null) {
            Log.e(TAG, "AKA: USIM rejected challenge or returned AUTS");
            return null;
        }

        try {
            // Install kernel SAs (these hold the keys; lifetime tied to
            // the IpsecResult). Must stay alive across the entire
            // registration session — close() releases them on stop().
            mIpsec = IpsecHelper.setupTransportMode(
                    mContext, mLocalAddr, mPcscf,
                    (int) serverSpiS, (int) serverSpiC,
                    mUeSec.spiC, mUeSec.spiS, aka.ik);

            // Install kernel-wide port-pair xfrm policies (selectors
            // that reference the SAs by SPI — this is what makes the
            // kernel ESP-wrap reSIProcate's plain TCP traffic).
            String pcscfStr = stripScope(mPcscf.getHostAddress());
            EspRoutingFix.addRule(mLocalIp);
            EspRoutingFix.addXfrmPolicy(mSlotId, mLocalIp, pcscfStr,
                    mUeSec.portC, mUeSec.portS,
                    serverPortC, serverPortS,
                    (int) serverSpiC, (int) serverSpiS);

            return new AkaResult(aka.res, aka.ck, aka.ik);
        } catch (Exception e) {
            Log.e(TAG, "IPsec setup failed", e);
            return null;
        }
    }

    @Override
    public void close() {
        if (mIpsec != null) {
            try { mIpsec.close(); } catch (Exception ignored) {}
            mIpsec = null;
        }
        if (mLocalIp != null) {
            EspRoutingFix.removeRule(mLocalIp);
        }
        /* Tear down the four kernel xfrm policies installed at AKA time.
         * Without this, the per-cycle random (uePortC, uePortS) selectors
         * accumulate one stale quartet per REGISTER cycle (next cycle's
         * del-then-add only matches its own ports). */
        EspRoutingFix.removeXfrmPolicy(mSlotId);
    }

    private static String stripScope(String ip) {
        int i = ip.indexOf('%');
        return i > 0 ? ip.substring(0, i) : ip;
    }
}
