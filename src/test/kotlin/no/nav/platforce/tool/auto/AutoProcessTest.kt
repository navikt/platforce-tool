package no.nav.platforce.tool.auto

import com.google.gson.JsonParser
import no.nav.platforce.tool.targets.AvailableVersion
import no.nav.platforce.tool.targets.AvailableVersions
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.snapshot
import no.nav.platforce.tool.user.withUserCacheControl
import no.nav.platforce.tool.workflows.QueuedExecutor
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.http4k.routing.routes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class AutoProcessTest {
    private class Operations : AutoOperations {
        val calls = mutableListOf<String>()
        var stored =
            TargetVersionsState(
                mutableMapOf("example.plugin" to "2.0"),
                mutableMapOf(
                    "example:library" to "1.0",
                    "example:ignored" to "1.0",
                ),
                "8.0",
                ignoredDependencies = setOf("example:ignored"),
            )
        var upgrade = true
        var fail: String? = null
        var users = listOf("alice", "bob", "service-user")
        var onDependencies: () -> Unit = {}

        private fun record(step: String) {
            calls += step
            if (fail ==
                step
            ) {
                throw AutoStopped(AutoReport("Stopped at $step; work is unfinished", "Synthetic vulnerability or incomplete check"))
            }
        }

        override fun targets(username: String): TargetVersionsState {
            record("targets:$username")
            return stored.snapshot()
        }

        override fun available(
            username: String,
            key: String,
            current: String,
        ): AvailableVersions {
            record("available:$key")
            return AvailableVersions(
                key,
                current,
                if (upgrade) listOf(AvailableVersion("1.2", false, false), AvailableVersion("2.0", false, true)) else emptyList(),
            )
        }

        override fun saveTargets(
            username: String,
            expected: TargetVersionsState,
            updated: TargetVersionsState,
        ): List<String> {
            record("save:$username")
            check(stored == expected) { "Targets changed" }
            stored = updated.snapshot()
            return users
        }

        override fun scanDependencies(
            username: String,
            state: TargetVersionsState,
            checkActive: () -> Unit,
        ): AutoReport {
            record("dependencies")
            onDependencies()
            checkActive()
            return AutoReport("Dependencies checked")
        }

        override fun scanBoms(
            username: String,
            state: TargetVersionsState,
            checkActive: () -> Unit,
        ): AutoReport {
            record("boms")
            return AutoReport("BOMs checked")
        }

        override fun scanPlugins(
            username: String,
            state: TargetVersionsState,
            checkActive: () -> Unit,
        ): AutoReport {
            record("plugins")
            return AutoReport("Plugins checked")
        }

        override fun repositories(): List<String> = listOf("example/updated", "example/current", "example/updated")

        override fun createPullRequest(
            username: String,
            state: TargetVersionsState,
            repository: String,
            checkActive: () -> Unit,
        ): AutoReport {
            record("pr:$repository")
            return if (repository.endsWith(
                    "current",
                )
            ) {
                AutoReport(
                    "No changes required",
                )
            } else {
                AutoReport("Pull request created", "Updated library", "https://github.com/$repository/pull/42")
            }
        }
    }

    @Test
    fun `trigger returns immediately global overlap is rejected and completed reports survive service recreation`() {
        val store = MemoryAutoRunStore()
        val executor = QueuedExecutor()
        val operations = Operations()
        val service = AutoProcessService(store, AutoWorkflow(operations), executor)
        val handler =
            routes(
                *autoProcessRoutes(service, username = {
                    it.header("X-Test-User") ?: "service-user"
                }).toTypedArray(),
            ).withUserCacheControl()
        val trigger = Request(Method.GET, "/internal/triggerAuto")
        val response = handler(trigger)
        assertEquals(Status.OK, response.status)
        assertEquals("process started", response.bodyString())
        assertEquals("no-store", response.header("Cache-Control"))
        assertTrue(operations.calls.isEmpty())
        assertEquals(1, executor.queuedTasks)
        assertEquals(Status.CONFLICT, handler(trigger.header("X-Test-User", "bob")).status)
        val second = AutoProcessService(store, AutoWorkflow(operations), QueuedExecutor())
        assertNull(second.start("bob"))
        executor.runNext()
        assertEquals(AutoRunStatus.COMPLETED, second.snapshot().run?.status)
        assertEquals(
            listOf(
                "targets:service-user",
                "available:example:library",
                "save:service-user",
                "dependencies",
                "boms",
                "plugins",
                "pr:example/current",
                "pr:example/updated",
            ),
            operations.calls,
        )
        val logs = second.snapshot().logs
        assertTrue(logs.all { it.username == "service-user" && it.timestamp.isNotBlank() })
        assertTrue(logs.single { it.step == "Draft latest stable non-major versions" }.details!!.contains("example:library: 1.0 -> 1.2"))
        val saved = logs.single { it.step == "Save Target Versions" }
        assertEquals("Saved Target Versions as service-user", saved.message)
        assertEquals("Users in database:\nalice\nbob\nservice-user", saved.details)
        assertEquals("1.0", operations.stored.dependencies["example:ignored"])
        assertEquals("2.0", operations.stored.plugins["example.plugin"])
        assertEquals("example/updated", logs.single { it.url != null }.repository)
        assertEquals("https://github.com/example/updated/pull/42", logs.single { it.url != null }.url)
        assertNotNull(logs.last().details)
        assertNotNull(service.start("alice"))
    }

    @Test
    fun `no upgrades skips saving but still scans and evaluates opted in repositories`() {
        val operations = Operations().apply { upgrade = false }
        val executor = QueuedExecutor()
        val service = AutoProcessService(MemoryAutoRunStore(), AutoWorkflow(operations), executor)
        service.start("service-user")
        executor.runNext()
        assertFalse(operations.calls.any { it.startsWith("save:") })
        assertTrue(operations.calls.contains("plugins"))
        assertEquals(AutoRunStatus.COMPLETED, service.snapshot().run?.status)
    }

    @Test
    fun `each unsafe or incomplete scan stops all following stages and PR creation`() {
        listOf("dependencies", "boms", "plugins").forEach { stage ->
            val operations = Operations().apply { fail = stage }
            val executor = QueuedExecutor()
            val service = AutoProcessService(MemoryAutoRunStore(), AutoWorkflow(operations), executor)
            service.start("service-user")
            executor.runNext()
            assertEquals(AutoRunStatus.STOPPED, service.snapshot().run?.status)
            assertEquals(stage, operations.calls.last())
            assertFalse(operations.calls.any { it.startsWith("pr:") })
            assertTrue(
                service
                    .snapshot()
                    .logs
                    .last()
                    .message
                    .contains("unfinished"),
            )
            assertNotNull(service.start("bob"))
        }
    }

    @Test
    fun `worker errors interruptions and submission failures have terminal persisted outcomes`() {
        val operations = Operations().apply { onDependencies = { throw IOException("Synthetic failure") } }
        val executor = QueuedExecutor()
        val store = MemoryAutoRunStore()
        val service = AutoProcessService(store, AutoWorkflow(operations), executor)
        service.start("service-user")
        executor.runNext()
        assertEquals(AutoRunStatus.FAILED, store.snapshot().run?.status)
        assertTrue(
            store
                .snapshot()
                .logs
                .last()
                .details!!
                .contains("Synthetic failure"),
        )
        assertNull(store.snapshot().run?.step)
        store.healthy = false
        service.start("service-user")
        executor.runNext()
        assertEquals(AutoRunStatus.INTERRUPTED, store.snapshot().run?.status)
        store.healthy = true
        executor.shutdown()
        val handler = routes(*autoProcessRoutes(service, username = { "service-user" }).toTypedArray())
        assertEquals(Status.SERVICE_UNAVAILABLE, handler(Request(Method.GET, "/internal/triggerAuto")).status)
        assertEquals(AutoRunStatus.FAILED, store.snapshot().run?.status)
    }

    @Test
    fun `active step is observable while work runs and authenticated logs support pagination`() {
        val store = MemoryAutoRunStore()
        val operations = Operations()
        val executor = QueuedExecutor()
        val service = AutoProcessService(store, AutoWorkflow(operations), executor)
        operations.onDependencies = {
            assertEquals("Scan Dependencies", service.snapshot().run?.step)
            assertNotNull(service.snapshot().run?.stepStartedAt)
        }
        service.start("alice")
        executor.runNext()
        val handler = routes(*autoProcessRoutes(service, username = { "bob" }).toTypedArray()).withUserCacheControl()
        val response = handler(Request(Method.GET, "/internal/api/auto/status"))
        assertEquals("no-store", response.header("Cache-Control"))
        val json = JsonParser.parseString(response.bodyString()).asJsonObject
        assertEquals("alice", json["run"].asJsonObject["username"].asString)
        val firstId =
            store
                .snapshot()
                .logs
                .last()
                .id
        val older = handler(Request(Method.GET, "/internal/api/auto/status?before=$firstId"))
        assertTrue(
            JsonParser
                .parseString(older.bodyString())
                .asJsonObject["logs"]
                .asJsonArray
                .all { it.asJsonObject["id"].asLong < firstId },
        )
        listOf("x", "0", "-1", "9223372036854775808").forEach {
            assertEquals(Status.BAD_REQUEST, handler(Request(Method.GET, "/internal/api/auto/status?before=$it")).status)
        }
        val secured = routes(*autoProcessRoutes(service).toTypedArray())
        assertThrows(RuntimeException::class.java) { secured(Request(Method.GET, "/internal/triggerAuto")) }
        assertThrows(RuntimeException::class.java) { secured(Request(Method.GET, "/internal/api/auto/status")) }
    }

    @Test
    fun `latest stable non major selector matches bulk upgrade eligibility`() {
        fun candidate(
            version: String,
            prerelease: Boolean = false,
            major: Boolean = false,
        ) = AvailableVersion(version, prerelease, major)
        val versions =
            AvailableVersions(
                "example:library",
                "01.2",
                listOf(
                    candidate("1.8"),
                    candidate("2.0", major = true),
                    candidate("1.9-RC1", prerelease = true),
                    candidate("1.3"),
                    candidate("1.1"),
                ),
            )
        assertEquals("1.8", latestStableNonMajor(versions))
        assertNull(latestStableNonMajor(versions.copy(currentVersion = "latest.release")))
        assertNull(latestStableNonMajor(versions.copy(versions = listOf(candidate("2.0", major = true)))))
    }
}
