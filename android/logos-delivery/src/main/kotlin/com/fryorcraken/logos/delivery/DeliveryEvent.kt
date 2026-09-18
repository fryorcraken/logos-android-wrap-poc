package com.fryorcraken.logos.delivery

/**
 * A delivery-node event, delivered asynchronously from the native event
 * thread via the JNI shim's callback bridge (see
 * docs/adr/0003-jni-shim-per-module.md).
 *
 * STUB (Milestone 2): the real event name set and payload shapes are
 * defined in `generated/logosdelivery.h`, which liblogosdelivery.nim
 * generates at build time from `{.ffi.}`-annotated Nim procs — it is not
 * checked into logos-messaging/logos-delivery's repo. This type will be
 * filled in once nim-src/logos-delivery has been built once (Milestone 3)
 * and the generated header's actual event names/payloads can be read.
 */
public data class DeliveryEvent(
    public val name: String,
    public val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeliveryEvent) return false
        return name == other.name && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
