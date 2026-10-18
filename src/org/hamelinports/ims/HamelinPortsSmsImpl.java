package org.hamelinports.ims;

import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.telephony.CarrierConfigManager;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.util.Log;

import org.hamelinports.ims.reg.OutgoingGate;
import org.hamelinports.ims.sip.HamelinPortsSipStack;
import org.hamelinports.ims.sip.PendingMoSms;
import org.hamelinports.ims.sip.RpData;
import org.hamelinports.ims.sip.RpError;
import org.hamelinports.ims.sip.RpErrorPolicy;
import org.hamelinports.ims.sip.SipClient;
import org.hamelinports.ims.sip.SmsSessionListener;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * SMS-over-IMS via reSIProcate. MO sends use ClientPagerMessage; MT
 * arrivals come back through ServerPagerMessage. Both routes are
 * exposed to Java via {@link HamelinPortsSipStack}.
 *
 * <p>MO state machine (one outcome per token, see {@link PendingMoSms}):
 * <pre>
 *   sendSms()                    → native MESSAGE submitted; tracked as pending
 *   onSendSuccess (SIP 2xx)       → log only; wait for RP-ACK/RP-ERROR
 *   onSendFailure (SIP 4xx/5xx)   → framework error now
 *   onIncomingSms RP-ACK          → framework success
 *   onIncomingSms RP-ERROR        → framework error, retry over IMS or CS
 *                                   fallback per {@link RpErrorPolicy}
 *   pending timeout (30 s)        → optimistic framework success
 * </pre>
 *
 * <p>MT state machine:
 * <pre>
 *   onIncomingSms RP-DATA + TP-MTI=DELIVER        → onSmsReceived (new SMS)
 *   onIncomingSms RP-DATA + TP-MTI=STATUS-REPORT  → onSmsStatusReportReceived ("Delivered" ✓)
 * </pre>
 *
 * The SMS body is RP-DATA (3GPP TS 24.011) wrapping the framework's
 * TPDU. Carriage decisions (SMSC URI, content-type) match the existing
 * working capture on Telefonica DE.
 */
public class HamelinPortsSmsImpl extends ImsSmsImplBase implements SmsSessionListener {
    private static final String TAG = HamelinPortsImsService.TAG;

    private static final String CONTENT_TYPE = "application/vnd.3gpp.sms";

    /** SMSC for MO SMS over IMS (o2 Germany, the only carrier this
     *  device is used on). Not queried through
     *  {@link SmsManager#getSmscAddress()}: the RIL deliberately answers
     *  it empty (CS sends then use the modem's default SMSC), so it
     *  never yields a usable number. A per-MCC/MNC table is the
     *  long-term replacement. */
    private static final String SMSC_NUMBER = "+491770610000";
    private static final String FALLBACK_SMSC_DOMAIN = "telefonica.de";

    /** Optimistic success timeout when no RP-ACK / RP-ERROR arrives
     *  after a successful SIP 2xx. Covers carriers that ack at the SIP
     *  layer but never send a server-initiated RP-ACK MESSAGE. 30 s is
     *  the same timeout Android's GsmSMSDispatcher uses for CS SUBMIT. */
    private static final long MO_FALLBACK_TIMEOUT_MS = 30_000L;

    /** TS 23.040 §9.2.3.1 TP-MTI (MT direction): 00 = DELIVER, 10 = STATUS-REPORT. */
    private static final int TP_MTI_DELIVER        = 0x00;
    private static final int TP_MTI_STATUS_REPORT  = 0x02;

    private final ImsRegistrationController mRegController;
    private final AtomicInteger mIncomingToken = new AtomicInteger(1);
    private final Handler mMain = new Handler(Looper.getMainLooper());

    /** MO send awaiting the SMSC's RP-ACK / RP-ERROR. */
    private final PendingMoSms mPending = new PendingMoSms();
    /** Optimistic-success timer of the pending send and the token it belongs to. */
    private Runnable mPendingTimeout = null;
    private int mPendingTimeoutToken = -1;

    HamelinPortsSmsImpl(ImsRegistrationController regController) {
        mRegController = regController;
        mRegController.stack().setSmsListener(this);
    }

