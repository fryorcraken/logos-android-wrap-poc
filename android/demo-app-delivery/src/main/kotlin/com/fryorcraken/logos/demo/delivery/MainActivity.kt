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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Delivery-only demo: starts a Logos Messaging node and shows whether it's
 * running and connecting to peers. Deliberately does not demo message
 * send/receive — see the root README's "Demo apps" section for scope.
 *
 * STUB (Milestone 2): [DeliveryNode] construction will fail with
 * [UnsatisfiedLinkError] until Milestone 3 wires the real JNI shim and
 * native libraries. This Activity establishes the demo app's structure and
 * dependency graph now.
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

    // TODO(Milestone 3): construct DeliveryNode(configJson), start() it,
    // register a peer-connection event listener via addEventListener, and
    // update `status` with a live peer count as events arrive. Left
    // unimplemented in Milestone 2 since DeliveryNative has no real .so to
    // load yet.
}
