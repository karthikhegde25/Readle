package com.readle.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.readle.app.ui.LocalAppContainer
import com.readle.app.ui.ReadleRoot

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch carries a document to open; a recreated activity must not reopen it.
        if (savedInstanceState == null) incoming = intent
        val container = (application as ReadleApp).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                ReadleRoot(incoming = incoming, onIncomingHandled = { incoming = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = intent
    }
}
