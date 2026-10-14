package org.hamelinports.ims.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(JUnit4.class)
public class ProcessNetworkOwnerTest {
    private static final String NET_SLOT0 = "net-100";
    private static final String NET_SLOT1 = "net-101";

    /** Stands in for ConnectivityManager's process binding. */
    private static final class FakeBinding implements ProcessNetworkOwner.Binding<String> {
        volatile String bound;
        volatile int clears;
        boolean refuse;

        @Override public synchronized boolean bind(String network) {
            if (refuse) return false;
            bound = network;
            return true;
        }

        @Override public synchronized void clear() {
            bound = null;
            clears++;
        }

        @Override public synchronized String current() {
            return bound;
        }
    }

    @Test
    public void nonOwnerReleaseIsANoOp() {
        FakeBinding b = new FakeBinding();
        ProcessNetworkOwner<String> owner = new ProcessNetworkOwner<>(b);
        assertTrue(owner.bind(1, NET_SLOT1));
        assertFalse(owner.release(0));
        assertEquals(NET_SLOT1, b.bound);
        assertEquals(0, b.clears);
        assertEquals(1, owner.ownerSlot());
    }

    @Test
    public void releaseWithoutAnyBindIsANoOp() {
        FakeBinding b = new FakeBinding();
        ProcessNetworkOwner<String> owner = new ProcessNetworkOwner<>(b);
        assertFalse(owner.release(0));
        assertFalse(owner.release(ProcessNetworkOwner.NO_SLOT));
        assertEquals(0, b.clears);
    }

    @Test
    public void ownerReleaseClears() {
        FakeBinding b = new FakeBinding();
        ProcessNetworkOwner<String> owner = new ProcessNetworkOwner<>(b);
        assertTrue(owner.bind(1, NET_SLOT1));
        assertTrue(owner.release(1));
        assertNull(b.bound);
        assertEquals(1, b.clears);
        assertEquals(ProcessNetworkOwner.NO_SLOT, owner.ownerSlot());
        assertFalse(owner.release(1));
        assertEquals(1, b.clears);
    }

    @Test
    public void ownerDoesNotClearAForeignBinding() {
        FakeBinding b = new FakeBinding();
        ProcessNetworkOwner<String> owner = new ProcessNetworkOwner<>(b);
        assertTrue(owner.bind(1, NET_SLOT1));
        b.bound = "net-other";                  /* rebound outside the owner */
        assertFalse(owner.release(1));
        assertEquals("net-other", b.bound);
        assertEquals(0, b.clears);
        assertEquals(ProcessNetworkOwner.NO_SLOT, owner.ownerSlot());
    }

    @Test
    public void secondBindMovesOwnership() {
        FakeBinding b = new FakeBinding();
        ProcessNetworkOwner<String> owner = new ProcessNetworkOwner<>(b);
        assertTrue(owner.bind(0, NET_SLOT0));
        assertTrue(owner.bind(1, NET_SLOT1));
        assertEquals(1, owner.ownerSlot());
        assertFalse(owner.release(0));
        assertEquals(NET_SLOT1, b.bound);
        assertTrue(owner.release(1));
        assertNull(b.bound);
    }

    @Test
    public void refusedBindKeepsThePreviousOwner() {
        FakeBinding b = new FakeBinding();
        ProcessNetworkOwner<String> owner = new ProcessNetworkOwner<>(b);
        assertTrue(owner.bind(1, NET_SLOT1));
        b.refuse = true;
        assertFalse(owner.bind(0, NET_SLOT0));
        assertFalse(owner.bind(0, null));
        assertEquals(1, owner.ownerSlot());
        assertFalse(owner.release(0));
        assertEquals(NET_SLOT1, b.bound);
    }

    /** Binding to null would clear the process binding behind the owner's back. */
    @Test
    public void nullBindIsRefusedAndDoesNotClear() {
        FakeBinding b = new FakeBinding();
        ProcessNetworkOwner<String> owner = new ProcessNetworkOwner<>(b);
        assertTrue(owner.bind(1, NET_SLOT1));
        assertFalse(owner.bind(0, null));
        assertEquals(NET_SLOT1, b.bound);
        assertEquals(1, owner.ownerSlot());
    }

    /** r8: slot 0's tear-down ran right after slot 1 bound for a fresh registration. */
    @Test
    public void otherSlotTearDownAfterBindKeepsTheBinding() {
        FakeBinding b = new FakeBinding();
        ProcessNetworkOwner<String> owner = new ProcessNetworkOwner<>(b);
        assertTrue(owner.bind(1, NET_SLOT1));
        for (int i = 0; i < 5; i++) {
            owner.release(0);                   /* each slot-0 trial tear-down */
        }
        assertEquals(NET_SLOT1, b.bound);
        assertEquals(0, b.clears);
    }

    @Test
    public void concurrentNonOwnerReleasesNeverClear() throws Exception {
        FakeBinding b = new FakeBinding();
        ProcessNetworkOwner<String> owner = new ProcessNetworkOwner<>(b);
        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        Thread slot0 = new Thread(() -> {
            try { go.await(); } catch (InterruptedException e) { return; }
            while (!stop.get()) owner.release(0);
        });
        slot0.start();
        go.countDown();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(owner.bind(1, NET_SLOT1));
            assertEquals(NET_SLOT1, b.current());
        }
        stop.set(true);
        slot0.join();
        assertEquals(0, b.clears);
        assertEquals(NET_SLOT1, b.bound);
    }
}
