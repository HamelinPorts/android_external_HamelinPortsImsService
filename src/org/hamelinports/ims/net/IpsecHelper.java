package org.hamelinports.ims.net;

import android.content.Context;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.util.Log;

import org.hamelinports.ims.auth.AkaHelper;

import java.net.InetAddress;

public final class IpsecHelper {
    private static final String TAG = "HamelinPortsIms";

    public static class IpsecResult implements AutoCloseable {
        public IpSecTransform outTransform;   // SA1: UE:portC→PCSCF:portS, SPI=PCSCF spiS
        public IpSecTransform outTransformS;  // SA3: UE:portS→PCSCF:portC, SPI=PCSCF spiC
        public IpSecTransform inTransform;    // SA2: PCSCF:portS→UE:portC, SPI=UE spiC
        public IpSecTransform inTransformS;   // SA4: PCSCF:portC→UE:portS, SPI=UE spiS
        public IpSecManager.SecurityParameterIndex outSpi;
        public IpSecManager.SecurityParameterIndex outSpiS;
        public IpSecManager.SecurityParameterIndex inSpi;
        public IpSecManager.SecurityParameterIndex inSpiS;

        @Override
        public void close() {
            if (outTransform != null) outTransform.close();
            if (outTransformS != null) outTransformS.close();
            if (inTransform != null) inTransform.close();
            if (inTransformS != null) inTransformS.close();
            if (outSpi != null) outSpi.close();
            if (outSpiS != null) outSpiS.close();
            if (inSpi != null) inSpi.close();
            if (inSpiS != null) inSpiS.close();
        }
    }

    /**
     * Set up two unidirectional transport-mode ESP SAs:
     *   outbound (UE→P-CSCF): SPI = server's spi-s, key = IK
     *   inbound  (P-CSCF→UE): SPI = UE's spi-s, key = IK
     *
     * Per 3GPP TS 33.203 §7, integrity key for both SAs is IK (16 bytes).
     * Algorithm hmac-md5-96, null encryption.
     *
     * The UE binds to port-s (server port; we use 6100).
     * The P-CSCF sends both responses and new requests (NOTIFY) on the same
     * TCP connection to port-s, using UE's spi-s for the inbound SA.
     */
    public static IpsecResult setupTransportMode(
            Context ctx,
            InetAddress localAddr,
            InetAddress pcscfAddr,
            int serverSpiS,
            int serverSpiC,
            int ueSpiC,
            int ueSpiS,
            byte[] ik) throws Exception {

        IpSecManager ism = ctx.getSystemService(IpSecManager.class);
        IpsecResult result = new IpsecResult();

        Log.i(TAG, "IPsec setup: localAddr=" + localAddr.getHostAddress()
                + " pcscfAddr=" + pcscfAddr.getHostAddress()
                + " serverSpiS=" + serverSpiS
                + " ueSpiC=" + ueSpiC + " ueSpiS=" + ueSpiS
                + " IK=" + AkaHelper.hex(ik));

        IpSecAlgorithm auth = new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_MD5, ik, 96);

        // Outbound: UE sends to P-CSCF. P-CSCF allocated spi-s for receiving.
        result.outSpi = ism.allocateSecurityParameterIndex(pcscfAddr, serverSpiS);
        result.outTransform = new IpSecTransform.Builder(ctx)
                .setAuthentication(auth)
                .buildTransportModeTransform(localAddr, result.outSpi);
        Log.i(TAG, "IPsec outbound SA created: SPI=" + serverSpiS);

        // Inbound SA #1: P-CSCF responses on the client connection use spi-c.
        result.inSpi = ism.allocateSecurityParameterIndex(localAddr, ueSpiC);
        result.inTransform = new IpSecTransform.Builder(ctx)
                .setAuthentication(auth)
                .buildTransportModeTransform(pcscfAddr, result.inSpi);
        Log.i(TAG, "IPsec inbound SA #1 (spiC) created: SPI=" + ueSpiC);

        // Inbound SA #2: P-CSCF server-initiated requests (NOTIFY) use spi-s.
        // This SA exists in the kernel xfrm table and decrypts packets with spi-s.
        result.inSpiS = ism.allocateSecurityParameterIndex(localAddr, ueSpiS);
        result.inTransformS = new IpSecTransform.Builder(ctx)
                .setAuthentication(auth)
                .buildTransportModeTransform(pcscfAddr, result.inSpiS);
        Log.i(TAG, "IPsec inbound SA #2 (spiS) created: SPI=" + ueSpiS);

        // Outbound SA #2 (SA3): for server socket responses (SYN-ACK to NOTIFY).
        // UE:portS → PCSCF:portC, encrypted with P-CSCF's spiC.
        result.outSpiS = ism.allocateSecurityParameterIndex(pcscfAddr, serverSpiC);
        result.outTransformS = new IpSecTransform.Builder(ctx)
                .setAuthentication(auth)
                .buildTransportModeTransform(localAddr, result.outSpiS);
        Log.i(TAG, "IPsec outbound SA #2 (serverSpiC) created: SPI=" + serverSpiC);

        return result;
    }

    private IpsecHelper() {}
}
