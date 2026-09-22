package com.fryorcraken.logos.delivery

/**
 * A delivery-node event, delivered asynchronously from the native event
 * thread via the JNI shim's callback bridge (see
 * docs/adr/0003-jni-shim-per-module.md).
 *
 * [payload] is the raw JSON event body `logosdelivery_add_event_listener`'s
 * callback delivers (its `msg`/`len`), e.g. for `onMessageReceived`:
 * `{"eventType":"message_received","messageHash":"0x...","message":{...},
 * "source":"live"}` — see `library/MESSAGE_EVENTS.md` in
 * nim-src/logos-delivery for the full per-event JSON shapes, and
 * [DeliveryNode.addEventListener]'s doc comment for the confirmed event
 * name set (`onMessageQueued`, `onMessageSent`, `onMessagePropagated`,
 * `onMessageError`, `onMessageReceived`, `onConnectionStatusChange`,
 * `onTopicHealthChange`, `onConnectionChange`, `onChannelMessage*`).
 * [DeliveryNode] itself hands callers a plain
 * [com.fryorcraken.logos.common.NativeCallback] (matching
 * `logos-storage`'s pattern) rather than this type directly — construct a
 * [DeliveryEvent] from a callback's `(retCode, message)` when a named,
 * typed event value is more convenient than the raw bytes, e.g. in UI code
 * that already knows which `eventName` it registered.
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