    /** Build the SMSC SIP URI ({@code sip:<E164>@<carrier-domain>}).
     *  The carrier domain is the IMS public domain learned from the
     *  REGISTER 200 OK's P-Associated-URI; we do NOT hard-code it. */
    private String resolveSmscUri(String smscNumber) {
        String domain = mRegController.getPublicDomain();
        if (domain == null || domain.isEmpty()) {
            domain = FALLBACK_SMSC_DOMAIN;
            Log.w(TAG, "SMSC URI domain empty — using fallback " + domain);
        }
        return "sip:" + smscNumber + "@" + domain;
    }

    @Override public void onReady() { Log.i(TAG, "SMS onReady"); }
    @Override public String getSmsFormat() { return SmsMessage.FORMAT_3GPP; }

    private void failSmsRetryable(int token, int messageRef, String why) {
        Log.w(TAG, "sendSms token=" + token + ": " + why + " — retryable failure");
        onSendSmsResultError(token, messageRef, SEND_STATUS_ERROR_RETRY,
                SmsManager.RESULT_ERROR_NO_SERVICE, 0);
    }

    @Override
    public void sendSms(int token, int messageRef, String format,
                        String smsc, boolean isRetry, byte[] pdu) {
        Log.i(TAG, "sendSms token=" + token + " messageRef=" + messageRef
                + " format=" + format + " pduLen=" + pdu.length);
        mRegController.checkRegistrationBeforeOutgoing();
        sendSmsWhenRegistered(token, messageRef, format, smsc, isRetry, pdu, /*mayDefer=*/ true);
    }

    /**
     * Sends now, waits once for a pending renewal (bounded by the deferral
     * limit), or fails. A wait that ends without a registration fails with
     * SEND_STATUS_ERROR_RETRY: telephony retries and falls back to CS after
     * its IMS retry limit.
     */
    private void sendSmsWhenRegistered(int token, int messageRef, String format,
                                       String smsc, boolean isRetry, byte[] pdu,
                                       boolean mayDefer) {
        switch (OutgoingGate.decideSms(mRegController.isRegistered(),
                mRegController.isRetryInFlight(), mayDefer)) {
            case SEND:
                break;
            case DEFER:
                Log.i(TAG, "sendSms token=" + token
                        + ": registration renewal pending, deferring");
                mRegController.deferUntilRegistered(
                        () -> sendSmsWhenRegistered(token, messageRef, format, smsc,
                                isRetry, pdu, /*mayDefer=*/ false),
                        () -> failSmsRetryable(token, messageRef,
                                "registration renewal too slow"));
                return;
            case FAIL:
                if (!mayDefer) {
                    failSmsRetryable(token, messageRef, "registration renewal failed");
                    return;
                }
                Log.e(TAG, "sendSms: not registered");
                onSendSmsResultError(token, messageRef, SEND_STATUS_ERROR,
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE, 0);
                return;
        }

        /* P-Access-Network-Info: the decorator adds it on non-REGISTER
         * requests but only if the Java side has populated PaniCache.
         * MO SMS needs PANI on Mavenir networks — refresh before each
         * send. The dispatcher picks 3GPP-E-UTRAN-FDD on cellular IMS
         * and IEEE-802.11 (with the AP BSSID) on Wi-Fi Calling. */
        SipClient.refreshPaniForOutbound(
                mRegController.getContext(), mRegController.getLastBoundIface(),
                mRegController.stack());

        // Wrap TPDU in RP-DATA per 3GPP TS 24.011
        byte[] rpData = RpData.wrapMo(messageRef, SMSC_NUMBER, pdu);
        // RP-MR is byte 1 of the wrapped RP-DATA (wrapMo writes messageRef & 0xFF).
        int rpMr = rpData.length > 1 ? (rpData[1] & 0xFF) : -1;
        Log.i(TAG, "MO RP-DATA " + rpData.length + " bytes (TPDU " + pdu.length
                + ") RP-MR=" + rpMr);

        /* Re-claim the static SMS listener. HamelinPortsImsService creates
         * one HamelinPortsSmsImpl per active slot; the static listener is
         * last-writer-wins. Without this re-claim the SMSC RP-ACK
         * MESSAGE gets routed to the wrong instance. */
        mRegController.stack().setSmsListener(this);

        /* Only one send is tracked; an earlier one still pending gets no
         * outcome (logged). */
        int superseded = mPending.begin(token, messageRef, rpMr);
        if (superseded >= 0) {
            cancelPendingTimeout(superseded);
            Log.w(TAG, "MO token=" + superseded + " superseded by token=" + token
                    + " before its RP-ACK/RP-ERROR; it gets no outcome");
        }

        boolean queued = mRegController.stack().sendSms(resolveSmscUri(SMSC_NUMBER), CONTENT_TYPE, rpData);
        if (!queued) {
            PendingMoSms.Outcome o = mPending.onNotSent(token);
            if (o.ignored == null) {
                onSendSmsResultError(token, messageRef, SEND_STATUS_ERROR,
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE, 0);
            }
            return;
        }
        /* Arm the fallback timer. If no RP-ACK/RP-ERROR arrives within
         * the window, report optimistic success on the SIP 2xx we
         * (presumably) got — otherwise the Messaging UI hangs. */
        armPendingTimeout(token);
    }

