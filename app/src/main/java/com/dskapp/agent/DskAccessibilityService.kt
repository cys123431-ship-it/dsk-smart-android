package com.dskapp.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.content.Intent
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class DskAccessibilityService : AccessibilityService() {

    val overlay: AgentOverlay by lazy { AgentOverlay(this) }
    val executor: ActionExecutor by lazy { ActionExecutor(this) }

    /** Coroutine scope that lives as long as the service (agent runs survive the app going to background). */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentBridge.service = this
        // Stage 1: the accessibility button summarises the current screen (read-only).
        runCatching {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) {
                        AgentBridge.onAccessibilityButton(this@DskAccessibilityService)
                    }
                }
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (AgentBridge.service === this) {
            AgentBridge.service = null
        }
        runCatching { overlay.hide() }
        scope.cancel()
        return super.onUnbind(intent)
    }

    /** Screenshot as JPEG (quality 80, long edge max 1280) on API 30+, else null. */
    suspend fun screenshotJpeg(): ByteArray? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return runCatching<ByteArray?> {
            val result = suspendCancellableCoroutine<ScreenshotResult?> { cont ->
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            if (cont.isActive) cont.resume(screenshot)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } ?: return null
            val hardwareBuffer = result.hardwareBuffer
            try {
                val wrapped = runCatching {
                    Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                }.getOrNull() ?: return null
                val software = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                wrapped.recycle()
                if (software == null) return null
                val scaled = scaleDown(software, 1280)
                if (scaled !== software) software.recycle()
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                scaled.recycle()
                out.toByteArray()
            } finally {
                runCatching { hardwareBuffer.close() }
            }
        }.getOrNull()
    }

    /** Scales so the longest edge is at most [maxEdge]; returns [source] when it already fits. */
    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val width = source.width
        val height = source.height
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return source
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }
}
