package org.hamelinports.ims.call;

/** SIP response status classes (RFC 3261 §21). Pure Java. */
public final class SipStatus {
    private SipStatus() {}

    /** A final failure response (3xx-6xx): the only codes an onFailure upcall may carry. */
    public static boolean isFailure(int status) {
        return status >= 300 && status <= 699;
    }
}