    @Override
    public void acknowledgeSms(int token, int messageRef, int result) {
        Log.i(TAG, "acknowledgeSms token=" + token + " messageRef=" + messageRef
                + " result=" + result);
        /* The SMS-layer RP-ACK is sent immediately from handleMtRpData,
         * not here. This callback is purely informational: the
         * framework tells us whether the broadcast to the Messaging
         * app succeeded. No network traffic required. */
    }

    /** RP-ACK (ms→n, MTI=2) — TS 24.011 §8.2.3. 2-byte minimal form:
     *  MTI + MR, no RP-User-Data. */
    private static byte[] buildRpAckMsToN(int rpMr) {
        return new byte[] { 0x02, (byte) (rpMr & 0xFF) };
    }

    /** Send RP-ACK (ms→n) back to the SMSC instance that sent us the
     *  last MT RP-DATA. Per TS 24.229 §5.3.1.3.4 a terminating UE
     *  responds to an MT MESSAGE with both a SIP 200 OK (transport
     *  ack, handled by native acceptCommand) AND a separate SIP
     *  MESSAGE carrying the RP-ACK at the SMS layer. Without the
     *  SMS-layer RP-ACK, Mavenir's IP-SM-GW keeps re-queuing the MT.
     *
     *  Target: the P-Asserted-Identity of the incoming MT MESSAGE
     *  (e.g. {@code sip:10.112.204.3:5060}). Mavenir fronts several
     *  IP-SM-GW instances and only the originating one holds the
     *  transaction state — routing the RP-ACK through the generic
     *  SMSC E.164 URI elicits 481 Call/Transaction Does Not Exist. */
    private void sendRpAckToSmsc(int rpMr) {
        String pai = mRegController.stack().getLastMtPai();
        if (pai == null || pai.isEmpty()) {
            Log.w(TAG, "sendRpAckToSmsc: no PAI captured; skipping RP-ACK");
            return;
        }
        byte[] rpAck = buildRpAckMsToN(rpMr);
        Log.i(TAG, "RP-ACK → " + pai + " RP-MR=" + rpMr);
        boolean queued = mRegController.stack().sendSms(pai, CONTENT_TYPE, rpAck);
        if (!queued) Log.w(TAG, "RP-ACK send failed to queue");
    }

    /** Decode TP-SCTS (service centre time stamp) from a SMS-DELIVER
     *  TPDU per 3GPP TS 23.040 §9.2.3.11. Useful to distinguish an
     *  original MT from a Mavenir queue retry (retries carry the same
     *  SCTS, original fresh MTs carry a newer SCTS). */
    private static String decodeTpScts(byte[] tpdu) {
        if (tpdu == null || tpdu.length < 1) return "?";
        int i = 1; // skip first flags octet
        if (i >= tpdu.length) return "?";
        int toaLen = tpdu[i++] & 0xFF;
        i += 1 + (toaLen + 1) / 2; // TON/NPI + digits (semi-octet)
        if (i + 9 > tpdu.length) return "?"; // need PID + DCS + 7 SCTS bytes
        i += 2; // PID, DCS
        StringBuilder sb = new StringBuilder(24);
        final String order = "YMDhms"; // year, month, day, hour, min, sec
        for (int k = 0; k < 6; k++) {
            int b = tpdu[i + k] & 0xFF;
            int lo = b & 0x0F;
            int hi = (b >> 4) & 0x0F;
            if (k == 0) sb.append("20"); // 2-digit year
            sb.append(lo).append(hi);
            if      (k == 0) sb.append('-');
            else if (k == 1) sb.append('-');
            else if (k == 2) sb.append(' ');
            else if (k == 3) sb.append(':');
            else if (k == 4) sb.append(':');
        }
        int tz = tpdu[i + 6] & 0xFF;
        boolean neg = (tz & 0x08) != 0;
        int tzQ = (tz & 0x07) * 10 + ((tz >> 4) & 0x0F);
        int tzMin = tzQ * 15;
        sb.append(' ').append(neg ? '-' : '+');
        sb.append(String.format("%02d:%02d", tzMin / 60, tzMin % 60));
        return sb.toString();
    }

