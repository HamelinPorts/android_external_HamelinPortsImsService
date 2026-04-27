package org.hamelinports.ims.sip;

/**
 * Result of a single 3GPP TS 33.203 AKA challenge round.
 *
 * On success: {@link #res}, {@link #ck}, {@link #ik} are populated and
 * {@link #auts} is null. The native digest engine uses {@link #res}
 * as the SIP digest password; the Java side keeps {@link #ck} and
 * {@link #ik} for the next IPsec rekey via {@code ims_xfrm}.
 *
 * On synchronisation failure (USIM AUTN check rejected the AUTN as
 * out-of-window): {@link #auts} is populated, the others are null.
 * The native side will currently treat that as failure; future work
 * surfaces AUTS back into the next REGISTER as
 * {@code Authorization: ... auts=base64(auts)} per RFC 3310 §3.2.
 */
public final class AkaResult {

    /** AKA RES — digest password. Length depends on USIM (typically 8B). */
    public final byte[] res;

    /** AKA CK — IPsec confidentiality key (16B). */
    public final byte[] ck;

    /** AKA IK — IPsec integrity key (16B). */
    public final byte[] ik;

    /** AKA AUTS — present iff USIM rejected AUTN (resync needed). */
    public final byte[] auts;

    /** Successful round constructor. */
    public AkaResult(byte[] res, byte[] ck, byte[] ik) {
        this(res, ck, ik, null);
    }

    /** Resync constructor. */
    public static AkaResult resync(byte[] auts) {
        return new AkaResult(null, null, null, auts);
    }

    private AkaResult(byte[] res, byte[] ck, byte[] ik, byte[] auts) {
        this.res = res;
        this.ck = ck;
        this.ik = ik;
        this.auts = auts;
    }
}
