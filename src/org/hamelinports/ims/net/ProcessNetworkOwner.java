package org.hamelinports.ims.net;

/**
 * Owner of the process-wide network binding. Only the slot that bound the
 * process may clear the binding, and only while the process is still bound to
 * that slot's network. Otherwise one slot's teardown would unbind the network
 * another slot just bound, and the next socket would leave on the default
 * network.
 *
 * <p>Thread-safe: each slot calls it from its own registration thread. Pure
 * Java (the binding is injected), so the host tests drive it directly.
 *
 * @param <N> the network type
 */
public final class ProcessNetworkOwner<N> {

    /** The process-wide binding this class guards. */
    public interface Binding<N> {
        /** Binds the process to {@code network}; false if the binding was refused. */
        boolean bind(N network);

        /** Clears the process binding. */
        void clear();

        /** The network the process is bound to, or null. */
        N current();
    }

    public static final int NO_SLOT = -1;

    private final Binding<N> mBinding;
    private int mOwnerSlot = NO_SLOT;
    private N mOwnerNetwork;

    public ProcessNetworkOwner(Binding<N> binding) {
        mBinding = binding;
    }

    /** Binds the process to {@code network}; on success {@code slot} becomes the owner. */
    public synchronized boolean bind(int slot, N network) {
        if (network == null || !mBinding.bind(network)) {
            return false;
        }
        mOwnerSlot = slot;
        mOwnerNetwork = network;
        return true;
    }

    /**
     * Clears the binding if {@code slot} owns it and the process is still bound
     * to the network it bound. Ownership ends either way. Returns true if the
     * binding was cleared.
     */
    public synchronized boolean release(int slot) {
        if (slot == NO_SLOT || slot != mOwnerSlot) {
            return false;
        }
        N owned = mOwnerNetwork;
        mOwnerSlot = NO_SLOT;
        mOwnerNetwork = null;
        if (!owned.equals(mBinding.current())) {
            return false;
        }
        mBinding.clear();
        return true;
    }

    public synchronized int ownerSlot() {
        return mOwnerSlot;
    }
}
