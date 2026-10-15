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
