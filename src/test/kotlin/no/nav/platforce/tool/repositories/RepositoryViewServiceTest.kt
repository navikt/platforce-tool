package no.nav.platforce.tool.repositories

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RepositoryViewServiceTest {
    private val service = RepositoryViewService()

    @Test
    fun `repository view retains team sorting access state and the supplied enriched scan`() {
        val scan = RepositoryDependencyScan("example/scanned", "2026-01-01T00:00:00Z", emptyList())
        val result =
            service.buildRepoView(
                listOf(
                    "example/scanned" to "z-team",
                    "example/unscanned" to "a-team",
                    "example/no-access" to "a-team",
                ),
                setOf("example/scanned", "example/unscanned"),
                mapOf(scan.repository to scan, "example/no-access" to scan),
            )
        assertEquals(listOf("example/no-access", "example/unscanned", "example/scanned"), result.map { it.name })
        assertEquals(listOf(RepoState.NOT_IN_GITHUB, RepoState.NOT_SCANNED, RepoState.SCANNED), result.map { it.state })
        assertSame(scan, result.last().scan)
    }

    @Test
    fun `moving models to a feature package does not change the JSON contract consumed by GUI`() {
        val result = service.buildRepoView(listOf("example/repo" to "team"), emptySet(), emptyMap())
        val json =
            JsonParser
                .parseString(Gson().toJson(result))
                .asJsonArray
                .single()
                .asJsonObject
        assertEquals(setOf("name", "team", "state"), json.keySet())
        assertEquals("example/repo", json["name"].asString)
        assertEquals("team", json["team"].asString)
        assertEquals("NOT_IN_GITHUB", json["state"].asString)
    }
}
