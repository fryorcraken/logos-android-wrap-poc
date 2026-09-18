package com.fryorcraken.logos.storage

import com.fryorcraken.logos.common.LogosException
import com.fryorcraken.logos.common.NativeCallback
import com.fryorcraken.logos.common.NodeLifecycle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Idiomatic Kotlin API for a Logos Storage node.
 *
 * STUB (Milestone 2): wraps [StorageNative], which is itself a stub until
 * Milestone 4 wires the real JNI shim and native library. Constructing and
 * using this class will fail with [UnsatisfiedLinkError] until then.
 *
 * `libstorage.h`'s C API is entirely asynchronous — every call dispatches
 * to a worker thread and reports its outcome via callback, including
 * start/stop/close. [NodeLifecycle] promises a synchronous shape (matching
 * delivery's synchronous node/start/stop), so [start]/[stop] here block on
 * a [CountDownLatch] until the corresponding callback fires. This is a
 * deliberate tradeoff to keep both wrapped libraries usable through one
 * shared interface — callers needing non-blocking storage operations
 * (upload/download progress, etc.) should use [StorageNative] directly
 * rather than go through this class.
 */
public class StorageNode(configJson: String) : NodeLifecycle {
    private var ctx: Long = 0L

    init {
        val latch = CountDownLatch(1)
        var createRet = -1
        ctx = StorageNative.nativeNew(configJson, NativeCallback { ret, _ ->
            createRet = ret
            latch.countDown()
        })
        check(ctx != 0L) { "storage_new failed to allocate a context" }
        awaitOrThrow(latch, "storage_new")
        if (createRet != 0) throw LogosException("storage_new failed", createRet)
    }

    override var isRunning: Boolean = false
        private set

    override fun start() {
        check(ctx != 0L) { "StorageNode already destroyed" }
        blockingCall("storage_start") { cb -> StorageNative.nativeStart(ctx, cb) }
        isRunning = true
    }

    override fun stop() {
        check(ctx != 0L) { "StorageNode already destroyed" }
        blockingCall("storage_stop") { cb -> StorageNative.nativeStop(ctx, cb) }
        isRunning = false
    }

    override fun destroy() {
        if (ctx == 0L) return
        runCatching { blockingCall("storage_close") { cb -> StorageNative.nativeClose(ctx, cb) } }
        StorageNative.nativeDestroy(ctx)
        ctx = 0L
    }

    /**
     * Connects to a peer by address or id. See `storage_connect` in
     * `library/libstorage.h` — the peer id must already be advertised in
     * the DHT for id-only connection to work.
     */
    public fun connect(peerId: String? = null, peerAddresses: List<String> = emptyList()) {
        check(ctx != 0L) { "StorageNode already destroyed" }
        blockingCall("storage_connect") { cb ->
            StorageNative.nativeConnect(ctx, peerId, peerAddresses.toTypedArray(), cb)
        }
    }

    /** Returns this node's own peer id (JSON/string payload per storage_peer_id). */
    public fun peerId(): StorageResult = resultCall("storage_peer_id") { cb -> StorageNative.nativePeerId(ctx, cb) }

    /** Returns debug info (JSON) for the given peer, if compiled with peer-debug support. */
    public fun peerDebug(peerId: String): StorageResult =
        resultCall("storage_peer_debug") { cb -> StorageNative.nativePeerDebug(ctx, peerId, cb) }

    /** Returns node debug info (JSON) — TODO(Milestone 4): confirm this is where peer count/connection state lives. */
    public fun debug(): StorageResult = resultCall("storage_debug") { cb -> StorageNative.nativeDebug(ctx, cb) }

    private fun blockingCall(opName: String, invoke: (NativeCallback) -> Int) {
        val result = resultCall(opName, invoke)
        if (result.retCode != 0) throw LogosException("$opName failed", result.retCode)
    }

    private fun resultCall(opName: String, invoke: (NativeCallback) -> Int): StorageResult {
        val latch = CountDownLatch(1)
        var ret = -1
        var msg = ByteArray(0)
        val dispatchRet = invoke(NativeCallback { callerRet, message ->
            ret = callerRet
            msg = message
            latch.countDown()
        })
        if (dispatchRet != 0) throw LogosException("$opName dispatch failed", dispatchRet)
        awaitOrThrow(latch, opName)
        return StorageResult(ret, msg)
    }

    private fun awaitOrThrow(latch: CountDownLatch, opName: String) {
        check(latch.await(30, TimeUnit.SECONDS)) { "$opName timed out waiting for native callback" }
    }
}
