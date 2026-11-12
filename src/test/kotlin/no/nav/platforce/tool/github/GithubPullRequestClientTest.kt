package no.nav.platforce.tool.github

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GithubPullRequestClientTest {
    private fun pullRequest(number: Int) =
        """{"number":$number,"html_url":"https://github.com/example/repo/pull/$number","user":{"id":99,"type":"Bot"},
        "head":{"ref":"chore/update-dependencies-2026-11-01","repo":{"full_name":"example/repo"}},
        "base":{"ref":"release/next","repo":{"full_name":"example/repo"}},"state":"open","merged_at":null,
        "body":"Automated dependency update via platforce-tool app"}"""

    private fun client(reply: (Request) -> Pair<Int, String>) =
        DefaultGithubClient(
            object : GithubAccessTokenHandler {
                override val accessToken = "synthetic-token"
            },
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    assertEquals("api.github.com", request.url.host)
                    val (status, body) = reply(request)
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("Synthetic")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }.build(),
        )

    @Test
    fun `open PR listing follows all pages and encodes the base branch`() {
        val pages = mutableListOf<String?>()
        val github =
            client { request ->
                assertEquals("/repos/example/repo/pulls", request.url.encodedPath)
                assertEquals("open", request.url.queryParameter("state"))
                assertEquals("release/next", request.url.queryParameter("base"))
                assertEquals("100", request.url.queryParameter("per_page"))
                val page = request.url.queryParameter("page")
                pages += page
                200 to if (page == "1") (1..100).joinToString(",", "[", "]") { pullRequest(it) } else "[${pullRequest(101)}]"
            }
        val result = github.listOpenPullRequests("example", "repo", "release/next")
        assertEquals(listOf("1", "2"), pages)
        assertEquals((1..101).toList(), result.map { it.number })
        assertEquals(99L, result.first().authorId)
        assertEquals("Bot", result.first().authorType)
        assertFalse(result.first().merged)
    }

    @Test
    fun `PR metadata preserves merged state and deleted source repositories`() {
        val github =
            client { request ->
                assertEquals("/repos/example/repo/pulls/42", request.url.encodedPath)
                200 to
                    pullRequest(42)
                        .replace("\"merged_at\":null", "\"merged_at\":\"2026-11-01T10:00:00Z\"")
                        .replace("\"state\":\"open\"", "\"state\":\"closed\"")
                        .replace(
                            "\"head\":{\"ref\":\"chore/update-dependencies-2026-11-01\",\"repo\":{\"full_name\":\"example/repo\"}}",
                            "\"head\":{\"ref\":\"chore/update-dependencies-2026-11-01\",\"repo\":null}",
                        )
            }
        val result = github.getPullRequest("example", "repo", 42)
        assertTrue(result.merged)
        assertEquals("closed", result.state)
        assertNull(result.headRepository)
        assertEquals("release/next", result.baseBranch)
    }

    @Test
    fun `superseding comments and closure use authenticated issue POST and pull PATCH requests`() {
        val calls = mutableListOf<String>()
        val github =
            client { request ->
                calls += "${request.method} ${request.url.encodedPath}"
                val body = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
                if (request.method == "POST") {
                    assertEquals("Superseded by https://github.com/example/repo/pull/43", body["body"].asString)
                    201 to "{}"
                } else {
                    assertEquals("closed", body["state"].asString)
                    200 to """{"number":42,"state":"closed"}"""
                }
            }
        github.commentOnPullRequest("example", "repo", 42, "Superseded by https://github.com/example/repo/pull/43")
        github.closePullRequest("example", "repo", 42)
        assertEquals(listOf("POST /repos/example/repo/issues/42/comments", "PATCH /repos/example/repo/pulls/42"), calls)
    }

    @Test
    fun `read and write failures are explicit and a successful response must confirm closure`() {
        listOf(401, 403, 429, 500).forEach { status ->
            val github = client { status to "{}" }
            assertThrows(IllegalStateException::class.java) { github.getPullRequest("example", "repo", 42) }
            assertThrows(IllegalStateException::class.java) { github.listOpenPullRequests("example", "repo", "main") }
            assertThrows(IllegalStateException::class.java) { github.commentOnPullRequest("example", "repo", 42, "Superseded") }
            assertThrows(IllegalStateException::class.java) { github.closePullRequest("example", "repo", 42) }
        }
        listOf("""{"number":42,"state":"open"}""", """{"number":43,"state":"closed"}""", "{}").forEach { body ->
            assertThrows(IllegalStateException::class.java) { client { 200 to body }.closePullRequest("example", "repo", 42) }
        }
        assertThrows(IllegalStateException::class.java) { client { 200 to "{}" }.getPullRequest("example", "repo", 42) }
    }
}
