package com.dskapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which folder goes into session/new: goose only accepts absolute PC paths. */
class RemoteCwdTest {

    private fun summary(id: String, cwd: String) = AcpSessionSummary(
        sessionId = id, title = id, updatedAt = "", cwd = cwd,
        messageCount = 0, providerId = "", modelId = ""
    )

    @Test
    fun absolutePathsAreRecognised() {
        assertTrue(RemoteCwd.isAbsolute("C:\\Users\\me\\work"))
        assertTrue(RemoteCwd.isAbsolute("c:/Users/me"))
        assertTrue(RemoteCwd.isAbsolute("D:\\"))
        assertTrue(RemoteCwd.isAbsolute("\\\\nas\\share"))
        assertTrue(RemoteCwd.isAbsolute("/home/me"))
        assertTrue(RemoteCwd.isAbsolute("  C:\\x  "))
    }

    @Test
    fun relativeOrBlankPathsAreNot() {
        // "~" is what the app used to send; goose answers "cwd must be an absolute path".
        assertFalse(RemoteCwd.isAbsolute("~"))
        assertFalse(RemoteCwd.isAbsolute("~/work"))
        assertFalse(RemoteCwd.isAbsolute(""))
        assertFalse(RemoteCwd.isAbsolute("work\\project"))
        assertFalse(RemoteCwd.isAbsolute("C:"))
        assertFalse(RemoteCwd.isAbsolute("C:work"))
    }

    @Test
    fun typedAbsolutePathWins() {
        val sessions = listOf(summary("s1", "C:\\other"))
        assertEquals("C:\\mine", RemoteCwd.resolve(" C:\\mine ", sessions))
    }

    @Test
    fun blankSettingUsesTheMostRecentSessionFolder() {
        val sessions = listOf(summary("new", "C:\\dsk-remote\\workspace"), summary("old", "C:\\older"))
        assertEquals("C:\\dsk-remote\\workspace", RemoteCwd.resolve("", sessions))
    }

    @Test
    fun tildeSettingIsNotSentAndFallsBackToSessions() {
        val sessions = listOf(summary("s1", "C:\\dsk-remote\\workspace"))
        assertEquals("C:\\dsk-remote\\workspace", RemoteCwd.resolve("~", sessions))
    }

    @Test
    fun reopeningUsesThatSessionsOwnFolder() {
        val sessions = listOf(summary("recent", "C:\\a"), summary("target", "C:\\b"))
        assertEquals("C:\\b", RemoteCwd.resolve("", sessions, loadSessionId = "target"))
        // Unknown id: the most recent folder still works.
        assertEquals("C:\\a", RemoteCwd.resolve("", sessions, loadSessionId = "missing"))
    }

    @Test
    fun sessionsWithoutAbsoluteFoldersAreSkipped() {
        val sessions = listOf(summary("s1", ""), summary("s2", "~"), summary("s3", "/srv/goose"))
        assertEquals("/srv/goose", RemoteCwd.resolve("", sessions))
    }

    @Test
    fun nothingUsableGivesNullAndAHelpfulMessage() {
        assertNull(RemoteCwd.resolve("", emptyList()))
        assertNull(RemoteCwd.resolve("~", listOf(summary("s1", ""))))
        assertTrue(RemoteCwd.missingMessage("").contains("작업 폴더"))
        assertTrue(RemoteCwd.missingMessage("~").contains("'~'"))
    }
}
