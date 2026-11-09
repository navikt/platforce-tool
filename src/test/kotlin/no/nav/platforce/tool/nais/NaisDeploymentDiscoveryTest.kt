package no.nav.platforce.tool.nais

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NaisDeploymentDiscoveryTest {
    @Test
    fun `repository associations accept GitHub URLs and coordinates but not other hosts`() {
        assertEquals("example/repo", normalizeDeploymentRepository("example/repo"))
        assertEquals("example/repo", normalizeDeploymentRepository("https://github.com/example/repo.git"))
        assertEquals("example/repo", normalizeDeploymentRepository("https://github.com/example/repo/"))
        assertEquals(null, normalizeDeploymentRepository("https://elsewhere.test/example/repo"))
        assertEquals(null, normalizeDeploymentRepository(null))
    }

    private fun json(body: String): JsonObject = JsonParser.parseString(body).asJsonObject

    private fun connection(
        nodes: String,
        cursor: String? = null,
    ): String = """{"nodes":[$nodes],"pageInfo":{"hasNextPage":${cursor != null},"endCursor":${cursor?.let { "\"$it\"" } ?: "null"}}}"""

    private fun app(
        id: String,
        environment: String = "dev-gcp",
    ): String = """{"id":"$id","name":"orders","teamEnvironment":{"environment":{"name":"$environment"}}}"""

    private fun instance(
        state: String,
        ready: Boolean = true,
    ): String = """{"status":{"state":"$state","ready":$ready},"image":{"name":"registry/orders","tag":"abc","digest":"sha256:abc"}}"""

    private fun deployment(
        state: String,
        repository: String,
    ): String = """{"repository":"$repository","createdAt":"2026-11-09T12:00:00Z","statuses":{"nodes":[{"state":"$state"}]}}"""

    @Test
    fun `discovery paginates teams apps instances and deployments and uses successful deploy repository for running apps`() {
        val calls = mutableListOf<String>()
        val discovery =
            NaisDeploymentDiscovery { query, variables ->
                val cursor = variables["after"]
                calls += "${query.lineSequence().first()}:$cursor"
                when {
                    "query DeploymentTeams" in query -> {
                        assertEquals("alice@example.test", variables["email"])
                        val page =
                            if (cursor ==
                                null
                            ) {
                                connection("""{"team":{"slug":"team-a"}}""", "teams-2")
                            } else {
                                connection("""{"team":{"slug":"team-b"}}""")
                            }
                        json("""{"user":{"teams":$page}}""")
                    }

                    "query DeploymentApplications" in query -> {
                        val page =
                            when {
                                variables["team"] == "team-b" -> connection("")
                                cursor == null -> connection(app("dev") + "," + app("stopped"), "apps-2")
                                else -> connection(app("prod", "prod-gcp") + "," + app("unknown"))
                            }
                        json("""{"team":{"applications":$page}}""")
                    }

                    "query DeploymentInstances" in query -> {
                        val page =
                            when {
                                variables["id"] == "stopped" -> connection(instance("TERMINATED"))
                                variables["id"] == "dev" && cursor == null -> connection(instance("STARTING"), "instances-2")
                                else -> connection(instance("RUNNING", false))
                            }
                        json("""{"node":{"instances":$page}}""")
                    }

                    else -> {
                        assertTrue("query ApplicationDeployments" in query)
                        val page =
                            when {
                                variables["id"] == "unknown" -> connection(deployment("FAILURE", "example/wrong"))
                                cursor == null -> connection(deployment("FAILURE", "example/wrong"), "deployments-2")
                                else -> connection(deployment("SUCCESS", "example/monorepo"))
                            }
                        json("""{"node":{"deployments":$page}}""")
                    }
                }
            }
        val result = discovery.get("alice@example.test")
        assertEquals(listOf("team-a", "team-b"), result.teams)
        assertEquals(3, result.applications.size)
        assertEquals(2, result.applications.count { it.repository == "example/monorepo" })
        assertEquals(1, result.applications.count { it.repository == null })
        val diagnostic = result.applications.single { it.repository == null }.repositoryDiagnostic!!
        assertTrue(diagnostic.reason.contains("No deployment had SUCCESS"))
        assertEquals(1, diagnostic.deploymentsChecked)
        assertEquals("FAILURE", diagnostic.recentDeployments.single().status)
        assertEquals("example/wrong", diagnostic.recentDeployments.single().repository)
        assertTrue(result.applications.filter { it.repository != null }.all { it.repositoryDiagnostic == null })
        assertEquals(
            setOf("dev-gcp", "prod-gcp"),
            result.applications
                .filter { it.repository != null }
                .map { it.environment }
                .toSet(),
        )
        assertTrue(result.applications.all { it.instances == 1 && it.readyInstances == 0 })
        assertTrue(result.applications.all { it.images.single().digest == "sha256:abc" })
        assertTrue(calls.any { it.endsWith(":teams-2") })
        assertTrue(calls.any { it.endsWith(":apps-2") })
        assertTrue(calls.any { it.endsWith(":instances-2") })
        assertTrue(calls.any { it.endsWith(":deployments-2") })
    }

    @Test
    fun `unknown repository diagnostics distinguish absent history missing repository and unsupported format`() {
        listOf(
            "" to "no deployment history",
            """{"repository":null,"createdAt":"2026-11-09","statuses":{"nodes":[{"state":"SUCCESS"}]}}""" to "no repository value",
            deployment("SUCCESS", "sf-arkiv-jobbsporet") to "not a supported GitHub",
            """{"repository":"example/repo","statuses":{"nodes":[]}}""" to "No deployment had SUCCESS",
        ).forEach { (deployments, reason) ->
            val discovery =
                NaisDeploymentDiscovery { query, _ ->
                    when {
                        "query DeploymentTeams" in query -> json("""{"user":{"teams":${connection("""{"team":{"slug":"team"}}""")}}}""")
                        "query DeploymentApplications" in query -> json("""{"team":{"applications":${connection(app("app"))}}}""")
                        "query DeploymentInstances" in query -> json("""{"node":{"instances":${connection(instance("RUNNING"))}}}""")
                        else -> json("""{"node":{"deployments":${connection(deployments)}}}""")
                    }
                }
            val result = discovery.get("alice").applications.single()
            assertEquals(null, result.repository)
            assertTrue(result.repositoryDiagnostic!!.reason.contains(reason), result.repositoryDiagnostic.reason)
        }
    }

    @Test
    fun `invalid pagination fails instead of returning a partial inventory`() {
        val discovery =
            NaisDeploymentDiscovery { _, _ ->
                json("""{"user":{"teams":${connection("""{"team":{"slug":"team-a"}}""", "repeat")}}}""")
            }
        assertThrows(IllegalStateException::class.java) { discovery.get("alice@example.test") }
    }

    @Test
    fun `membership discovery is repeated per user and never shared`() {
        val discovery =
            NaisDeploymentDiscovery { query, variables ->
                when {
                    "query DeploymentTeams" in query -> {
                        json(
                            """{"user":{"teams":${connection("""{"team":{"slug":"${variables["email"]}"}}""")}}}""",
                        )
                    }

                    else -> {
                        json("""{"team":{"applications":${connection("")}}}""")
                    }
                }
            }
        assertEquals(listOf("alice"), discovery.get("alice").teams)
        assertEquals(listOf("bob"), discovery.get("bob").teams)
    }
}
