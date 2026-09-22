package com.fryorcraken.logos.delivery

import com.fryorcraken.logos.common.NativeCallback

/**
 * `external fun` bridge to `libdelivery_jni.so`, the hand-written JNI shim
 * that wraps `liblogosdelivery.so`'s C FFI (see
 * docs/adr/0003-jni-shim-per-module.md for why a shim is required — Nim's
 * exported symbols don't follow JNI naming conventions).
 *
 * Reconciled against the real generated header (Milestone 3):
 * `library/generated/logosdelivery.h`, emitted by nim-ffi's `genBindings()`
 * from `library`'s `.nim` source's `{.ffi.}`-annotated procs — not checked into
 * logos-messaging/logos-delivery, built fresh by
 * `scripts/build-jni-shims.sh` before `delivery_jni.c` is compiled against
 * it. That header's typed helper layer (`logosdelivery_ctx_create`,
 * `_ctx_start_node`, `_ctx_stop_node`, `_ctx_destroy`, ...) is entirely
 * **asynchronous** — every call submits a CBOR-encoded request and returns
 * immediately, with the terminal result delivered later via callback from
 * nim-ffi's dispatch thread. This contradicts Milestone 2's stub, which
 * assumed (per the comment that used to be on [com.fryorcraken.logos.common
 * .NodeLifecycle]) that delivery's node lifecycle was synchronous, unlike
 * storage's. It is not: both wrapped libraries turn out to have fully async
 * C APIs. `delivery_jni.c` blocks the calling thread on a native condvar
 * until each callback fires, so these `external fun`s present the same
 * synchronous-return-plus-callback shape [com.fryorcraken.logos.storage
 * .StorageNative] already established for storage's own async API — every
 * lifecycle call here now also takes a [NativeCallback], matching that
 * pattern.
 */
internal object DeliveryNative {
    init {
        System.loadLibrary("logosdelivery")
        System.loadLibrary("delivery_jni")
    }

    /**
     * Returns an opaque native context pointer (a `LogosDeliveryCtx*`), or 0
     * on failure. [callback] receives the terminal `logosdelivery_ctx_create`
     * result (retCode 0 = success) once the native call completes — the
     * shim blocks until then, so by the time this function returns,
     * [callback] has already been invoked exactly once.
     */
    external fun nativeCreate(configJson: String, callback: NativeCallback): Long

    external fun nativeStart(ctx: Long, callback: NativeCallback): Int

    external fun nativeStop(ctx: Long, callback: NativeCallback): Int

    /** Synchronous: `logosdelivery_ctx_destroy` is a plain blocking C call. */
    external fun nativeDestroy(ctx: Long)

    /** Returns a non-zero listener id (0 = registration failed). */
    external fun nativeAddEventListener(ctx: Long, eventName: String, callback: NativeCallback): Long

    external fun nativeRemoveEventListener(ctx: Long, listenerId: Long): Int
}
