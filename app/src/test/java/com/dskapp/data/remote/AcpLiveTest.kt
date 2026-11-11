package com.dskapp.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * Runs the app's real [AcpClient] against a real goose on this machine.
 *
 * Off by default: it needs DSK Remote running on the PC and it spends a few model tokens, so
 * it only runs when DSK_ACP_LIVE_SETTINGS points at the PC's settings.txt (the Secret Key is
 * read from that file, never from the command line). Without the variable every test here is
 * skipped and the normal build is unaffected.
 *
 *     set DSK_ACP_LIVE_SETTINGS=C:\path\to\dsk-remote\settings.txt
 *     gradlew testDebugUnitTest --tests "com.dskapp.data.remote.AcpLiveTest"
 *
 * Why it exists: the parsing code was once written from guesses, unit-tested against the same
 * guesses, and shipped for several versions without ever exchanging a frame with goose. These
 * tests are the missing check - what the phone sends is what goose accepts, and what goose
 * sends is what the phone understands.
 */
class AcpLiveTest {

    private val settingsPath: String? = System.getenv("DSK_ACP_LIVE_SETTINGS")
    private val url: String = System.getenv("DSK_ACP_LIVE_URL") ?: "ws://127.0.0.1:3284/acp"

    /** Optional per-session model, for when the PC's default model is unavailable. */
    private val model: String? = System.getenv("DSK_ACP_LIVE_MODEL")?.takeIf { it.isNotBlank() }

    private suspend fun AcpClient.useTestModel(sessionId: String) {
        model?.let { setConfigOption(sessionId, "model", it) }
    }

    @Before
    fun onlyWhenAskedFor() {
        assumeTrue("live goose test is off (DSK_ACP_LIVE_SETTINGS not set)", !settingsPath.isNullOrBlank())
        assumeTrue("settings file not found", File(settingsPath!!).isFile)
    }

