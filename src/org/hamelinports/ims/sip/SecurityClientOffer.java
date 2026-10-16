package org.hamelinports.ims.sip;

import java.util.Random;

/**
 * The UE's IPsec offer in Security-Client (TS 33.203 §7.2, RFC 3329): its
 * SPIs and ports for the two SA pairs. Immutable.
 *
 * <p>Pure Java, so the host tests drive it directly.
 */
public final class SecurityClientOffer {
    static final int SPI_MIN = 10000;
    static final int SPI_RANGE = 50000;
    static final int PORT_BASE = 7100;
    static final int PORT_PAIRS = 900;

    public final int spiC;
    public final int spiS;
    public final int portC;
    public final int portS;

    SecurityClientOffer(int spiC, int spiS, int portC, int portS) {
        this.spiC = spiC;
        this.spiS = spiS;
        this.portC = portC;
        this.portS = portS;
    }

    /** The offer of an initial registration: spi-s = spi-c + 1, port-s = port-c - 1. */
    public static SecurityClientOffer initial(Random random) {
        int spiC = SPI_MIN + random.nextInt(SPI_RANGE);
        int portC = PORT_BASE + random.nextInt(PORT_PAIRS) * 2 + 1;
        return new SecurityClientOffer(spiC, spiC + 1, portC, portC - 1);
    }

    /**
     * The offer of a re-registration over this offer's SAs (TS 33.203 §7.4):
     * new SPIs that collide with neither of this offer's, a new port-c and the
     * same port-s.
     */
    public SecurityClientOffer forReRegistration(Random random) {
        int newSpiC;
        do {
            newSpiC = SPI_MIN + random.nextInt(SPI_RANGE);
        } while (usesSpi(newSpiC) || usesSpi(newSpiC + 1));
        int newPortC;
        do {
            newPortC = PORT_BASE + random.nextInt(PORT_PAIRS) * 2 + 1;
        } while (newPortC == portC);
        return new SecurityClientOffer(newSpiC, newSpiC + 1, newPortC, portS);
    }

    private boolean usesSpi(int spi) {
        return spi == spiC || spi == spiS;
    }

    /**
     * The Security-Client header value. Both algorithms are listed: the
     * P-CSCF rejects an offer without hmac-md5-96 with 403.
     */
    public String headerValue() {
        String common = "ipsec-3gpp;prot=esp;mod=trans"
                + ";spi-c=" + spiC
                + ";spi-s=" + spiS
                + ";port-c=" + portC
                + ";port-s=" + portS;
        return common + ";alg=hmac-md5-96;ealg=null, "
                + common + ";alg=hmac-sha-1-96;ealg=null";
    }
}
