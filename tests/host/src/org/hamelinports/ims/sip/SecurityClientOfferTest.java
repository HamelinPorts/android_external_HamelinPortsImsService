package org.hamelinports.ims.sip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Random;

@RunWith(JUnit4.class)
public class SecurityClientOfferTest {

    /** Returns the queued values from nextInt(bound), then falls back to a seeded Random. */
    private static final class ScriptedRandom extends Random {
        private final Deque<Integer> mValues = new ArrayDeque<>();

        ScriptedRandom(Integer... values) {
            super(7);
            mValues.addAll(Arrays.asList(values));
        }

        @Override
        public int nextInt(int bound) {
            Integer v = mValues.poll();
            return v != null ? v : super.nextInt(bound);
        }
    }

    private static void assertInRange(SecurityClientOffer o) {
        assertTrue("spi-c " + o.spiC, o.spiC >= 10000 && o.spiC < 60000);
        assertEquals(o.spiC + 1, o.spiS);
        assertTrue("port-c " + o.portC, o.portC >= 7101 && o.portC <= 8899);
        assertEquals("port-c odd", 1, o.portC % 2);
    }

    @Test
    public void headerValueIsTheRegistrationOffer() {
        SecurityClientOffer o = new SecurityClientOffer(12345, 12346, 7101, 7100);
        String common = "ipsec-3gpp;prot=esp;mod=trans;spi-c=12345;spi-s=12346;port-c=7101;port-s=7100";
        assertEquals(common + ";alg=hmac-md5-96;ealg=null, " + common + ";alg=hmac-sha-1-96;ealg=null",
                o.headerValue());
    }

    @Test
    public void initialOfferPairsSpisAndPorts() {
        Random r = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            SecurityClientOffer o = SecurityClientOffer.initial(r);
            assertInRange(o);
            assertEquals(o.portC - 1, o.portS);
        }
        SecurityClientOffer edge = SecurityClientOffer.initial(new ScriptedRandom(0, 0));
        assertEquals(10000, edge.spiC);
        assertEquals(7101, edge.portC);
        assertEquals(7100, edge.portS);
        SecurityClientOffer top = SecurityClientOffer.initial(new ScriptedRandom(49999, 899));
        assertEquals(59999, top.spiC);
        assertEquals(8899, top.portC);
    }

    @Test
    public void reRegistrationOffersNewSpisAndPortCButKeepsPortS() {
        Random r = new Random(2);
        for (int i = 0; i < 10_000; i++) {
            SecurityClientOffer current = SecurityClientOffer.initial(r);
            SecurityClientOffer next = current.forReRegistration(r);
            assertInRange(next);
            assertEquals(current.portS, next.portS);
            assertNotEquals(current.portC, next.portC);
            assertNotEquals(current.portS, next.portC);
            for (int used : new int[] {current.spiC, current.spiS}) {
                assertNotEquals(used, next.spiC);
                assertNotEquals(used, next.spiS);
            }
        }
    }

    @Test
    public void reRegistrationSkipsCollidingDraws() {
        SecurityClientOffer current = new SecurityClientOffer(20000, 20001, 7301, 7300);
        /* SPI draws: 20000 (same spi-c), 19999 (its spi-s = our spi-c),
         * 20001 (our spi-s), then 30000; port draws: 100 (port 7301, ours), then 5. */
        SecurityClientOffer next = current.forReRegistration(
                new ScriptedRandom(10000, 9999, 10001, 20000, 100, 5));
        assertEquals(30000, next.spiC);
        assertEquals(30001, next.spiS);
        assertEquals(7111, next.portC);
        assertEquals(7300, next.portS);
    }

    @Test
    public void reRegistrationHeaderCarriesTheNewValues() {
        SecurityClientOffer current = new SecurityClientOffer(20000, 20001, 7301, 7300);
        SecurityClientOffer next = current.forReRegistration(new ScriptedRandom(30000, 5));
        String header = next.headerValue();
        assertTrue(header, header.contains(";spi-c=40000;spi-s=40001;port-c=7111;port-s=7300;"));
        assertFalse(header, header.contains("spi-c=20000"));
        assertFalse(header, header.contains("port-c=7301"));
        assertEquals(current.headerValue().length(), header.length());
    }

    @Test
    public void reRegistrationOfAReRegistrationStillKeepsPortS() {
        Random r = new Random(3);
        SecurityClientOffer first = SecurityClientOffer.initial(r);
        SecurityClientOffer offer = first;
        for (int i = 0; i < 100; i++) {
            offer = offer.forReRegistration(r);
            assertEquals(first.portS, offer.portS);
        }
    }
}
