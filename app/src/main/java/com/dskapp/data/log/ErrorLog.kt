package com.dskapp.data.log

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Small append-only error log in app storage so the user can copy it and send it for
 * debugging (the developer cannot see the phone). Never contains API keys.
 *
 * Every file access is guarded so that logging failures can never crash the app.
 */
object ErrorLog {

    /** Keeps the log file small enough to be shareable while retaining recent history. */
    private const val MAX_CHARS = 200_000

    /** The secret-bearing patterns are compiled once; these run on every record call. */
    private val REDACTIONS: List<Pair<Regex, String>> = listOf(
        Regex("rv_[A-Za-z0-9_\\-]{6,}") to "***",
        Regex("sk-[A-Za-z0-9_\\-]{10,}") to "***",
        Regex("Bearer\\s+[A-Za-z0-9._\\-]+") to "Bearer ***",
        Regex("(api[_-]?key|key|token|password)=([^&\\s\"]+)") to "$1=***"
    )

    private var logFile: File? = null
    private var header: String = ""
    private var crashHandlerInstalled = false

    /** Remembers the file and device header, and installs the crash handler exactly once. */
    @Synchronized
    fun init(context: Context) {
        if (crashHandlerInstalled) return
        val app = context.applicationContext
        runCatching {
            val dir = File(app.filesDir, "logs")
            dir.mkdirs()
            logFile = File(dir, "errors.log")
        }
        runCatching {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
            header = "DSK 스마트 $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL}\n\n"
        }
        installCrashHandler()
        crashHandlerInstalled = true
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                record("crash", "앱이 비정상 종료되었습니다: ${error.javaClass.simpleName}", error)
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
                runCatching { System.exit(10) }
            }
        }
    }

    /** Appends one entry; trims the file when it grows past [MAX_CHARS]. */
    @Synchronized
    fun record(where: String, message: String, error: Throwable? = null) {
        val file = logFile ?: return
        val safeMessage = redact(message)
        val safeStack = error?.let { redact(Log.getStackTraceString(it)) }
        val entry = formatEntry(now(), where, safeMessage, safeStack)
        runCatching {
            file.appendText(entry)
            if (file.length() > MAX_CHARS) {
                file.writeText(trimToLimit(file.readText(), MAX_CHARS))
            }
        }
        runCatching { Log.w("DSK", entry) }
    }

    /** Device header plus entries, or "" when nothing was logged yet. */
    @Synchronized
    fun read(): String {
        val file = logFile ?: return ""
        val body = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        if (body.isEmpty()) return ""
        return header + body
    }

    @Synchronized
    fun clear() {
        val file = logFile ?: return
        runCatching { file.delete() }
    }

    @Synchronized
    fun entryCount(): Int {
        val file = logFile ?: return 0
        val body = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        return body.lineSequence().count { it.startsWith("=== ") }
    }

    @Synchronized
    fun lastEntryTime(): String? {
        val file = logFile ?: return null
        val body = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        val headerLine = body.lineSequence().lastOrNull { it.startsWith("=== ") } ?: return null
        return parseTime(headerLine)
    }

    /** Extracts the timestamp between "=== " and " [" of a header line. */
    private fun parseTime(line: String): String? {
        val start = line.indexOf("=== ").takeIf { it == 0 } ?: return null
        val end = line.indexOf(" [")
        if (end <= start + 4) return null
        return line.substring(start + 4, end)
    }

    internal fun redact(text: String): String {
        var result = text
        for ((regex, replacement) in REDACTIONS) {
            result = result.replace(regex, replacement)
        }
        return result
    }

    /** Cuts down to the newest [maxChars], starting at an entry header so no entry is halved. */
    internal fun trimToLimit(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val tail = text.substring(text.length - maxChars)
        val headerIndex = tail.indexOf("=== ")
        return if (headerIndex >= 0) tail.substring(headerIndex) else tail
    }

    internal fun formatEntry(time: String, where: String, message: String, stack: String?): String {
        return "=== $time [$where]\n$message\n" + (stack?.let { it.trimEnd() + "\n" } ?: "")
    }

    private fun now(): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
        format.timeZone = TimeZone.getTimeZone("Asia/Seoul")
        return format.format(Date()) + " KST"
    }
}
