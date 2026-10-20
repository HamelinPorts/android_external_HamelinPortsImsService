package org.hamelinports.ims.call;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.hamelinports.ims.call.MtCallState.Action;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MtCallStateTest {

    private static MtCallState accepted() {
        MtCallState s = new MtCallState();
        assertTrue(s.accept());
        return s;
    }

    @Test
    public void normalCall() {
        MtCallState s = accepted();
        assertTrue(s.isAccepted());
        assertEquals(Action.ESTABLISH, s.onConnected());
        assertEquals(Action.TERMINATE, s.onTerminated());
        assertTrue(s.isTerminated());
    }

    /** The misdispatched onConnected arrived as onFailure(200): refused, nothing changes. */
    @Test
    public void onFailureWithASuccessStatusIsRefused() {
        MtCallState s = accepted();
        assertEquals(Action.REFUSE, s.onFailure(200));
        assertEquals(Action.REFUSE, s.onFailure(180));
        assertEquals(Action.REFUSE, s.onFailure(0));
        assertEquals(Action.REFUSE, s.onFailure(-1));
        assertEquals(Action.REFUSE, s.onFailure(700));
        assertFalse(s.isTerminated());
        assertEquals("the call goes on", Action.ESTABLISH, s.onConnected());
    }

    @Test
    public void failureAfterAcceptEndsTheDialogAndReportsOnce() {
        MtCallState s = accepted();
        assertEquals(Action.ESTABLISH, s.onConnected());
        assertEquals(Action.END_DIALOG_AND_TERMINATE, s.onFailure(408));
        assertTrue(s.isTerminated());
        assertEquals(Action.NONE, s.onFailure(500));
        assertEquals("BYE after the failure is a no-op", Action.NONE, s.onTerminated());
        assertFalse("no second report from a local end", s.endLocally());
    }

    @Test
    public void failureBeforeTheConnectAlsoEndsTheDialog() {
        MtCallState s = accepted();
        assertEquals(Action.END_DIALOG_AND_TERMINATE, s.onFailure(300));
        assertEquals(Action.NONE, s.onConnected());
    }

    @Test
    public void failureOfAnUnacceptedCallOnlyTerminates() {
        MtCallState s = new MtCallState();
        assertEquals(Action.TERMINATE, s.onFailure(699));
        assertFalse(s.accept());
    }

    @Test
    public void connectBeforeAcceptIsRefused() {
        MtCallState s = new MtCallState();
        assertEquals(Action.REFUSE, s.onConnected());
        assertFalse(s.isTerminated());
        assertTrue(s.accept());
    }

    @Test
    public void localEndReportsOnceAndSilencesLaterEvents() {
        MtCallState s = accepted();
        assertEquals(Action.ESTABLISH, s.onConnected());
        assertTrue(s.endLocally());
        assertFalse(s.endLocally());
        assertEquals("the late BYE upcall", Action.NONE, s.onTerminated());
        assertEquals(Action.NONE, s.onFailure(487));
        assertEquals(Action.NONE, s.onConnected());
    }

    @Test
    public void remoteByeThenLocalHangup() {
        MtCallState s = accepted();
        s.onConnected();
        assertEquals(Action.TERMINATE, s.onTerminated());
        assertFalse("terminate() after the BYE reports nothing", s.endLocally());
    }

    @Test
    public void acceptOnlyOnceAndNotAfterTheEnd() {
        MtCallState s = new MtCallState();
        assertFalse(s.isAccepted());
        assertTrue(s.accept());
        assertFalse(s.accept());
        MtCallState rejected = new MtCallState();
        assertTrue(rejected.endLocally());
        assertFalse(rejected.accept());
        assertFalse(rejected.isAccepted());
    }

    @Test
    public void acceptedStaysTrueAfterTheEnd() {
        MtCallState s = accepted();
        s.onTerminated();
        assertTrue(s.isAccepted());
    }

    @Test
    public void duplicateConnectIsIgnored() {
        MtCallState s = accepted();
        assertEquals(Action.ESTABLISH, s.onConnected());
        assertEquals(Action.NONE, s.onConnected());
    }

    @Test
    public void failureStatusRange() {
        assertFalse(SipStatus.isFailure(299));
        assertTrue(SipStatus.isFailure(300));
        assertTrue(SipStatus.isFailure(486));
        assertTrue(SipStatus.isFailure(699));
        assertFalse(SipStatus.isFailure(700));
        assertFalse(SipStatus.isFailure(200));
    }
}
