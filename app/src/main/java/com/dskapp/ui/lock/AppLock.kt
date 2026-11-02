package com.dskapp.ui.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Fingerprint / face / screen-lock check for the optional app lock.
 *
 * Uses the platform BiometricPrompt instead of androidx.biometric: the AndroidX library
 * would drag in Fragments and require a FragmentActivity for one dialog. Android 11+ accepts
 * biometrics or the device PIN in one prompt; Android 10 does the same through the older
 * flag; Android 8–9 fall back to the system credential screen ([CREDENTIAL_REQUEST]).
 */
object AppLock {

    /** Request code for the pre-Android 10 credential screen, handled by MainActivity. */
    const val CREDENTIAL_REQUEST = 4201

    /** Grace period: switching to the camera or a picker and back does not lock again. */
    const val RELOCK_AFTER_MS = 60_000L

    /** The lock can only be turned on when the phone itself has a PIN, pattern or password. */
    fun isDeviceSecure(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguard.isDeviceSecure
    }

    /**
     * Shows the system unlock prompt; [onResult] is called with true on success. On Android
     * 8–9 the result arrives later through [Activity.onActivityResult] with [CREDENTIAL_REQUEST].
     */
    fun authenticate(activity: Activity, onResult: (Boolean) -> Unit) {
        if (!isDeviceSecure(activity)) {
            // Nothing to check against; never lock the user out of their own data.
            onResult(true)
            return
        }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> prompt(activity, onResult) {
                setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> prompt(activity, onResult) {
                @Suppress("DEPRECATION")
                setDeviceCredentialAllowed(true)
            }
            else -> legacyCredentialScreen(activity, onResult)
        }
    }

    private fun prompt(
        activity: Activity,
        onResult: (Boolean) -> Unit,
        configure: BiometricPrompt.Builder.() -> Unit
    ) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("DSK 스마트 잠금 해제")
            .setSubtitle("지문, 얼굴 또는 휴대폰 잠금으로 확인합니다")
            .apply(configure)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    onResult(false)
                }
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun legacyCredentialScreen(activity: Activity, onResult: (Boolean) -> Unit) {
        val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = keyguard.createConfirmDeviceCredentialIntent("DSK 스마트 잠금 해제", null)
        if (intent == null) {
            onResult(true)
        } else {
            activity.startActivityForResult(intent, CREDENTIAL_REQUEST)
        }
    }
}

/**
 * Opaque cover shown while the app is locked. It asks once automatically and offers a
 * button in case the prompt was dismissed.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "DSK 스마트가 잠겨 있어요",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onUnlock) { Text("잠금 해제") }
        }
    }
}
