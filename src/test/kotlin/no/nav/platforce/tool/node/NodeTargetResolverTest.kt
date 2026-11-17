package no.nav.platforce.tool.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class NodeTargetResolverTest {
    @TempDir
    lateinit var directory: Path

    private val lock =
        """
        {"lockfileVersion":3,"packages":{
          "":{"dependencies":{"@scope/root":"1.0.0"}},
          "node_modules/@scope/root":{"version":"1.0.0","resolved":"https://registry.npmjs.org/@scope/root/-/root-1.0.0.tgz"},
          "node_modules/transitive":{"version":"2.0.0"},
          "node_modules/@scope/root/node_modules/transitive":{"version":"1.0.0"},
          "node_modules/alias":{"name":"actual-package","version":"3.0.0"},
          "node_modules/optional":{"version":"4.0.0","optional":true,"os":["linux"]}
        }}
        """.trimIndent()

    @Test
    fun `lock parser retains scoped names aliases nested versions and optional packages`() {
        val packages = NodeTargetResolver().parseLockfile(lock, "@scope/root", "1.0.0")
        assertEquals(
            listOf(
                "@scope/root" to "1.0.0",
                "transitive" to "2.0.0",
                "transitive" to "1.0.0",
                "actual-package" to "3.0.0",
                "optional" to "4.0.0",
            ),
            packages.map { it.name to it.version },
        )
        assertTrue(packages.all { it.group == "npm" })
    }

    @Test
    fun `incomplete foreign linked or wrong version lockfiles fail explicitly`() {
        listOf(
            "{}",
            lock.replace("\"lockfileVersion\":3", "\"lockfileVersion\":2"),
            lock.replace("\"version\":\"1.0.0\"", "\"version\":\"9.0.0\""),
            lock.replace("\"version\":\"4.0.0\"", "\"version\":\"*\""),
            lock.replace("\"optional\":true", "\"link\":true"),
            lock.replace("https://registry.npmjs.org", "https://example.test"),
            lock.replace("https://registry.npmjs.org", "http://registry.npmjs.org"),
        ).forEach { source ->
            assertThrows(Exception::class.java) { NodeTargetResolver().parseLockfile(source, "@scope/root", "1.0.0") }
        }
    }

    @Test
    fun `resolver generates an exact manifest disables scripts and isolates npm configuration and cleanup`() {
        val recorded = directory.resolve("recorded")
        val home =
            fakeNode(
                """
                /bin/mkdir -p '$recorded'
                /bin/cp package.json '$recorded/package.json'
                /usr/bin/printf '%s\n' "${'$'}@" > '$recorded/arguments'
                /usr/bin/printf '%s' "${'$'}PWD" > '$recorded/workdir'
                /usr/bin/env > '$recorded/environment'
                """.trimIndent() + "\n/bin/cat > package-lock.json <<'LOCK'\n$lock\nLOCK\n",
            )
        val result = NodeTargetResolver(home).resolve("@scope/root", "1.0.0")
        assertEquals(5, result.size)
        assertTrue(recorded.resolve("package.json").readText().contains("\"@scope/root\":\"1.0.0\""))
        val arguments = recorded.resolve("arguments").readText()
        assertTrue(arguments.contains("--package-lock-only"))
        assertTrue(arguments.contains("--ignore-scripts"))
        assertTrue(arguments.contains("--registry=https://registry.npmjs.org"))
        assertTrue(arguments.contains("--userconfig="))
        assertTrue(arguments.contains("--globalconfig="))
        assertFalse(recorded.resolve("environment").readText().contains("NODE_OPTIONS="))
        assertFalse(Path.of(recorded.resolve("workdir").readText()).exists())
    }

    @Test
    fun `resolver process failures and timeouts are surfaced and temporary directories are cleaned`() {
        val recorded = directory.resolve("workdir")
        val home = fakeNode("/usr/bin/printf '%s' \"${'$'}PWD\" > '$recorded'\necho synthetic-error\nexit 1")
        val failure = assertThrows(IllegalStateException::class.java) { NodeTargetResolver(home).resolve("example", "1.0.0") }
        assertTrue(failure.message!!.contains("synthetic-error"))
        assertFalse(Path.of(recorded.readText()).exists())
        fakeNode("/usr/bin/printf '%s' \"${'$'}PWD\" > '$recorded'\nexec /bin/sleep 10")
        val timeout =
            assertThrows(IllegalStateException::class.java) { NodeTargetResolver(home, timeoutSeconds = 1).resolve("example", "1.0.0") }
        assertTrue(timeout.message!!.contains("timed out"))
        assertFalse(Path.of(recorded.readText()).exists())
        assertThrows(IllegalArgumentException::class.java) { NodeTargetResolver(home).resolve("example", "^1.0.0") }
    }

    @Test
    fun `real npm resolves pinned public targets without installing node modules`() {
        val home = System.getenv("PLATFORCE_TEST_NODE_HOME")?.let(Path::of)
        assumeTrue(home != null, "Set PLATFORCE_TEST_NODE_HOME to run the public registry integration test")
        val packages = NodeTargetResolver(checkNotNull(home)).resolve("is-odd", "3.0.1")
        assertTrue(packages.any { it.name == "is-odd" && it.version == "3.0.1" })
        assertTrue(packages.any { it.name == "is-number" && it.version == "6.0.0" })
    }

    private fun fakeNode(body: String): Path {
        val home = directory.resolve("node")
        home.resolve("bin").createDirectories()
        home.resolve("bin/node").writeText("#!/bin/sh\n$body\n")
        check(home.resolve("bin/node").toFile().setExecutable(true))
        return home
    }
}
