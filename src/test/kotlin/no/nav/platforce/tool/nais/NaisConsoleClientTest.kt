package no.nav.platforce.tool.nais

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class NaisConsoleClientTest {
    @Test
    fun `extracted NAIS client preserves GraphQL authentication user variables and repository sorting`() {
        val requestBody = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val method = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            method.set(exchange.requestMethod)
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            val response =
                """
                {"data":{"user":{"teams":{"nodes":[
                  {"role":"MEMBER","team":{"slug":"z-team","repositories":{"nodes":[{"name":"example/z"},{"name":"example/a"}]}}},
                  {"role":"ADMIN","team":{"slug":"a-team","repositories":{"nodes":[]}}}
                ]}}}}
                """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            // A loopback server exercises the real HTTP client without NAIS credentials or external calls.
            val client =
                NaisConsoleClient(
                    OkHttpClient(),
                    endpoint = "http://127.0.0.1:${server.address.port}/graphql",
                    serviceToken = { "synthetic-service-token" },
                )
            val result = client.getRepositoriesForUser("alice@example.test")
            assertEquals("POST", method.get())
            assertEquals("Bearer synthetic-service-token", authorization.get())
            val payload = JsonParser.parseString(requestBody.get()).asJsonObject
            assertEquals("alice@example.test", payload["variables"].asJsonObject["email"].asString)
            assertTrue(payload["query"].asString.contains("repositories"))
            assertEquals("alice@example.test", result.email)
            assertEquals(listOf("a-team", "z-team"), result.teams.map { it.team })
            assertEquals(listOf("example/a", "example/z"), result.teams[1].repositories)
            assertEquals("ADMIN", result.teams[0].role)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `missing NAIS user retains the explicit failure instead of returning empty repositories`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            exchange.requestBody.close()
            val body = """{"data":{}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client =
                NaisConsoleClient(
                    OkHttpClient(),
                    endpoint = "http://127.0.0.1:${server.address.port}/graphql",
                    serviceToken = { "synthetic-service-token" },
                )
            val error = assertThrows(IllegalStateException::class.java) { client.getRepositoriesForUser("alice@example.test") }
            assertEquals("No user returned", error.message)
        } finally {
            server.stop(0)
        }
    }
}
