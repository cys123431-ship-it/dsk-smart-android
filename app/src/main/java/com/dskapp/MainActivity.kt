package com.dskapp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskapp.data.settings.SettingsRepository.ThemeMode
import com.dskapp.di.ServiceLocator
import com.dskapp.ui.DskAppNavHost
import com.dskapp.ui.lock.AppLock
import com.dskapp.ui.lock.LockScreen
import com.dskapp.ui.share.LaunchInbox
import com.dskapp.ui.theme.DskTheme

class MainActivity : ComponentActivity() {

    /** True while the app lock cover is shown. */
    private var locked by mutableStateOf(false)

    /** When the app last went to the background; drives the re-lock grace period. */
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A recreated activity still carries the original share intent; deliver it once.
        if (savedInstanceState == null) LaunchInbox.offer(intent)

        val settings = ServiceLocator.settings(this)
        locked = settings.state.value.appLock

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

            // With the lock on, the recent-apps thumbnail must not show the chat either.
            LaunchedEffect(appSettings.appLock) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setRecentsScreenshotEnabled(!appSettings.appLock)
                }
                if (!appSettings.appLock) locked = false
            }

            DskTheme(darkTheme = darkTheme, fontScale = appSettings.fontScale) {
                // Edge-to-edge: each screen pads itself for the status bar, navigation bar and
                // keyboard (top bars, the composer, drawers). Consuming the insets here without
                // padding made every bar draw underneath the system bars.
                Surface {
                    DskAppNavHost()
                    if (locked) {
                        // Back on the lock cover leaves the app instead of revealing it.
                        BackHandler { finish() }
                        LockScreen(onUnlock = { AppLock.authenticate(this) { ok -> if (ok) locked = false } })
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val lockOn = ServiceLocator.settings(this).state.value.appLock
        // Coming back from the camera, a file picker or the share sheet within the grace
        // period does not ask again.
        if (lockOn && stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > AppLock.RELOCK_AFTER_MS) {
            locked = true
        }
    }

    /** Android 8–9 unlock result from the system credential screen. */
    @Deprecated("Only used for the pre-Android 10 credential screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AppLock.CREDENTIAL_REQUEST && resultCode == Activity.RESULT_OK) locked = false
    }

    /** singleTask: shares arriving while the app is open land here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LaunchInbox.offer(intent)
    }
}