    /* --- SmsSessionListener: results from native ClientPagerMessage --- */

    @Override
    public void onSendSuccess(int statusCode, String reason) {
        /* SIP 2xx from the P-CSCF means the MESSAGE was accepted for
         * onward routing — NOT that the SMSC has stored it. Wait for
         * the SMSC's RP-ACK / RP-ERROR (arrives as a server-initiated
         * SIP MESSAGE) before signalling framework. */
        Log.i(TAG, "MO MESSAGE SIP " + statusCode + " " + reason
                + "; waiting for RP-ACK/RP-ERROR");
    }

    @Override
    public void onSendFailure(int statusCode, String reason) {
        /* SIP 4xx/5xx — P-CSCF rejected the MESSAGE. Final outcome. */
        Log.w(TAG, "MO SMS failed at SIP layer: " + statusCode + " " + reason);
        PendingMoSms.Outcome o = mPending.onSipFailure(statusCode);
        if (o.ignored != null) return;
        cancelPendingTimeout(o.token);
        onSendSmsResultError(o.token, o.messageRef, SEND_STATUS_ERROR,
                SmsManager.RESULT_ERROR_GENERIC_FAILURE, statusCode);
    }

    @Override
    public void onIncomingSms(byte[] body) {
        if (body == null || body.length == 0) return;
        Log.i(TAG, "MT SMS body " + body.length + " bytes");
        /* Populate the native PANI cache synchronously so the decorator
         * stamps P-Access-Network-Info on the 200 OK we emit for the
         * incoming MESSAGE. Mavenir SMSC gates its "delivered, remove
         * from queue" logic on PANI being present in the response;
         * without it, the SMSC re-queues the MT SMS indefinitely
         * (observed: RP-MRs 203/236/216 for the same TPDU within 3min).
         * JNI's h->acceptCommand(200) posts the response via the DUM
         * command thread, so setting PANI here (before onIncomingSms
         * returns) runs before callOutboundDecorators dispatches the
         * decorator chain on the outgoing 200 OK. */
        String cellIdPani = SipClient.getCellIdForPani(mRegController.getContext());
        if (cellIdPani != null) {
            mRegController.stack().setCellIdForPani(cellIdPani);
        }
        RpData.Parsed parsed = RpData.parseMt(body);
        if (parsed == null) {
            Log.w(TAG, "unparseable RP-PDU, dropping");
            return;
        }
        switch (parsed.rpMti) {
            case RpData.RP_MTI_ACK_N_TO_MS:
                handleRpAck(parsed);
                return;
            case RpData.RP_MTI_ERROR_N_TO_MS:
                handleRpError(RpError.parse(body));
                return;
            case RpData.RP_MTI_DATA_N_TO_MS:
                handleMtRpData(parsed);
                return;
            default:
                Log.w(TAG, "unexpected RP-MTI " + parsed.rpMti + ", dropping");
        }
    }

    /** RP-ACK (n→ms, MTI=3): SMSC accepted our MO. Complete the pending
     *  send with a framework success callback. Optional inner TPDU is an
     *  SMS-SUBMIT-REPORT-FOR-RP-ACK; we log TP-SCTS for debugging but
     *  don't surface it (framework has no hook). */
    private void handleRpAck(RpData.Parsed parsed) {
        Log.i(TAG, "RP-ACK RP-MR=" + parsed.rpMr);
        PendingMoSms.Outcome o = mPending.onRpAck(parsed.rpMr);
        if (o.ignored != null) {
            /* A late ack of an earlier send must not complete the current one. */
            Log.w(TAG, o.ignored + "; ignoring");
            return;
        }
        cancelPendingTimeout(o.token);
        onSendSmsResultSuccess(o.token, o.messageRef);
    }

