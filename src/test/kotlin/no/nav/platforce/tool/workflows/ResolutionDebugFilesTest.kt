package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.gui.filesHandler
import no.nav.platforce.tool.resolution.ResolutionDebugFiles
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
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ResolutionDebugFilesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `inspection retains latest artifacts per scan mode and user without stale failure output`() {
        val store = ResolutionDebugFiles(directory)

        fun publish(
            user: String,
            bom: Boolean,
            build: String,
        ) {
            val run = store.start(user)
            store.startInspection(user, bom)
            run.resolve("build.gradle").writeText(build)
            run.resolve("gradle-output.txt").writeText("$build output")
            run.resolve("gradle-resolution-report.json").writeText("""{"status":"READY"}""")
            store.publishInspection(user, run, bom)
        }

        publish("alice", false, "normal")
        publish("alice", true, "bom")
        publish("bob", true, "private bob")
        publish("alice", false, "new normal")
        val inspection = store.inspectionDirectory("alice")
        assertEquals("new normal", inspection.resolve("build.gradle").readText())
        assertEquals("bom", inspection.resolve("build-bom.gradle").readText())
        assertEquals("bom output", inspection.resolve("gradle-output-bom.txt").readText())
        assertTrue(inspection.resolve("gradle-resolution-report-bom.json").exists())
        val handler = filesHandler(inspection.toFile())
        val response = handler(Request(Method.GET, "/internal/files/build-bom.gradle"))
        assertEquals(Status.OK, response.status)
        assertTrue(response.bodyString().contains("bom"))
        assertFalse(response.bodyString().contains("private bob"))

        // A failed new BOM run must not display old successful BOM output or disturb the normal scan.
        store.startInspection("alice", true)
        assertFalse(inspection.resolve("build-bom.gradle").exists())
        assertFalse(inspection.resolve("gradle-output-bom.txt").exists())
        assertEquals("new normal", inspection.resolve("build.gradle").readText())
        assertTrue(store.inspectionDirectory("bob").resolve("build-bom.gradle").exists())
        assertEquals(
            Status.FORBIDDEN,
            handler(
                Request(
                    Method.GET,
                    "/internal/files/../../${store.inspectionDirectory("bob").parent.fileName}/inspection/build-bom.gradle",
                ),
            ).status,
        )
    }

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
