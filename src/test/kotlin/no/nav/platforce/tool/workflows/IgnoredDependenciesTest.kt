package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.ignore.parseIgnoredDependencies
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.RepositoryDependencyInventory
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityService
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.fingerprint
import no.nav.platforce.tool.targets.preservingIgnoredDependencies
import no.nav.platforce.tool.targets.withoutIgnoredDependencies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IgnoredDependenciesTest {
    private val key = "example:library"
    private val stored =
        TargetVersionsState(
            mutableMapOf(),
            mutableMapOf(key to "2.0", "example:other" to "1.0"),
            "8.0",
            transientDependencies = setOf(key),
            ignoredDependencies = setOf(key),
        )

    @Test
    fun `saving visible targets preserves the stored ignored version and transient flag`() {
        val active = stored.withoutIgnoredDependencies()
        assertEquals(mapOf("example:other" to "1.0"), active.dependencies)
        assertTrue(active.transientDependencies.isEmpty())
        val edited = active.copy(dependencies = mutableMapOf("example:other" to "3.0", key to "99.0"))
        val saved = edited.preservingIgnoredDependencies(stored)
        assertEquals("2.0", saved.dependencies[key])
        assertEquals("3.0", saved.dependencies["example:other"])
        assertEquals(setOf(key), saved.transientDependencies)
        val restored = saved.copy(ignoredDependencies = emptySet()).withoutIgnoredDependencies()
        assertEquals("2.0", restored.dependencies[key])
        assertNotEquals(fingerprint(active), fingerprint(restored))
    }

    @Test
    fun `cached repository scans hide ignored tracked and untracked dependencies only for their user`() {
        val inventory =
            RepositoryDependencyInventory(
                "example/repository",
                "",
                emptyMap(),
                mapOf(key to "1.0", "example:untracked" to "1.0"),
                "8.0",
            )
        val cache = DependencyScanCache()
        cache.update("alice", listOf(inventory))
        cache.update("bob", listOf(inventory))
        val resolution = GradleTargetResolutionScanner({ _, _ -> error("No scan should start") }, QueuedExecutor())
        val security =
            TargetSecurityScanner(
                TargetSecurityService(
                    object : VulnerabilityService {
                        override fun find(dependencies: List<ResolvedDependency>): Nothing = error("No OSV lookup should start")
                    },
                ),
                resolution,
                QueuedExecutor(),
            )
        val view = DependencyScanViewService(cache, security, DependencyScanner(RecordingGithubClient()))
        val ignored = stored.copy(ignoredDependencies = setOf(key, "example:untracked")).withoutIgnoredDependencies()
        val alice = view.get("alice", ignored).single()
        assertTrue(alice.findings.none { it.key == key })
        assertTrue(alice.untrackedDependencies.isEmpty())
        assertTrue(alice.declaredDependencyKeys!!.isEmpty())
        val restored = stored.copy(ignoredDependencies = emptySet())
        assertEquals(
            "2.0",
            view
                .get("alice", restored)
                .single()
                .findings
                .single { it.key == key }
                .targetVersion,
        )
        assertEquals(
            listOf("example:untracked"),
            view
                .get("bob", restored)
                .single()
                .untrackedDependencies
                .map { it.key },
        )
        assertEquals(inventory, cache.get("alice").single())
    }

    @Test
    fun `ignored dependency input validates coordinates and deduplicates entries`() {
        assertEquals(setOf(key), parseIgnoredDependencies("""{"dependencies":[" $key ","$key"]}"""))
        assertEquals(emptySet<String>(), parseIgnoredDependencies("""{"dependencies":[]}"""))
        listOf("null", "{}", """{"dependencies":[null]}""", """{"dependencies":["not-a-coordinate"]}""", "{").forEach {
            assertThrows(IllegalArgumentException::class.java) { parseIgnoredDependencies(it) }
        }
    }
}
