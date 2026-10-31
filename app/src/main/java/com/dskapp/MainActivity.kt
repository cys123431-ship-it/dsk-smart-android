package com.dskapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.dskapp.ui.DskAppNavHost
import com.dskapp.ui.theme.DskTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            DskTheme {
                Surface(
                    // Compose handles IME + system bar insets so the composer stays above
                    // the keyboard on both phones and tablets.
                    modifier = Modifier
                        .consumeWindowInsets(WindowInsets.systemBars.union(WindowInsets.ime))
                ) {
                    DskAppNavHost()
                }
            }
        }
    }
}
