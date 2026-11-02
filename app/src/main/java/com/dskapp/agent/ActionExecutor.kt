package com.dskapp.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ActionExecutor(private val service: AccessibilityService) {

    /** Runs one validated action. Returns a short Korean description of what happened. */
    suspend fun execute(action: AgentAction, screen: CapturedScreen?): Result<String> = runCatching {
        when (action.action) {
            "tap" -> tap(action, screen)
            "long_press" -> longPress(action, screen)
            "type" -> type(action, screen)
            "scroll" -> scroll(action, screen)
            "swipe" -> swipe(action)
            "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "뒤로")
            "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "홈 화면")
            "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "최근 앱")
            "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "알림창")
            "open_app" -> openApp(action)
            "wait" -> waitAction(action)
            "done" -> action.text ?: ""
            "ask" -> action.text ?: ""
            else -> error("알 수 없는 동작입니다: ${action.action}")
        }
    }

    private fun nodeFor(action: AgentAction, screen: CapturedScreen?): AccessibilityNodeInfo? {
        val target = action.target ?: return null
        val node = screen?.nodes?.getOrNull(target) ?: return null
        return node
    }

    /** Walks up to the closest clickable ancestor so tapping a label still works. */
    private fun clickableAncestor(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = start
        var levels = 0
        while (current != null && levels <= 6) {
            if (runCatching { current!!.isClickable }.getOrDefault(false)) return current
            current = runCatching { current!!.parent }.getOrNull()
            levels++
        }
        return null
    }

    private fun scrollableAncestor(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = start
        var levels = 0
        while (current != null && levels <= 6) {
            if (runCatching { current!!.isScrollable }.getOrDefault(false)) return current
            current = runCatching { current!!.parent }.getOrNull()
            levels++
        }
        return null
    }

    private suspend fun tap(action: AgentAction, screen: CapturedScreen?): String {
        val node = nodeFor(action, screen)
        val label = screen?.labelOf(action.target) ?: "요소"
        if (node != null) {
            val target = clickableAncestor(node)
            val clicked = target != null &&
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            if (clicked) return "탭: $label"
        }
        val element = screen?.elements?.firstOrNull { it.index == action.target }
        if (element != null && gestureAt(element.centerX, element.centerY, 60)) {
            return "탭: $label"
        }
        error("화면이 바뀌어 요소를 찾지 못했습니다.")
    }

    private suspend fun longPress(action: AgentAction, screen: CapturedScreen?): String {
        val node = nodeFor(action, screen)
        val label = screen?.labelOf(action.target) ?: "요소"
        if (node != null) {
            val target = clickableAncestor(node)
            val clicked = target != null &&
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)
            if (clicked) return "길게 누름: $label"
        }
        val element = screen?.elements?.firstOrNull { it.index == action.target }
        if (element != null && gestureAt(element.centerX, element.centerY, 700)) {
            return "길게 누름: $label"
        }
        error("화면이 바뀌어 요소를 찾지 못했습니다.")
    }

    private fun type(action: AgentAction, screen: CapturedScreen?): String {
        val node = nodeFor(action, screen) ?: error("화면이 바뀌어 요소를 찾지 못했습니다.")
        val label = screen?.labelOf(action.target) ?: "요소"
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        if (!editable) error("화면이 바뀌어 요소를 찾지 못했습니다.")
        val password = runCatching { node.isPassword }.getOrDefault(false)
        if (password) error("비밀번호 칸에는 입력하지 않습니다.")
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                action.text ?: ""
            )
        }
        val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
        if (!ok) error("화면이 바뀌어 요소를 찾지 못했습니다.")
        return "입력: $label"
    }

    private suspend fun scroll(action: AgentAction, screen: CapturedScreen?): String {
        val direction = action.direction ?: "down"
        val node = nodeFor(action, screen)
        if (node != null) {
            val target = if (runCatching { node.isScrollable }.getOrDefault(false))
                node else scrollableAncestor(node)
            if (target != null) {
                val scrollAction = if (direction == "up" || direction == "left")
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                if (runCatching { target.performAction(scrollAction) }.getOrDefault(false)) {
                    return "스크롤: $direction"
                }
            }
        }
        swipeGesture(direction)
        return "스크롤: $direction"
    }

    private suspend fun swipe(action: AgentAction): String {
        val direction = action.direction ?: "up"
        swipeGesture(direction)
        return "밀기: $direction"
    }

    private suspend fun swipeGesture(direction: String) {
        val metrics = service.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val path = Path()
        when (direction) {
            "up" -> {
                path.moveTo(w / 2f, h * 0.3f)
                path.lineTo(w / 2f, h * 0.7f)
            }
            "left" -> {
                path.moveTo(w * 0.7f, h / 2f)
                path.lineTo(w * 0.3f, h / 2f)
            }
            "right" -> {
                path.moveTo(w * 0.3f, h / 2f)
                path.lineTo(w * 0.7f, h / 2f)
            }
            else -> {
                path.moveTo(w / 2f, h * 0.7f)
                path.lineTo(w / 2f, h * 0.3f)
            }
        }
        dispatchGesture(path, 400)
    }

    private suspend fun gestureAt(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchGesture(path, durationMs)
    }

    private suspend fun dispatchGesture(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val description = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = runCatching {
                service.dispatchGesture(description, callback, null)
            }.getOrDefault(false)
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    private fun global(action: Int, label: String): String {
        val ok = runCatching { service.performGlobalAction(action) }.getOrDefault(false)
        if (!ok) error("화면이 바뀌어 요소를 찾지 못했습니다.")
        return label
    }

    private fun openApp(action: AgentAction): String {
        val query = action.text?.trim().orEmpty()
        if (query.isEmpty()) error("열 앱 이름이 없습니다.")
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching {
            service.packageManager.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())
        val resolved = activities.mapNotNull { info ->
            runCatching {
                val label = service.packageManager.getApplicationLabel(info.activityInfo.applicationInfo).toString()
                val pkg = info.activityInfo.packageName
                Triple(label, pkg, normalizeName(label))
            }.getOrNull()
        }
        val normalizedQuery = normalizeName(query)
        val exact = resolved.firstOrNull { it.third == normalizedQuery }
        val contains = resolved.firstOrNull { it.third.contains(normalizedQuery) }
        val contained = resolved.firstOrNull { normalizedQuery.contains(it.third) && it.third.isNotEmpty() }
        val match = exact ?: contains ?: contained
            ?: error("‘$query’ 앱을 찾지 못했습니다.")
        val launchIntent = runCatching {
            service.packageManager.getLaunchIntentForPackage(match.second)
        }.getOrNull() ?: error("‘$query’ 앱을 찾지 못했습니다.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { service.startActivity(launchIntent) }
            .getOrElse { error("‘$query’ 앱을 찾지 못했습니다.") }
        return "앱 열기: ${match.first}"
    }

    /** Lowercase without spaces so "카카오 톡" still matches "카카오톡". */
    private fun normalizeName(raw: String): String = raw.lowercase().replace(" ", "").trim()

    private suspend fun waitAction(action: AgentAction): String {
        val requested = action.text?.toLongOrNull() ?: 1500L
        delay(requested.coerceIn(300L, 5000L))
        return "기다림"
    }
}
