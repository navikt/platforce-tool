package no.nav.platforce.tool.workflows

import com.google.gson.Gson
import com.google.gson.JsonParser
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.security.OsvVulnerabilityService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OsvBatchTest {
    @Test
    fun `OSV batches and advisory lookups allow sixty seconds without changing the shared client`() {
        for (ecosystem in listOf("Maven", "npm")) {
            val paths = mutableListOf<String>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        assertEquals(60_000, chain.readTimeoutMillis())
                        val request = chain.request()
                        paths += request.url.encodedPath
                        val body =
                            when (request.url.encodedPath) {
                                "/v1/querybatch" -> {
                                    val buffer = Buffer()
                                    checkNotNull(request.body).writeTo(buffer)
                                    val queries = JsonParser.parseString(buffer.readUtf8()).asJsonObject["queries"].asJsonArray
                                    assertEquals(467, queries.size())
                                    Gson().toJson(
                                        mapOf(
                                            "results" to
                                                List(467) { index ->
                                                    if (index == 0) mapOf("vulns" to listOf(mapOf("id" to "TEST"))) else emptyMap()
                                                },
                                        ),
                                    )
                                }

                                "/v1/vulns/TEST" -> {
                                    """{"id":"TEST","summary":"Synthetic advisory"}"""
                                }

                                else -> {
                                    error("Unexpected OSV request: ${request.url}")
                                }
                            }
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody("application/json".toMediaType()))
                            .build()
                    }.build()
            val originalTimeout = client.readTimeoutMillis
            val dependencies = (1..467).map { ResolvedDependency("example", "module-$it", "1.0.0", "1.0.0", emptyList()) }
            val results = OsvVulnerabilityService(client, ecosystem = ecosystem).find(dependencies)
            assertEquals(467, results.size)
            assertEquals("TEST", results.getValue("example:module-1:1.0.0").single().id)
            assertEquals(listOf("/v1/querybatch", "/v1/vulns/TEST"), paths)
            assertEquals(originalTimeout, client.readTimeoutMillis)
        }
    }

    @Test
    fun `npm lookups preserve scoped package names and use npm ecosystem instead of Maven coordinates`() {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val buffer = Buffer()
                    checkNotNull(request.body).writeTo(buffer)
                    val queries = JsonParser.parseString(buffer.readUtf8()).asJsonObject["queries"].asJsonArray
                    val pkg = queries.single().asJsonObject["package"].asJsonObject
                    assertEquals("@slack/bolt", pkg["name"].asString)
                    assertEquals("npm", pkg["ecosystem"].asString)
                    assertEquals("3.22.0", queries.single().asJsonObject["version"].asString)
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"results":[{}]}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        val service = OsvVulnerabilityService(client, ecosystem = "npm")
        assertEquals(
            mapOf("npm:@slack/bolt:3.22.0" to emptyList<Any>()),
            service.find(listOf(ResolvedDependency("npm", "@slack/bolt", "3.22.0", "3.22.0", emptyList()))),
        )
    }

    @Test
    fun `large BOM catalogs are batched without dropping coordinates`() {
        val sizes = mutableListOf<Int>()
        val service =
            service { count ->
                sizes += count
                Gson().toJson(mapOf("results" to List(count) { emptyMap<String, String>() }))
            }
        val dependencies = (1..1001).map { ResolvedDependency("example", "module-$it", "1.0", "1.0", emptyList()) }
        val results = service.find(dependencies)
        assertEquals(listOf(1000, 1), sizes)
        assertEquals(1001, results.size)
        assertTrue(results.values.all { it.isEmpty() })
    }

    @Test
    fun `incomplete OSV responses fail instead of treating unreturned coordinates as safe`() {
        val service = service { """{"results":[]}""" }
        assertThrows(IllegalStateException::class.java) {
            service.find(listOf(ResolvedDependency("example", "module", "1.0", "1.0", emptyList())))
        }
    }

    private fun service(body: (Int) -> String): OsvVulnerabilityService =
        OsvVulnerabilityService(
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val buffer = Buffer()
                    checkNotNull(request.body).writeTo(buffer)
                    val count =
                        JsonParser
                            .parseString(buffer.readUtf8())
                            .asJsonObject["queries"]
                            .asJsonArray
                            .size()
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body(count).toResponseBody("application/json".toMediaType()))
                        .build()
                }.build(),
        )
}
