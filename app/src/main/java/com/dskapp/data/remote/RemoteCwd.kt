package com.dskapp.data.remote

/**
 * Picks the working folder sent with `session/new` / `session/load`.
 *
 * goose rejects anything that is not an absolute path on the PC ("Invalid params: cwd must be
 * an absolute path"), and the phone has no idea what the PC's folders are called. The app
 * used to send "~" when the setting was blank, so every new remote chat failed even though
 * "연결 테스트" (which never opens a session) passed. The rules now:
 *
 *  1. reopening a session uses that session's own folder, as goose listed it - a session
 *     started in another project must not be reopened in the folder from Settings;
 *  2. otherwise a path the user typed in Settings, if it is absolute;
 *  3. otherwise the folder of the PC's most recent session - in practice the DSK Remote
 *     workspace, since that is where goose serve starts;
 *  4. nothing usable: null, and the caller asks the user to type a folder.
 *
 * Pure, so the rules are tested without a socket.
 */
object RemoteCwd {

    /** Absolute on Windows (C:\ or C:/, \\server\share) or on a Unix-like PC (/home/me). */
    fun isAbsolute(path: String): Boolean {
        val p = path.trim()
        return WINDOWS_DRIVE.matches(p) || p.startsWith("\\\\") || p.startsWith("/")
    }

    fun resolve(
        configured: String,
        sessions: List<AcpSessionSummary>,
        loadSessionId: String? = null
    ): String? {
        if (loadSessionId != null) {
            sessions.firstOrNull { it.sessionId == loadSessionId }?.cwd
                ?.takeIf { isAbsolute(it) }
                ?.let { return it }
        }
        val typed = configured.trim()
        if (typed.isNotEmpty() && isAbsolute(typed)) return typed
        // session/list comes back newest first.
        return sessions.firstOrNull { isAbsolute(it.cwd) }?.cwd
    }

    /** Shown when [resolve] finds nothing; [configured] is what Settings holds now. */
    fun missingMessage(configured: String): String {
        val typed = configured.trim()
        return if (typed.isNotEmpty()) {
            "작업 폴더 '$typed'은(는) PC의 전체 경로가 아니라서 goose가 받지 않습니다. " +
                "설정 > PC goose 원격 > 작업 폴더에 C:\\Users\\이름\\폴더 처럼 전체 경로를 넣어 주세요."
        } else {
            "PC의 작업 폴더를 알 수 없습니다. 설정 > PC goose 원격 > 작업 폴더에 " +
                "C:\\Users\\이름\\폴더 처럼 PC의 전체 경로를 넣어 주세요."
        }
    }

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/].*")
}
