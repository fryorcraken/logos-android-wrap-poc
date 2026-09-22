package com.fryorcraken.logos.demo.delivery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fryorcraken.logos.common.LogosException
import com.fryorcraken.logos.common.NativeCallback
import com.fryorcraken.logos.delivery.DeliveryNode
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * Delivery-only demo: starts a Logos Messaging node and shows whether it's
 * running and connecting to peers. Deliberately does not demo message
 * send/receive — see the root README's "Demo apps" section for scope.
 *
 * Milestone 3: [DeliveryNode] construction, [DeliveryNode.start], and event
 * registration now use the real JNI shim (`delivery_jni.c`) and native
 * libraries. Node construction and [DeliveryNode.start] both block their
 * calling thread on a native condvar until the corresponding async native
 * call completes (see [DeliveryNode]'s doc comment), so both run on a plain
 * background thread here, never the main/UI thread.
 *
 * Uses the `logos.dev` network preset (see `library/README.md` in
 * nim-src/logos-delivery for the full preset table) — a real dev network,
 * not a solo/offline node, so this demo needs network access and may take
 * a few seconds to find its first peer.
 */
public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeliveryDemoScreen()
        }
    }
}

@Composable
private fun DeliveryDemoScreen() {
    var status by remember { mutableStateOf("Not started") }

    DisposableEffect(Unit) {
        val connectedPeers = AtomicInteger(0)
        @Volatile var node: DeliveryNode? = null
        var stopped = false

        val worker = Thread {
            try {
                status = "Creating node..."
                // mode defaults to "Core"; logos.dev is a real dev network
                // with RLN off, auto-sharded, so a fresh node needs no
                // extra config to find peers.
                val configJson = """{"preset": "logos.dev"}"""
                val newNode = DeliveryNode(configJson)
                if (stopped) {
                    // onDispose already fired before create finished.
                    runCatching { newNode.destroy() }
                    return@Thread
                }
                node = newNode

                newNode.addEventListener(
                    "onConnectionChange",
                    NativeCallback { retCode, message ->
                        if (retCode != 0) return@NativeCallback
                        // Payload per library/logos_delivery_api/node_api.nim's
                        // WakuPeerEvent listener: {"eventType":"connection_change",
                        // "peerId":"...","peerEvent":"EventConnected"|
                        // "EventDisconnected"|"EventIdentified"|
                        // "EventMetadataUpdated"} -- the exact `$` string form of
                        // WakuPeerEventKind (logos_delivery/waku/api/events/
                        // peer_events.nim), a {.pure.} enum, so `$kind` is the bare
                        // identifier. Only Connected/Disconnected drive the demo's
                        // peer count; Identified/MetadataUpdated fire for an
                        // already-counted peer and are ignored here.
                        val peerEvent = runCatching {
                            JSONObject(String(message, Charsets.UTF_8)).optString("peerEvent")
                        }.getOrNull()
                        val count = when (peerEvent) {
                            "EventConnected" -> connectedPeers.incrementAndGet()
                            "EventDisconnected" -> connectedPeers.updateAndGet { if (it > 0) it - 1 else 0 }
                            else -> return@NativeCallback
                        }
                        status = "Running — $count peer(s) connected"
                    },
                )

                status = "Starting node..."
                newNode.start()
                status = "Running — 0 peer(s) connected"
            } catch (e: LogosException) {
                status = "Failed: ${e.message} (code ${e.nativeCode})"
            } catch (e: UnsatisfiedLinkError) {
                status = "Native library not loaded: ${e.message}"
            }
        }
        worker.name = "delivery-demo-node"
        worker.start()

        onDispose {
            stopped = true
            node?.let { current -> Thread { runCatching { current.destroy() } }.start() }
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Logos Delivery Demo", style = MaterialTheme.typography.headlineSmall)
                Text(status, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}
