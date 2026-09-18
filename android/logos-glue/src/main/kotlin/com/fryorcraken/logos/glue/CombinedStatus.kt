package com.fryorcraken.logos.glue

import com.fryorcraken.logos.delivery.DeliveryNode
import com.fryorcraken.logos.storage.StorageNode

/**
 * Combined running-state snapshot across a delivery node and a storage
 * node, read together in one call.
 *
 * This is the reference example for how a future multi-library glue module
 * should look once more native libraries (lez-node, lez-wallet, l1-node,
 * l1-wallet) are added: a small, focused module that depends on exactly
 * the N libraries it glues together, contains no native code of its own,
 * and is the ONLY place in the dependency graph where those N libraries'
 * Kotlin APIs are used side by side. An app that wants delivery+storage
 * interaction depends on `logos-glue`; an app that wants only delivery
 * never resolves it, and so never resolves `logos-storage` transitively
 * through it either — see docs/adr/0001 and the root README for why this
 * structural separation, not a build flag, is what keeps a delivery-only
 * APK free of `libstorage.so`.
 *
 * As more libraries are added, prefer additional small glue modules over
 * growing this one into a catch-all (e.g. a future `logos-glue-wallet`
 * pairing lez-wallet + l1-wallet) — the same reasoning that keeps
 * `logos-delivery` and `logos-storage` separate applies one level up.
 */
public data class CombinedStatus(
    public val deliveryRunning: Boolean,
    public val storageRunning: Boolean,
)

public fun getCombinedStatus(delivery: DeliveryNode, storage: StorageNode): CombinedStatus =
    CombinedStatus(
        deliveryRunning = delivery.isRunning,
        storageRunning = storage.isRunning,
    )