    private fun secret(): String =
        File(settingsPath!!).readLines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("SECRET", ignoreCase = true) && it.contains('=') }
            ?.substringAfter('=')?.trim()
            .orEmpty()

    private fun workspace(): String =
        File(settingsPath!!).absoluteFile.parentFile.resolve("workspace").apply { mkdirs() }.absolutePath

    /** Collects every event; permission requests are answered with [answer]. */
    private class Session(val client: AcpClient) {
        val events: MutableList<AcpEvent> = Collections.synchronizedList(mutableListOf())
        fun updates(): List<AcpUpdate> = synchronized(events) { events.filterIsInstance<AcpEvent.Update>().map { it.update } }
        fun requests(): List<AcpPermissionRequest> =
            synchronized(events) { events.filterIsInstance<AcpEvent.PermissionRequest>().map { it.request } }
    }

    @Test
    fun approveModeAsksEveryTimeAndAllowOnceRunsTheTool() = runBlocking {
        withTimeout(240_000) {
            val session = Session(AcpClient(OkHttpClient(), url, secret()))
            val client = session.client
            val collector = launch(Dispatchers.Default) {
                client.events.collect { event ->
                    session.events += event
                    if (event is AcpEvent.PermissionRequest) {
                        // What the app's "이번만 승인" button sends.
                        client.respondPermission(event.request.requestId, event.request.allowOnceId)
                    }
                }
            }
            try {
                client.connect()
                val init = client.initialize()
                assertEquals("goose", init.agentName)
                assertTrue("goose must support session/load", init.canLoadSession)
                assertTrue("goose must support session/list", init.canListSessions)

                val opened = client.newSession(workspace())
                assertTrue("session/new must return an id", opened.sessionId.isNotBlank())
                println("LIVE session id: ${opened.sessionId}")
                assertTrue("approve mode must be offered", opened.modes.any { it.id == "approve" })

                client.useTestModel(opened.sessionId)
                // The mode from Settings is applied with session/set_mode.
                client.setMode(opened.sessionId, "approve")

                val stop = client.prompt(
                    opened.sessionId,
                    "Use your shell tool to run exactly this command: echo dsk-live-test . Then reply with the single word DONE."
                )
                assertEquals("end_turn", stop)
                delay(500)

                val updates = session.updates()
                assertTrue(
                    "set_mode must be confirmed by a current_mode_update",
                    updates.any { it.kind == "current_mode_update" && it.text == "approve" }
                )
                val firstRequests = session.requests()
                assertEquals("approve mode must ask once for the one tool call", 1, firstRequests.size)
                assertEquals("allow_once", firstRequests[0].allowOnceId)
                assertEquals("reject_once", firstRequests[0].rejectOnceId)
                assertTrue("the command must be shown to the user", firstRequests[0].detail.contains("dsk-live-test"))

                val tools = updates.mapNotNull { it.toolCall }
                assertTrue("the tool call must be announced with a title", tools.any { it.title.isNotBlank() })
                assertTrue("the allowed tool must complete", tools.any { it.status == "completed" })
                assertFalse("an allow must not be read as a refusal", tools.any { it.status == "failed" })

                val answer = updates.filter { it.kind == "agent_message_chunk" }.joinToString("") { it.text }
                assertTrue("the answer text must arrive (content is a single object)", answer.isNotBlank())
                assertTrue("usage must be parsed", updates.any { it.usage != null && it.usage!!.size > 0 })

                // Longer than the 20 s ping interval: the idle socket must survive.
                delay(25_000)

                // "이번만" really is one time: the same tool is asked about again.
                val stopAgain = client.prompt(
                    opened.sessionId,
                    "Use your shell tool again to run exactly: echo dsk-live-test-2 . Then reply with the single word DONE."
                )
                assertEquals("end_turn", stopAgain)
                delay(500)
                assertEquals("a one-time allow must not turn into allow-always", 2, session.requests().size)

                // The session appears in the list.
                val listed = client.listSessions(null)
                assertTrue("session/list must include the new session", listed.any { it.sessionId == opened.sessionId })
            } finally {
                collector.cancel()
                client.close()
            }
        }
    }

    @Test
    fun rejectIsARefusalAndReopeningReplaysTheHistory() = runBlocking {
        withTimeout(240_000) {
            val first = Session(AcpClient(OkHttpClient(), url, secret()))
            val firstCollector = launch(Dispatchers.Default) {
                first.client.events.collect { event ->
                    first.events += event
                    if (event is AcpEvent.PermissionRequest) {
                        // What the app's "거절" button sends.
                        first.client.respondPermission(event.request.requestId, event.request.rejectOnceId)
                    }
                }
            }
            val sessionId: String
            try {
                first.client.connect()
                first.client.initialize()
                val opened = first.client.newSession(workspace())
                sessionId = opened.sessionId
                println("LIVE session id: $sessionId")
                first.client.useTestModel(sessionId)
                first.client.setMode(sessionId, "approve")
                val stop = first.client.prompt(
                    sessionId,
                    "Use your shell tool to run exactly this command: echo dsk-live-reject . Then reply with the single word DONE."
                )
                assertEquals("end_turn", stop)
                delay(500)
                assertTrue("approve mode must ask", first.requests().isNotEmpty())
                val tools = first.updates().mapNotNull { it.toolCall }
                assertTrue("a rejected tool must be reported as failed", tools.any { it.status == "failed" })
                assertFalse("a rejected tool must not run", tools.any { it.status == "completed" })
            } finally {
                firstCollector.cancel()
                first.client.close()
            }

            // A second connection reopens the session, as the phone does after a drop.
            val second = Session(AcpClient(OkHttpClient(), url, secret()))
            val secondCollector = launch(Dispatchers.Default) {
                second.client.events.collect { second.events += it }
            }
            try {
                second.client.connect()
                second.client.initialize()
                val loaded = second.client.loadSession(sessionId, workspace())
                assertEquals("session/load carries no id; the requested one must be used", sessionId, loaded.sessionId)
                delay(500)
                val replay = second.updates()
                assertTrue(
                    "the user's message must be replayed",
                    replay.any { it.kind == "user_message_chunk" && it.text.contains("dsk-live-reject") }
                )
                assertTrue("the answer must be replayed", replay.any { it.kind == "agent_message_chunk" && it.text.isNotBlank() })
                val all = synchronized(second.events) { second.events.toList() }
                assertTrue("the end of the replay must be marked", all.any { it === AcpEvent.HistoryLoaded })
                val marker = all.indexOfFirst { it === AcpEvent.HistoryLoaded }
                val lastChunk = all.indexOfLast { it is AcpEvent.Update && it.update.kind.endsWith("_message_chunk") }
                assertTrue("the marker must come after every replayed message", marker > lastChunk)
            } finally {
                secondCollector.cancel()
                second.client.close()
            }
        }
    }

    /**
     * What the phone does when the 작업 폴더 setting is blank (the default). 1.11.0 sent "~"
     * here and goose refused every new chat with "Invalid params: cwd must be an absolute
     * path" - the other live tests passed an absolute folder and never saw it.
     */
    @Test
    fun blankFolderSettingStillOpensANewChat() = runBlocking {
        withTimeout(60_000) {
            val client = AcpClient(OkHttpClient(), url, secret())
            try {
                client.connect()
                val init = client.initialize()
                // Proof that the old value really is refused, so this test keeps its meaning.
                try {
                    client.newSession("~")
                    fail("goose accepted a relative cwd; the RemoteCwd rules may be unnecessary now")
                } catch (e: AcpException) {
                    assertTrue("message was: ${e.message}", e.message.orEmpty().contains("Invalid params"))
                }
                val sessions = if (init.canListSessions) client.listSessions(null) else emptyList()
                val cwd = RemoteCwd.resolve("", sessions)
                assertTrue("a blank setting must resolve to a PC folder (sessions: ${sessions.size})", cwd != null)
                assertTrue("resolved folder must be absolute: $cwd", RemoteCwd.isAbsolute(cwd!!))
                val opened = client.newSession(cwd)
                assertTrue("session/new must succeed with the resolved folder", opened.sessionId.isNotBlank())
                println("LIVE session id: ${opened.sessionId}")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun wrongKeyIsRefusedWithAClearMessage() = runBlocking {
        withTimeout(60_000) {
            val client = AcpClient(OkHttpClient(), url, "definitely-not-the-key-0000000000")
            try {
                client.connect()
                fail("a wrong key must not connect")
            } catch (e: AcpException) {
                assertTrue("message was: ${e.message}", e.message.orEmpty().contains("Secret Key"))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun deadAddressFailsWithinTheConnectDeadline() = runBlocking {
        withTimeout(60_000) {
            // Nothing listens on this loopback port.
            val client = AcpClient(OkHttpClient(), "ws://127.0.0.1:3299/acp", secret())
            val started = System.currentTimeMillis()
            try {
                client.connect()
                fail("nothing listens there")
            } catch (e: AcpException) {
                val took = System.currentTimeMillis() - started
                assertTrue("took ${took}ms", took <= AcpClient.CONNECT_TIMEOUT_MS + 5_000)
            } finally {
                client.close()
            }
        }
    }
}
