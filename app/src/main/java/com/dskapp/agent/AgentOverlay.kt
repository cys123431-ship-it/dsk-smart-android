package com.dskapp.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class AgentOverlay(private val service: AccessibilityService) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var statusView: TextView? = null
    private var questionView: TextView? = null
    private var actionRow: LinearLayout? = null
    private var resultScroll: ScrollView? = null
    private var confirmContinuation: ((Boolean) -> Unit)? = null
    private var attached = false
    private var stopAction: () -> Unit = {}

    private val windowManager: WindowManager
        get() = service.getSystemService(WindowManager::class.java)

    /** Shows the panel in "running" mode (status + 중지), reusing it if already on screen. */
    fun show(onStop: () -> Unit) {
        runOnMain {
            stopAction = onStop
            if (!attached) {
                val panel = buildPanel()
                root = panel
                val params = windowParams()
                if (params != null) {
                    runCatching { windowManager.addView(panel, params) }
                        .onSuccess { attached = true }
                        .onFailure { root = null }
                }
            }
            showRunning()
        }
    }

    private fun showRunning() {
        statusView?.visibility = View.VISIBLE
        questionView?.visibility = View.GONE
        resultScroll?.visibility = View.GONE
        actionRow?.let { row ->
            row.removeAllViews()
            row.addView(makeButton("중지", 0xFFEF4444.toInt()) { stopAction() })
            row.visibility = View.VISIBLE
        }
    }

    fun setStatus(text: String) {
        runOnMain {
            statusView?.text = text
            statusView?.visibility = View.VISIBLE
        }
    }

    /** Shows the question with 실행 / 취소 buttons and suspends until the user answers (true = run). */
    suspend fun confirm(question: String): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            // A second confirm while one is pending resolves the older one as "cancel".
            confirmContinuation?.invoke(false)
            confirmContinuation = { answer ->
                if (cont.isActive) cont.resume(answer)
            }
            cont.invokeOnCancellation { confirmContinuation = null }
            if (!attached) {
                show(stopAction)
            }
            statusView?.visibility = View.GONE
            questionView?.let { view ->
                view.text = question
                view.visibility = View.VISIBLE
            }
            actionRow?.let { row ->
                row.removeAllViews()
                row.addView(makeButton("실행", 0xFF34D399.toInt()) { resolveConfirm(true) })
                row.addView(makeButton("취소", 0xFFE5E7EB.toInt()) { resolveConfirm(false) })
                row.visibility = View.VISIBLE
            }
        }
    }

    /** Shows a long result text with a 닫기 button (used for "이 화면 요약"). */
    fun showResult(title: String, text: String) {
        runOnMain {
            if (!attached) {
                show(stopAction)
            }
            statusView?.visibility = View.GONE
            questionView?.let { view ->
                view.text = title
                view.visibility = View.VISIBLE
            }
            val scroll = resultScroll ?: return@runOnMain
            val screenHeight = service.resources.displayMetrics.heightPixels
            scroll.layoutParams = scroll.layoutParams?.apply {
                height = (screenHeight / 2).coerceAtLeast(dp(120))
            }
            val content = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
            }
            content.addView(TextView(service).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 13f
            })
            scroll.removeAllViews()
            scroll.addView(content)
            scroll.visibility = View.VISIBLE
            actionRow?.let { row ->
                row.removeAllViews()
                row.addView(makeButton("닫기", 0xFFE5E7EB.toInt()) { hide() })
                row.visibility = View.VISIBLE
            }
        }
    }

    fun hide() {
        runOnMain {
            val panel = root ?: return@runOnMain
            confirmContinuation?.invoke(false)
            confirmContinuation = null
            runCatching { windowManager.removeView(panel) }
            attached = false
            root = null
            statusView = null
            questionView = null
            actionRow = null
            resultScroll = null
        }
    }

    private fun resolveConfirm(answer: Boolean) {
        val continuation = confirmContinuation ?: return
        confirmContinuation = null
        runOnMain { showRunning() }
        continuation(answer)
    }

    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xEE1F2937.toInt())
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val title = TextView(service).apply {
            text = "📱 DSK 폰 조작"
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 15f
        }
        panel.addView(title)

        val status = TextView(service).apply {
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 13f
            maxLines = 3
            setPadding(0, dp(6), 0, dp(6))
        }
        panel.addView(status)
        statusView = status

        val question = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(6))
        }
        panel.addView(question)
        questionView = question

        val scroll = ScrollView(service).apply {
            visibility = View.GONE
            isFillViewport = false
        }
        panel.addView(scroll)
        resultScroll = scroll

        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        panel.addView(row)
        actionRow = row

        return panel
    }

    private fun makeButton(label: String, color: Int, onClick: () -> Unit): Button {
        return Button(service).apply {
            text = label
            setTextColor(color)
            textSize = 13f
            isAllCaps = false
            inputType = InputType.TYPE_NULL
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun windowParams(): WindowManager.LayoutParams? {
        return runCatching {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                x = 0
                y = dp(96)
                horizontalMargin = 0f
            }
        }.getOrNull()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching(block)
        } else {
            mainHandler.post { runCatching(block) }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            service.resources.displayMetrics
        ).toInt()
    }
}
