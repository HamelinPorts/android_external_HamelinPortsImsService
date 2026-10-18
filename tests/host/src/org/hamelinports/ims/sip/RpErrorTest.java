package org.hamelinports.ims.sip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RpErrorTest {
    /** TP-SCTS 2026-10-18 19:39:07 +02:00 in swapped semi-octets. */
    private static final int[] SCTS = {0x62, 0x01, 0x81, 0x91, 0x93, 0x70, 0x80};

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static int[] concat(int[]... parts) {
        int n = 0;
        for (int[] p : parts) n += p.length;
        int[] out = new int[n];
        int i = 0;
        for (int[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }

    /** RP-ERROR, RP-MR 0, cause 10 + diagnostic 0x2A, SUBMIT-REPORT (TP-FCS 0xC0, no options): 17 octets. */
    private static byte[] observedShape() {
        return bytes(concat(new int[] {0x05, 0x00, 0x02, 0x0A, 0x2A, 0x41, 0x0A, 0x01, 0xC0, 0x00},
                SCTS));
    }

    @Test
    public void decodesTheSeventeenOctetRpError() {
        byte[] pdu = observedShape();
        assertEquals(17, pdu.length);
        RpError e = RpError.parse(pdu);
        assertNotNull(e);
        assertEquals(0, e.rpMr);
        assertEquals(10, e.cause);
        assertEquals(0x2A, e.diagnostic);
        assertTrue(e.hasReport);
        assertEquals(0xC0, e.failureCause);
        assertEquals(0x00, e.parameterIndicator);
        assertEquals("261018193907+120min", e.serviceCentreTime);
        assertEquals(-1, e.userDataLength);
        assertEquals("RP-MR=0 cause=10 (call barred) diagnostic=2A SUBMIT-REPORT TP-FCS=0xC0"
                + " TP-PI=0x00 TP-SCTS=261018193907+120min TP-UDL=none"
                + " octets=0500020A2A410A01C00062018191937080", e.describe());
    }

    @Test
    public void causeWithoutDiagnosticAndAnOptionalField() {
        /* cause length 1; TP-PI announces TP-PID, TP-DCS and TP-UDL (3 user-data octets). */
        byte[] pdu = bytes(concat(new int[] {0x05, 0x07, 0x01, 0x08, 0x41, 0x10, 0x01, 0x99, 0x07},
                SCTS, new int[] {0x00, 0x00, 0x03, 0xAA, 0xBB, 0xCC}));
        RpError e = RpError.parse(pdu);
        assertEquals(7, e.rpMr);
        assertEquals(8, e.cause);
        assertEquals(-1, e.diagnostic);
        assertTrue(e.hasReport);
        assertEquals(0x99, e.failureCause);
        assertEquals(3, e.userDataLength);
        assertTrue(e.describe().contains("diagnostic=none"));
    }

    @Test
    public void extensionBitIsStripped() {
        RpError e = RpError.parse(bytes(0x05, 0x01, 0x01, 0x8A));
        assertEquals(10, e.cause);
        assertFalse(e.hasReport);
        assertTrue(e.describe().contains("no SUBMIT-REPORT"));
    }

    @Test
    public void missingOrEmptyCause() {
        assertEquals(-1, RpError.parse(bytes(0x05, 0x00)).cause);
        assertEquals(-1, RpError.parse(bytes(0x05, 0x00, 0x00)).cause);
        assertEquals("an empty cause element is not followed into the next octet",
                -1, RpError.parse(bytes(0x05, 0x00, 0x00, 0x0A)).cause);
        assertEquals("length beyond the PDU", -1, RpError.parse(bytes(0x05, 0x00, 0x01)).cause);
        assertEquals(10, RpError.parse(bytes(0x05, 0x00, 0x02, 0x0A)).cause);
        assertEquals(-1, RpError.parse(bytes(0x05, 0x00, 0x02, 0x0A)).diagnostic);
    }

    @Test
    public void truncatedOrForeignReportIsNotDecoded() {
        assertFalse("SCTS cut short",
                RpError.parse(bytes(0x05, 0x00, 0x01, 0x0A, 0x41, 0x0A, 0x01, 0xC0, 0x00, 0x62))
                        .hasReport);
        assertFalse("TP-MTI DELIVER, not SUBMIT-REPORT", RpError.parse(bytes(concat(
                new int[] {0x05, 0x00, 0x01, 0x0A, 0x41, 0x0A, 0x00, 0xC0, 0x00}, SCTS))).hasReport);
        assertFalse("not the RP-User-Data IEI", RpError.parse(bytes(concat(
                new int[] {0x05, 0x00, 0x01, 0x0A, 0x42, 0x0A, 0x01, 0xC0, 0x00}, SCTS))).hasReport);
        assertEquals(10, RpError.parse(bytes(0x05, 0x00, 0x01, 0x0A, 0x41)).cause);
    }

    @Test
    public void onlyAnRpErrorIsAccepted() {
        assertNull(RpError.parse(null));
        assertNull(RpError.parse(bytes(0x05)));
        assertNull("RP-ACK", RpError.parse(bytes(0x03, 0x00)));
        assertNull("RP-DATA n->ms", RpError.parse(bytes(0x01, 0x00, 0x00, 0x00, 0x00)));
        assertNull("RP-DATA ms->n", RpError.parse(bytes(0x00, 0x00, 0x00, 0x00, 0x00)));
        assertNull("RP-ERROR ms->n", RpError.parse(bytes(0x04, 0x00, 0x01, 0x0A)));
    }

    /** The logged octets of an RP-ERROR cannot hold a phone number: it has no address element. */
    @Test
    public void loggedOctetsHoldNoPhoneNumber() {
        String number = "12025550123";      // fictional (NANP 555-01xx)
        String swapped = "2120550521F3";    // the number in semi-octets, as RP-DA / TP-DA carry it
        /* The MO RP-DATA it answers carries the number (RP-DA here, TP-DA in a TPDU). */
        byte[] moRpData = bytes(0x00, 0x00, 0x00, 0x07, 0x91, 0x21, 0x20, 0x55, 0x05, 0x21, 0xF3,
                0x05, 0x01, 0x00, 0x0B, 0x91, 0x21);
        assertNull("an RP-DATA is never decoded (or logged) as an RP-ERROR",
                RpError.parse(moRpData));

        String line = RpError.parse(observedShape()).describe();
        assertFalse(line.contains(number));
        assertFalse(line.contains(swapped));
        assertFalse(line.contains(swapped.substring(0, 8)));
    }

    @Test
    public void causeNames() {
        assertEquals("call barred", RpError.causeName(10));
        assertEquals("operator determined barring", RpError.causeName(8));
        assertEquals("temporary failure", RpError.causeName(41));
        assertEquals("missing", RpError.causeName(-1));
        assertEquals("unspecified", RpError.causeName(2));
    }
}
