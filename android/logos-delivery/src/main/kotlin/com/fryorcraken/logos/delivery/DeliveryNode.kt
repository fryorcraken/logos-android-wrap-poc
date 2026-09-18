package com.fryorcraken.logos.delivery

import com.fryorcraken.logos.common.LogosException
import com.fryorcraken.logos.common.NativeCallback
import com.fryorcraken.logos.common.NodeLifecycle

/**
 * Idiomatic Kotlin API for a Logos Messaging (delivery) node.
 *
 * STUB (Milestone 2): wraps [DeliveryNative], which is itself a stub until
 * Milestone 3 wires the real JNI shim and native libraries. Constructing
 * and using this class will fail with [UnsatisfiedLinkError] until then —
 * that's expected at this stage; Milestone 2's goal is proving the Gradle
 * module graph and Kotlin API surface, not a working native call.
 */
public class DeliveryNode(configJson: String) : NodeLifecycle {
    private var ctx: Long = DeliveryNative.nativeCreate(configJson)
    private val listenerIds = mutableMapOf<String, Long>()

    override var isRunning: Boolean = false
        private set

    override fun start() {
        check(ctx != 0L) { "DeliveryNode already destroyed" }
        val ret = DeliveryNative.nativeStart(ctx)
        if (ret != 0) throw LogosException("logosdelivery_start failed", ret)
        isRunning = true
    }

    override fun stop() {
        check(ctx != 0L) { "DeliveryNode already destroyed" }
        val ret = DeliveryNative.nativeStop(ctx)
        if (ret != 0) throw LogosException("logosdelivery_stop failed", ret)
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
     * Registers [callback] for [eventName]. See `library/MESSAGE_EVENTS.md`
     * in nim-src/logos-delivery for the event names this library emits —
     * TODO(Milestone 3): the connectivity/peer-count event name used by the
     * demo app needs confirming against the generated header, not guessed.
     */
    public fun addEventListener(eventName: String, callback: NativeCallback) {
        check(ctx != 0L) { "DeliveryNode already destroyed" }
        val id = DeliveryNative.nativeAddEventListener(ctx, eventName, callback)
        if (id == 0L) throw LogosException("failed to register listener for $eventName", 0)
        listenerIds[eventName] = id
    }
}
