package com.olcayertas.tripsplit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.olcayertas.tripsplit.ui.Root
import com.olcayertas.tripsplit.ui.theme.TripSplitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TripSplitApp).container
        setContent {
            TripSplitTheme { Root(container) }
        }
    }
}
