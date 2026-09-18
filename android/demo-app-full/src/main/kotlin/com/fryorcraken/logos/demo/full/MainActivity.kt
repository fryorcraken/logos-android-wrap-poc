package com.fryorcraken.logos.demo.full

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
 * Full demo: starts both a delivery node and a storage node, and displays
 * [com.fryorcraken.logos.glue.getCombinedStatus] — exercising `logos-glue`
 * visibly, not just structurally including it. See the root README's
 * "Demo apps" section for scope (no messaging/upload/download demo).
 *
 * STUB (Milestone 2): node construction will fail with
 * [UnsatisfiedLinkError] until Milestones 3-4 wire the real JNI shims and
 * native libraries. This Activity establishes the demo app's structure and
 * three-way dependency graph now.
 */
public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FullDemoScreen()
        }
    }
}

@Composable
private fun FullDemoScreen() {
    var status by remember { mutableStateOf("Not started") }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Logos Full Demo", style = MaterialTheme.typography.headlineSmall)
                Text(status, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }

    // TODO(Milestone 5): construct DeliveryNode + StorageNode, start() both,
    // register event listeners, and periodically update `status` with
    // getCombinedStatus(delivery, storage) plus live peer counts from each.
    // Left unimplemented in Milestone 2 since neither native module has a
    // real .so to load yet.
}