    /** RP-ERROR (n→ms, MTI=5): the SMSC did not store our MO. The RP-Cause
     *  (TS 24.011 §8.2.5.4) picks the framework status ({@link RpErrorPolicy})
     *  and the SmsManager result the Messaging app sees. */
    private void handleRpError(RpError error) {
        Log.w(TAG, "RP-ERROR " + error.describe());
        PendingMoSms.Outcome o = mPending.onRpError(error, rpErrorPolicy());
        if (o.ignored != null) {
            Log.w(TAG, o.ignored + "; ignoring");
            return;
        }
        cancelPendingTimeout(o.token);
        int status = toSendStatus(o.status);
        Log.i(TAG, "RP-ERROR cause=" + error.cause + " token=" + o.token + " -> " + o.status
                + (o.status == RpErrorPolicy.Status.ERROR_FALLBACK ? " (resend over CS)"
                        : o.status == RpErrorPolicy.Status.ERROR_RETRY ? " (retry over IMS)" : ""));
        onSendSmsResultError(o.token, o.messageRef, status,
                rpCauseToSmsManagerResult(error.cause), error.cause);
    }

    /**
     * The carrier's RP-Cause lists, read per RP-ERROR, when {@value RpErrorPolicy#PROPERTY}
     * is on. A carrier config that cannot be read gives no retry and no fallback.
     */
    private RpErrorPolicy rpErrorPolicy() {
        if (!SystemProperties.getBoolean(RpErrorPolicy.PROPERTY, false)) {
            return RpErrorPolicy.off();
        }
        int subId = mRegController.activeSubscriptionId();
        CarrierConfigManager ccm =
                mRegController.getContext().getSystemService(CarrierConfigManager.class);
        PersistableBundle config = null;
        if (ccm != null && SubscriptionManager.isValidSubscriptionId(subId)) {
            try {
                config = ccm.getConfigForSubId(subId,
                        CarrierConfigManager.ImsSms.KEY_SMS_RP_CAUSE_VALUES_TO_FALLBACK_INT_ARRAY,
                        CarrierConfigManager.ImsSms.KEY_SMS_RP_CAUSE_VALUES_TO_RETRY_OVER_IMS_INT_ARRAY,
                        CarrierConfigManager.ImsSms.KEY_SMS_CSFB_RETRY_ON_FAILURE_BOOL);
            } catch (RuntimeException e) {
                Log.w(TAG, "carrier config unreadable subId=" + subId + ": " + e);
            }
        }
        if (config == null) {
            return new RpErrorPolicy(true, null, null, false);
        }
        return new RpErrorPolicy(true,
                config.getIntArray(
                        CarrierConfigManager.ImsSms.KEY_SMS_RP_CAUSE_VALUES_TO_FALLBACK_INT_ARRAY),
                config.getIntArray(
                        CarrierConfigManager.ImsSms.KEY_SMS_RP_CAUSE_VALUES_TO_RETRY_OVER_IMS_INT_ARRAY),
                config.getBoolean(CarrierConfigManager.ImsSms.KEY_SMS_CSFB_RETRY_ON_FAILURE_BOOL,
                        false));
    }

    private static int toSendStatus(RpErrorPolicy.Status status) {
        switch (status) {
            case OK:
                return SEND_STATUS_OK;
            case ERROR_RETRY:
                return SEND_STATUS_ERROR_RETRY;
            case ERROR_FALLBACK:
                return SEND_STATUS_ERROR_FALLBACK;
            case ERROR:
            default:
                return SEND_STATUS_ERROR;
        }
    }

