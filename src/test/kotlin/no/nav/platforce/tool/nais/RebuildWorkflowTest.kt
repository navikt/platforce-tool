package no.nav.platforce.tool.nais

import no.nav.platforce.tool.github.DefaultGithubClient
import no.nav.platforce.tool.github.GithubAccessTokenHandler
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class RebuildWorkflowTest {
    private fun app(repository: String?) = RunningApplication("team", "app", "dev-gcp", repository, 1, 1, emptyList(), null)

    @Test
    fun `deploy workflow existence determines repository type with one lookup per discovered repository`() {
        val calls = mutableListOf<String>()
        val client =
            client { path ->
                calls += path
                when (path) {
                    "/repos/example/normal", "/repos/example/mono" -> 200 to """{"default_branch":"main"}"""
                    "/repos/example/normal/contents/.github/workflows/deploy.yml" -> 200 to "{}"
                    "/repos/example/normal/contents/.github/workflows/rebuild.yml" -> 200 to "{}"
                    "/repos/example/mono/contents" -> 200 to "[]"
                    else -> 404 to "{}"
                }
            }
        val result =
            RunningDeployments(
                listOf("team"),
                listOf(app("example/normal"), app("example/normal"), app("example/mono"), app(null)),
            ).withRebuildWorkflows(client)
        assertEquals(RebuildWorkflow(false, "main", exists = true), result.rebuildWorkflows["example/normal"])
        assertEquals(RebuildWorkflow(true, "main", exists = false), result.rebuildWorkflows["example/mono"])
        assertEquals(8, calls.size)
        assertEquals(2, result.rebuildWorkflows.size)
        assertEquals(4, result.applications.size)
    }

    @Test
    fun `repository access failures and workflow lookup errors never classify a repository as monorepo`() {
        listOf(403, 404, 429, 500).forEach { status ->
            val inaccessible =
                RunningDeployments(emptyList(), listOf(app("example/private")))
                    .withRebuildWorkflows(client { status to "{}" })
                    .rebuildWorkflows
                    .getValue("example/private")
            assertNull(inaccessible.monorepo)
            assertTrue(inaccessible.error!!.contains("GitHub App access"))
        }
        listOf(403, 429, 500).forEach { status ->
            val failed =
                RunningDeployments(emptyList(), listOf(app("example/repo")))
                    .withRebuildWorkflows(
                        client { path ->
                            if (path.endsWith("/repo")) 200 to """{"default_branch":"trunk"}""" else status to "{}"
                        },
                    ).rebuildWorkflows
                    .getValue("example/repo")
            assertNull(failed.monorepo)
            assertTrue(failed.error!!.isNotBlank())
        }
        val network =
            RunningDeployments(emptyList(), listOf(app("example/repo")))
                .withRebuildWorkflows(client { throw IOException("Synthetic network failure") })
        assertNull(network.rebuildWorkflows.getValue("example/repo").monorepo)
        val noContentsAccess =
            RunningDeployments(emptyList(), listOf(app("example/repo")))
                .withRebuildWorkflows(
                    client { path ->
                        if (path.endsWith("/repo")) 200 to """{"default_branch":"main"}""" else 404 to "{}"
                    },
                )
        assertNull(noContentsAccess.rebuildWorkflows.getValue("example/repo").monorepo)
    }

    private fun client(reply: (String) -> Pair<Int, String>): DefaultGithubClient =
        DefaultGithubClient(
            object : GithubAccessTokenHandler {
                override val accessToken = "synthetic-token"
            },
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val (status, body) = reply(chain.request().url.encodedPath)
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("Synthetic response")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }.build(),
        )
}
