package com.fryorcraken.logos.delivery

import com.fryorcraken.logos.common.LogosException
import com.fryorcraken.logos.common.NativeCallback
import com.fryorcraken.logos.common.NodeLifecycle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Idiomatic Kotlin API for a Logos Messaging (delivery) node.
 *
 * Reconciled against the real generated header (Milestone 3). The
 * `configJson` constructor parameter was Milestone 2's guess and turned out
 * correct: `logosdelivery_ctx_create`'s only argument is a JSON config
 * string (see `library/README.md`'s "Example configuration JSON" — the
 * `mode`/`preset`/`messagingOverrides`/`channelsOverrides` shape). What the
 * stub got wrong was the lifecycle's synchronicity: `liblogosdelivery`'s
 * generated C API is fully asynchronous (every `logosdelivery_ctx_*` call
 * submits and returns immediately; the result arrives via callback from
 * nim-ffi's own dispatch thread — see `library/generated/nim_ffi_prelude.h`
 * and [DeliveryNative]'s doc comment). [DeliveryNative]'s JNI shim
 * (`delivery_jni.c`) blocks natively until that callback fires, so [start]/
 * [stop]/construction here block the calling Kotlin thread the same way
 * [com.fryorcraken.logos.storage.StorageNode] already does for storage's
 * (also fully async) API — this class deliberately mirrors that one's
 * `blockingCall`/`resultCall` shape for consistency between the two
 * wrapped-library modules.
 */
public class DeliveryNode(configJson: String) : NodeLifecycle {
    private var ctx: Long = 0L
    private val listenerIds = mutableMapOf<String, Long>()

    init {
        val latch = CountDownLatch(1)
        var createRet = -1
        ctx = DeliveryNative.nativeCreate(configJson, NativeCallback { ret, _ ->
            createRet = ret
            latch.countDown()
        })
        check(ctx != 0L) { "logosdelivery_ctx_create failed to allocate a context" }
        awaitOrThrow(latch, "logosdelivery_ctx_create")
        if (createRet != 0) throw LogosException("logosdelivery_ctx_create failed", createRet)
    }

    override var isRunning: Boolean = false
        private set

    override fun start() {
        check(ctx != 0L) { "DeliveryNode already destroyed" }
        blockingCall("logosdelivery_ctx_start_node") { cb -> DeliveryNative.nativeStart(ctx, cb) }
        isRunning = true
    }

    override fun stop() {
        check(ctx != 0L) { "DeliveryNode already destroyed" }
        blockingCall("logosdelivery_ctx_stop_node") { cb -> DeliveryNative.nativeStop(ctx, cb) }
        isRunning = false
    }

    override fun destroy() {
        if (ctx == 0L) return
        listenerIds.values.forEach { DeliveryNative.nativeRemoveEventListener(ctx, it) }
        listenerIds.clear()
        DeliveryNative.nativeDestroy(ctx)
        ctx = 0L
    }

    /**
     * Registers [callback] for [eventName]. Confirmed event names (from
     * `library/README.md` and `library/logos_delivery_api/node_api.nim`'s
     * `registerFFIEventListeners`): `onMessageQueued`, `onMessageSent`,
     * `onMessagePropagated`, `onMessageError`, `onMessageReceived`,
     * `onConnectionStatusChange`, `onTopicHealthChange`,
     * `onConnectionChange` (peer connect/disconnect — this is the one the
     * demo app uses for a live peer-connectivity indicator),
     * `onChannelMessageReceived`, `onChannelMessageSent`,
     * `onChannelMessageError`, `onChannelMessageLost`.
     */
    public fun addEventListener(eventName: String, callback: NativeCallback) {
        check(ctx != 0L) { "DeliveryNode already destroyed" }
        val id = DeliveryNative.nativeAddEventListener(ctx, eventName, callback)
        if (id == 0L) throw LogosException("failed to register listener for $eventName", 0)
        listenerIds[eventName] = id
    }

    private fun blockingCall(opName: String, invoke: (NativeCallback) -> Int) {
        val latch = CountDownLatch(1)
        var ret = -1
        val dispatchRet = invoke(NativeCallback { callerRet, _ ->
            ret = callerRet
            latch.countDown()
        })
        if (dispatchRet != 0) throw LogosException("$opName dispatch failed", dispatchRet)
        awaitOrThrow(latch, opName)
        if (ret != 0) throw LogosException("$opName failed", ret)
    }

    private fun awaitOrThrow(latch: CountDownLatch, opName: String) {
        check(latch.await(30, TimeUnit.SECONDS)) { "$opName timed out waiting for native callback" }
    }
}
