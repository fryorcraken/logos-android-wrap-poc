package com.fryorcraken.logos.common

/**
 * Shared lifecycle shape for a Logos p2p node, implemented by both
 * `logos-delivery`'s `DeliveryNode` and `logos-storage`'s `StorageNode` —
 * both wrap a native library whose C API follows the same
 * create/start/stop/destroy pattern around an opaque context pointer.
 *
 * This interface intentionally has no knowledge of either concrete node's
 * native library or JNI shim — it exists so shared, library-agnostic code
 * (e.g. a future glue module reasoning about "is this node running") can be
 * written once against both, without `logos-common` itself depending on
 * either native-bearing module. See docs/adr/0001 for why that separation
 * matters.
 */
public interface NodeLifecycle {
    /** True once [start] has completed successfully and before [stop]/[destroy]. */
    public val isRunning: Boolean

    /** Starts the node. Safe to call only once per instance's lifetime. */
    public fun start()

    /** Stops the node. The node may be [start]ed again afterwards. */
    public fun stop()

    /** Releases all native resources. The instance is unusable afterwards. */
    public fun destroy()
}
