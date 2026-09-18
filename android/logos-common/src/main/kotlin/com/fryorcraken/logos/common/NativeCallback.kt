package com.fryorcraken.logos.common

/**
 * Common shape of a result delivered from native code back into Kotlin.
 *
 * Mirrors the C callback signature both wrapped libraries share —
 * `liblogosdelivery.h`'s `FFICallBack` and `libstorage.h`'s
 * `StorageCallback` are both `void (*)(int callerRet, const char *msg,
 * size_t len, void *userData)`. The JNI shim in each native-lib module
 * (`logos-delivery`, `logos-storage`) is what actually bridges a C function
 * pointer to a call into an implementation of this interface — see each
 * module's `src/jni/*.c` and docs/adr/0003-jni-shim-per-module.md for how
 * that bridging handles the native library's own background event thread.
 *
 * `retCode` follows each library's own RET_* convention (0 = OK by
 * convention in both, but the exact non-zero codes differ per library —
 * this type does not normalize them, callers interpret `retCode` against
 * the library they're calling).
 */
public fun interface NativeCallback {
    public fun onResult(retCode: Int, message: ByteArray)
}
