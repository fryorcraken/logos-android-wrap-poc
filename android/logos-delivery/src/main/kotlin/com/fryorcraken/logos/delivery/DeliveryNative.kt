package com.fryorcraken.logos.delivery

import com.fryorcraken.logos.common.NativeCallback

/**
 * `external fun` bridge to `libdelivery_jni.so`, the hand-written JNI shim
 * that wraps `liblogosdelivery.so`'s plain-C exports (see
 * docs/adr/0003-jni-shim-per-module.md for why a shim is required — Nim's
 * exported symbols don't follow JNI naming conventions).
 *
 * STUB (Milestone 2): `libdelivery_jni.so` does not exist yet — it's written
 * in Milestone 3 against the *generated* `generated/logosdelivery.h`
 * (liblogosdelivery.nim generates the bulk of its C API at build time; the
 * checked-in `library/liblogosdelivery.h` only declares the event-listener
 * ABI). Method names/signatures below are provisional: they establish the
 * module's Kotlin surface and package structure now (renaming later means
 * renaming every exported JNI symbol too, see docs/adr/0003 section 5.3),
 * but will be reconciled against the real generated header before the shim
 * is written.
 */
internal object DeliveryNative {
    init {
        System.loadLibrary("logosdelivery")
        System.loadLibrary("delivery_jni")
    }

    /** Returns an opaque native context pointer, or 0 on failure. */
    external fun nativeCreate(configJson: String): Long

    external fun nativeStart(ctx: Long): Int

    external fun nativeStop(ctx: Long): Int

    external fun nativeDestroy(ctx: Long)

    /** Returns a non-zero listener id (0 = registration failed). */
    external fun nativeAddEventListener(ctx: Long, eventName: String, callback: NativeCallback): Long

    external fun nativeRemoveEventListener(ctx: Long, listenerId: Long): Int
}