    /** Map RP-Cause (TS 24.011 §8.2.5.4) to SmsManager.Result codes the
     *  framework propagates to the Messaging app's sent-intent. */
    private static int rpCauseToSmsManagerResult(int rpCause) {
        switch (rpCause) {
            case 1:  // Unassigned (unallocated) number
            case 27: // Destination out of order
            case 28: // Unidentified subscriber
            case 30: // Unknown subscriber
                return SmsManager.RESULT_ERROR_NO_SERVICE;
            case 22: // Memory capacity exceeded
                return SmsManager.RESULT_ERROR_LIMIT_EXCEEDED;
            case 41: // Temporary failure
            case 42: // Congestion
            case 47: // Resources unavailable
                return SmsManager.RESULT_ERROR_LIMIT_EXCEEDED;
            case 38: // Network out of order
                return SmsManager.RESULT_ERROR_RADIO_OFF;
            default:
                return SmsManager.RESULT_ERROR_GENERIC_FAILURE;
        }
    }

    /** RP-DATA (n→ms, MTI=1): an SMSC-originated RP-DATA carrying either
     *  a genuine SMS-DELIVER (someone sent us an SMS) or an
     *  SMS-STATUS-REPORT (delivery receipt for a prior MO we sent with
     *  TP-SRR=1). Frame the TPDU with SCA-length = 0 per GSM 03.40 §9.2
     *  so the framework's SmsMessage parser accepts it, then route to
     *  the appropriate callback. */
    private void handleMtRpData(RpData.Parsed parsed) {
        if (parsed.tpdu == null || parsed.tpdu.length == 0) {
            Log.w(TAG, "RP-DATA with empty TPDU, dropping");
            return;
        }
        int tpMti = parsed.tpdu[0] & 0x03;
        byte[] framedPdu = new byte[parsed.tpdu.length + 1];
        framedPdu[0] = 0x00;
        System.arraycopy(parsed.tpdu, 0, framedPdu, 1, parsed.tpdu.length);

        if (tpMti == TP_MTI_STATUS_REPORT) {
            /* SMS-STATUS-REPORT TPDU (TS 23.040 §9.2.2.3):
             *   byte 0: TP-SRQ | TP-MMS | TP-LP | TP-UDHI | TP-SRI | TP-MTI(2 bits)
             *   byte 1: TP-MR — message reference this status is for
             *     (matches the TP-MR of the original SUBMIT)
             * Extract TP-MR and pass to framework so it can correlate
             * with the originating send's tracker and fire deliveryIntent. */
            int tpMr = parsed.tpdu[1] & 0xFF;
            int token = mIncomingToken.getAndIncrement();
            Log.i(TAG, "SMS-STATUS-REPORT TP-MR=" + tpMr + " tpduLen="
                    + parsed.tpdu.length + " — delivery receipt");
            onSmsStatusReportReceived(token, SmsMessage.FORMAT_3GPP, framedPdu);
            return;
        }

        if (tpMti == TP_MTI_DELIVER) {
            int token = mIncomingToken.getAndIncrement();
            Log.i(TAG, "SMS-DELIVER, token=" + token + " tpduLen=" + parsed.tpdu.length
                    + " RP-MR=" + parsed.rpMr + " TP-SCTS=" + decodeTpScts(parsed.tpdu));
            onSmsReceived(token, SmsMessage.FORMAT_3GPP, framedPdu);
            sendRpAckToSmsc(parsed.rpMr);
            return;
        }

        Log.w(TAG, "RP-DATA with unexpected TP-MTI=" + tpMti + ", dropping");
    }

    // --- pending-send bookkeeping ---

    private synchronized void armPendingTimeout(int token) {
        Runnable timeout = () -> {
            PendingMoSms.Outcome o = mPending.onTimeout(token);
            if (o.ignored != null) return;
            Log.w(TAG, "MO timed out waiting for RP-ACK — optimistic success (token="
                    + o.token + ")");
            onSendSmsResultSuccess(o.token, o.messageRef);
        };
        if (mPendingTimeout != null) mMain.removeCallbacks(mPendingTimeout);
        mPendingTimeout = timeout;
        mPendingTimeoutToken = token;
        mMain.postDelayed(timeout, MO_FALLBACK_TIMEOUT_MS);
    }

    /** Cancels the timer of {@code token} only: a newer send keeps its own. */
    private synchronized void cancelPendingTimeout(int token) {
        if (mPendingTimeout != null && mPendingTimeoutToken == token) {
            mMain.removeCallbacks(mPendingTimeout);
            mPendingTimeout = null;
            mPendingTimeoutToken = -1;
        }
    }
}
