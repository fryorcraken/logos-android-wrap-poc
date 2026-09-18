package com.fryorcraken.logos.storage

/**
 * A result/event delivered asynchronously from `libstorage.so` via the JNI
 * shim's callback bridge (see docs/adr/0003-jni-shim-per-module.md).
 *
 * Unlike delivery's callback API, storage's `library/libstorage.h` is fully
 * hand-written and checked into nim-src/logos-storage-nim (not generated at
 * build time), so this shape is taken directly from that header's
 * `StorageCallback` — `void (*)(int callerRet, const char *msg, size_t len,
 * void *userData)` — rather than being a provisional guess.
 */
public data class StorageResult(
    public val retCode: Int,
    public val message: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StorageResult) return false
        return retCode == other.retCode && message.contentEquals(other.message)
    }

    override fun hashCode(): Int {
        var result = retCode
        result = 31 * result + message.contentHashCode()
        return result
    }
}
