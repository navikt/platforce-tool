package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.fingerprint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ScanCacheTest {
    @Test
    fun `repository refresh replaces its scan and preserves other repositories`() {
        val cache = DependencyScanCache()
        val first = inventory("example:library" to "1.0.0")
        val other = first.copy(repository = "example/other")
        cache.update("alice", listOf(first, other))
        // Refresh one repository in a cache containing two repositories.
        val refreshed = first.copy(scannedAt = "2026-01-02T00:00:00Z")
        cache.updateRepository("alice", refreshed)
        assertEquals(listOf(refreshed, other), cache.get("alice"))

        // A first scan of a third repository is appended without duplicates.
        val added = first.copy(repository = "example/new")
        cache.updateRepository("alice", added)
        cache.updateRepository("alice", added)
        assertEquals(listOf(refreshed, other, added), cache.get("alice"))
    }

    @Test
    fun `resolution fingerprint is independent of map insertion order`() {
        // Build equivalent target sets in different insertion orders.
        val first =
            TargetVersionsState(
                mutableMapOf("plugin.b" to "2", "plugin.a" to "1"),
                mutableMapOf("example:b" to "2", "example:a" to "1"),
                "8.1",
            )
        val reordered =
            TargetVersionsState(
                mutableMapOf("plugin.a" to "1", "plugin.b" to "2"),
                mutableMapOf("example:a" to "1", "example:b" to "2"),
                "8.1",
            )
        assertEquals(fingerprint(first), fingerprint(reordered))
    }

    @Test
    fun `dependency plugin and Gradle version changes alter resolution fingerprint`() {
        val original = TargetVersionsState(mutableMapOf("plugin.a" to "1"), mutableMapOf("example:a" to "1"), "8.1")
        // Change each resolution input separately and require a different fingerprint.
        listOf(
            original.copy(plugins = mutableMapOf("plugin.a" to "2")),
            original.copy(dependencies = mutableMapOf("example:a" to "2")),
            original.copy(gradleVersion = "8.2"),
            original.copy(transientDependencies = setOf("example:a")),
        ).forEach { changed ->
            assertNotEquals(fingerprint(original), fingerprint(changed))
        }
    }
}
