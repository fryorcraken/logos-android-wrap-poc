package com.fryorcraken.logos.storage

import com.fryorcraken.logos.common.NativeCallback

/**
 * `external fun` bridge to `libstorage_jni.so`, the hand-written JNI shim
 * wrapping `libstorage.so`'s plain-C exports declared in
 * `library/libstorage.h` (nim-src/logos-storage-nim) — see
 * docs/adr/0003-jni-shim-per-module.md for why a shim is required.
 *
 * STUB (Milestone 2): `libstorage_jni.so` does not exist yet (written in
 * Milestone 4). Method names below map 1:1 to `libstorage.h`'s
 * `storage_new`/`storage_start`/`storage_stop`/`storage_destroy`/
 * `storage_connect`/`storage_peer_id`/`storage_peer_debug`/`storage_debug`,
 * which — unlike delivery's header — are fully declared in a checked-in,
 * hand-written header, so these signatures are not provisional guesses.
 */
internal object StorageNative {
    init {
        System.loadLibrary("storage")
        System.loadLibrary("storage_jni")
    }

    /** Returns an opaque native context pointer, or 0 on failure. */
    external fun nativeNew(configJson: String, callback: NativeCallback): Long

    external fun nativeStart(ctx: Long, callback: NativeCallback): Int

    external fun nativeStop(ctx: Long, callback: NativeCallback): Int

    external fun nativeClose(ctx: Long, callback: NativeCallback): Int

    external fun nativeDestroy(ctx: Long): Int

    external fun nativeConnect(
        ctx: Long,
        peerId: String?,
        peerAddresses: Array<String>,
        callback: NativeCallback,
    ): Int

    external fun nativePeerId(ctx: Long, callback: NativeCallback): Int

    external fun nativePeerDebug(ctx: Long, peerId: String, callback: NativeCallback): Int

    external fun nativeDebug(ctx: Long, callback: NativeCallback): Int
}
