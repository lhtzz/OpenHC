package io.github.gcbond.openhc.settings

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticLogTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun clearRemovesOnlyTheConnectionLogAndNewEntriesCanBeWritten() {
        val log = temp.newFile("openhc-diagnostic.log").apply { writeText("old attempt\n") }
        val crash = temp.newFile("openhc-last-crash.log").apply { writeText("keep crash\n") }
        val trust = temp.newFile("trust-store.bin").apply { writeText("synthetic trust data") }

        assertTrue(clearConnectionLogFile(log))
        assertFalse(log.exists())
        assertTrue(clearConnectionLogFile(log), "clearing an empty log is safe")
        assertEquals("keep crash\n", crash.readText())
        assertEquals("synthetic trust data", trust.readText())

        log.appendText("new attempt\n")
        assertEquals("new attempt\n", log.readText())
    }

    @Test
    fun clearDoesNotDeleteAPathThatIsNotARegularFile() {
        val path = temp.newFolder("openhc-diagnostic.log")
        assertFalse(clearConnectionLogFile(path))
        assertTrue(path.isDirectory)
    }

    @Test
    fun clearReportsFailureWhenTheLogCannotBeDeleted() {
        val existing = temp.newFile("openhc-diagnostic.log").apply { writeText("keep old attempt") }
        val resistant = object : File(existing.path) {
            override fun delete(): Boolean = false
        }
        assertFalse(clearConnectionLogFile(resistant))
        assertEquals("keep old attempt", existing.readText())
    }
}
