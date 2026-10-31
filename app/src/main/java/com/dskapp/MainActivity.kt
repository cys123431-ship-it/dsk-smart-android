package com.dskapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskapp.data.settings.SettingsRepository.ThemeMode
import com.dskapp.di.ServiceLocator
import com.dskapp.ui.DskAppNavHost
import com.dskapp.ui.share.ShareInbox
import com.dskapp.ui.theme.DskTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A recreated activity still carries the original share intent; deliver it once.
        if (savedInstanceState == null) ShareInbox.offer(intent)

        val settings = ServiceLocator.settings(this)

        setContent {
            val appSettings by settings.state.collectAsStateWithLifecycle()
            val darkTheme = when (appSettings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Status/navigation bar icons must follow the app theme, not only the system one.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                )
                onDispose { }
            }

            DskTheme(darkTheme = darkTheme, fontScale = appSettings.fontScale) {
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

    /** singleTask: shares arriving while the app is open land here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ShareInbox.offer(intent)
    }
}
