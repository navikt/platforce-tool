package no.nav.platforce.tool.dependencies

import no.nav.platforce.tool.gui.filesHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

class ResolutionDebugFilesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `latest artifacts are selected separately for each user and each run`() {
        val store = ResolutionDebugFiles(directory)
        assertFalse(store.latestDirectory("alice").exists())
        val firstAlice = store.start("alice")
        val bob = store.start("bob")
        firstAlice.resolve("build.gradle").writeText("Alice's build")
        bob.resolve("build.gradle").writeText("Bob's build")
        assertEquals(firstAlice, store.latestDirectory("alice"))
        assertEquals(bob, store.latestDirectory("bob"))
        assertNotEquals(firstAlice.parent, bob.parent)

        // A later run starts with a fresh directory, so failed runs cannot show old unresolved findings.
        val secondAlice = store.start("alice")
        assertEquals(secondAlice, store.latestDirectory("alice"))
        assertFalse(secondAlice.resolve("build.gradle").exists())
        assertEquals(bob, store.latestDirectory("bob"))
    }

    @Test
    fun `file browser stays inside the selected users run including sibling prefix traversal`() {
        val store = ResolutionDebugFiles(directory)
        val alice = store.start("alice")
        val bob = store.start("bob")
        alice.resolve("build.gradle").writeText("Alice's build")
        bob.resolve("build.gradle").writeText("Bob's build")
        val handler = filesHandler(store.latestDirectory("alice").toFile())
        val response = handler(Request(Method.GET, "/internal/files/build.gradle"))
        assertEquals(Status.OK, response.status)
        assertTrue(response.bodyString().contains("Alice's build"))
        assertFalse(response.bodyString().contains("Bob's build"))
        assertEquals(
            Status.FORBIDDEN,
            handler(Request(Method.GET, "/internal/files/../../${bob.parent.fileName}/${bob.fileName}/build.gradle")).status,
        )

        // A sibling directory sharing the same name prefix is outside the user's selected directory too.
        assertEquals(
            Status.FORBIDDEN,
            handler(Request(Method.GET, "/internal/files/../${alice.fileName}-other/build.gradle")).status,
        )
    }
}
