package com.dskapp.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** One element the model can refer to by [index]. */
data class ScreenElement(
    val index: Int,
    val label: String,
    val role: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val isPassword: Boolean,
    val centerX: Int,
    val centerY: Int
)

/** What is on screen now. [nodes] keeps the live node per element index for the executor. */
class CapturedScreen(
    val packageName: String,
    val appLabel: String,
    val elements: List<ScreenElement>,
    internal val nodes: List<AccessibilityNodeInfo>
) {
    /** Text listing for the model, e.g. `[3] 버튼 "보내기" (탭)`. Header line: `앱: {appLabel} ({packageName})`. */
    fun render(maxChars: Int = 8000): String {
        val header = "앱: $appLabel ($packageName)"
        if (elements.isEmpty()) return header + "\n(읽을 수 있는 요소가 없습니다)"
        val builder = StringBuilder(header)
        builder.append('\n')
        var truncated = false
        for (element in elements) {
            val line = buildLine(element)
            val projected = builder.length + 1 + line.length
            // Reserve room for the ellipsis line so the client never gets a dangling half-line.
            if (projected > maxChars) {
                truncated = true
                break
            }
            builder.append(line).append('\n')
        }
        if (truncated) {
            builder.append("… (화면 요소가 더 있습니다)")
        } else if (builder.isNotEmpty() && builder.last() == '\n') {
            builder.setLength(builder.length - 1)
        }
        return builder.toString()
    }

    fun labelOf(index: Int?): String? {
        if (index == null) return null
        return elements.firstOrNull { it.index == index }?.label
    }

    private fun buildLine(element: ScreenElement): String {
        val flags = ArrayList<String>(4)
        if (element.clickable) flags.add("탭")
        if (element.editable) flags.add("입력")
        if (element.scrollable) flags.add("스크롤")
        when (element.checked) {
            true -> flags.add("켜짐")
            false -> flags.add("꺼짐")
            null -> Unit
        }
        val suffix = if (flags.isEmpty()) "" else " (" + flags.joinToString(", ") + ")"
        return "[${element.index}] ${element.role} \"${element.label}\"$suffix"
    }
}

object ScreenReader {
    const val MAX_ELEMENTS = 150
    private const val MAX_DEPTH = 40
    private const val MAX_LABEL_CHARS = 80
    private const val PASSWORD_LABEL = "[비밀번호 칸]"

    fun capture(service: AccessibilityService): CapturedScreen? {
        return runCatching {
            val ownPackage = service.packageName
            val root = findRoot(service, ownPackage) ?: return null
            val rootPackage = root.packageName?.toString()
            if (rootPackage != null && rootPackage == ownPackage) return null

            val elements = ArrayList<ScreenElement>(MAX_ELEMENTS)
            val nodes = ArrayList<AccessibilityNodeInfo>(MAX_ELEMENTS)
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            stack.addLast(root to 0)
            val rect = Rect()

            while (stack.isNotEmpty() && elements.size < MAX_ELEMENTS) {
                val (node, depth) = stack.removeLast()
                if (depth > MAX_DEPTH) continue
                collectNode(service, node, depth, rect, elements, nodes, stack)
            }

            val pkg = rootPackage ?: service.packageName
            CapturedScreen(pkg, appLabelOf(service, pkg), elements.toList(), nodes.toList())
        }.getOrNull()
    }

    private fun findRoot(service: AccessibilityService, ownPackage: String): AccessibilityNodeInfo? {
        val active = service.rootInActiveWindow
        if (active != null && active.packageName?.toString() != ownPackage) return active
        return runCatching {
            service.windows?.firstOrNull { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.root?.packageName?.toString() != ownPackage &&
                    window.root != null
            }?.root
        }.getOrNull()
    }

    private fun collectNode(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        depth: Int,
        rect: Rect,
        elements: MutableList<ScreenElement>,
        nodes: MutableList<AccessibilityNodeInfo>,
        stack: ArrayDeque<Pair<AccessibilityNodeInfo, Int>>
    ) {
        // Children first-in-last-out with reversed order keeps a top-to-bottom, left-to-right read.
        runCatching {
            for (i in node.childCount - 1 downTo 0) {
                val child = node.getChild(i) ?: continue
                stack.addLast(child to depth + 1)
            }
        }

        val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
        if (!visible) return

        val clickable = runCatching { node.isClickable }.getOrDefault(false)
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        val scrollable = runCatching { node.isScrollable }.getOrDefault(false)
        val checkable = runCatching { node.isCheckable }.getOrDefault(false)
        val isPassword = runCatching { node.isPassword }.getOrDefault(false)

        val label = labelOf(node, isPassword)
        if (label.isEmpty() && !clickable && !editable && !scrollable && !checkable) return

        val bounds = runCatching {
            rect.setEmpty()
            node.getBoundsInScreen(rect)
            Rect(rect)
        }.getOrNull() ?: return
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val checked = if (checkable) runCatching { node.isChecked }.getOrNull() else null
        val element = ScreenElement(
            index = elements.size,
            label = label.ifEmpty { "요소 ${elements.size}" },
            role = roleOf(node.className?.toString()),
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            checked = checked,
            isPassword = isPassword,
            centerX = bounds.centerX(),
            centerY = bounds.centerY()
        )
        elements.add(element)
        nodes.add(node)
    }

    private fun labelOf(node: AccessibilityNodeInfo, isPassword: Boolean): String {
        // Password content must never be copied into a prompt.
        if (isPassword) return PASSWORD_LABEL
        val candidates = listOf(
            runCatching { node.text?.toString() }.getOrNull(),
            runCatching { node.contentDescription?.toString() }.getOrNull(),
            runCatching { node.hintText?.toString() }.getOrNull()
        )
        for (candidate in candidates) {
            val normalized = normalize(candidate)
            if (normalized.isNotEmpty()) return normalized
        }
        val viewId = runCatching { node.viewIdResourceName }.getOrNull()
        if (!viewId.isNullOrBlank()) {
            val entry = viewId.substringAfterLast(":id/", viewId).substringAfterLast('/')
            val normalized = normalize(entry)
            if (normalized.isNotEmpty()) return normalized
        }
        return ""
    }

    /** Collapses whitespace so one element always occupies exactly one line. */
    private fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val singleLine = raw.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length > MAX_LABEL_CHARS) singleLine.take(MAX_LABEL_CHARS) else singleLine
    }

    private fun roleOf(className: String?): String {
        if (className.isNullOrBlank()) return "요소"
        return when {
            className.endsWith("Button") && !className.endsWith("ImageButton.Alt") &&
                !className.contains("CheckBox") && !className.contains("RadioButton") &&
                !className.contains("ToggleButton") && !className.contains("Switch") -> "버튼"
            className.contains("ImageButton") -> "버튼"
            className.contains("EditText") -> "입력칸"
            className.contains("CheckBox") || className.contains("RadioButton") -> "체크박스"
            className.contains("Switch") || className.contains("ToggleButton") -> "스위치"
            className.contains("ImageView") -> "이미지"
            className.contains("TextView") -> "글자"
            className.contains("RecyclerView") || className.contains("ListView") ||
                className.contains("ScrollView") || className.contains("HorizontalScrollView") -> "목록"
            className.contains("WebView") -> "웹"
            else -> "요소"
        }
    }

    private fun appLabelOf(service: AccessibilityService, packageName: String): String {
        return runCatching {
            val info = service.packageManager.getApplicationInfo(packageName, 0)
            service.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }
}
