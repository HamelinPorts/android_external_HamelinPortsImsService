package org.hamelinports.ims.sip;

/**
 * Java side of the native ClientPagerMessageHandler +
 * ServerPagerMessageHandler. Native fires these on the SIP stack
 * thread; implementations forward to the AOSP IMS framework
 * (ImsSmsImplBase).
 */
public interface SmsSessionListener {
    /** A 200 OK / 202 Accepted to our outbound SIP MESSAGE. */
    void onSendSuccess(int statusCode, String reason);
    /** A 4xx/5xx/6xx to our outbound SIP MESSAGE. */
    void onSendFailure(int statusCode, String reason);
    /** Inbound SIP MESSAGE body — typically RP-DATA wrapping a 3GPP TPDU. */
    void onIncomingSms(byte[] body);
}
