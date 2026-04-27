package org.hamelinports.ims.sip;

/**
 * Java side of the AKA bridge. The native HamelinPortsImsAuthManager calls
 * {@link #onAuthChallenge} synchronously from the reSIProcate stack
 * thread when a 401 with AKAv1-MD5 arrives.
 *
 * Implementations must:
 *   1. Run USIM AKA via TelephonyManager.getIccAuthentication(USIM,
 *      EAP_AKA, base64(0x10||rand||0x10||autn)) and parse the
 *      response (RES, CK, IK).
 *   2. Install the four TS 33.203 §7.4 kernel xfrm SAs using the
 *      negotiated SPIs/ports + the derived CK/IK BEFORE returning —
 *      the next outbound REGISTER from native goes onto the
 *      IPsec-protected port pair.
 *
 * Thread-safe: invoked off the SIP stack thread, may overlap with
 * application callbacks on the main thread. Implementations must NOT
 * call back into {@link HamelinPortsSipStack} methods that need the bridge
 * mutex; that would deadlock the stack thread.
 */
public interface AkaProvider {

    /**
     * @param rand        16-byte RAND (bytes 0–15 of the base64 nonce)
     * @param autn        16-byte AUTN (bytes 16–31 of the base64 nonce)
     * @param serverSpiC  P-CSCF's chosen SPI for client-direction SAs
     * @param serverSpiS  P-CSCF's chosen SPI for server-direction SAs
     * @param serverPortC P-CSCF's port-c (used by SA3 outbound from UE)
     * @param serverPortS P-CSCF's port-s (used by SA1 outbound from UE)
     * @param alg         negotiated MAC algorithm, e.g. "hmac-sha-1-96"
     * @return AkaResult with RES/CK/IK on success, with AUTS on
     *         resync (RFC 3310 §3.2 — not yet acted on by native),
     *         or null on hard failure
     */
    AkaResult onAuthChallenge(byte[] rand, byte[] autn,
                              long serverSpiC, long serverSpiS,
                              int  serverPortC, int  serverPortS,
                              String alg);
}
