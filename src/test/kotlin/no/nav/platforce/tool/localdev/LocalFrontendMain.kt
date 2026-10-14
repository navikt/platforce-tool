package no.nav.platforce.tool.localdev

import com.sun.net.httpserver.HttpServer
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    val server = startLocalFrontend(port)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
    println("LOCAL MOCK MODE: http://localhost:${server.address.port}/internal/gui")
    println("Synthetic data only. Changes live in memory until restart; no external services are called.")
}

fun startLocalFrontend(
    port: Int = 8080,
    guiDirectory: Path = findGuiDirectory(),
): HttpServer {
    val api = LocalFrontendApi(guiDirectory).handler
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/") { exchange ->
        try {
            val request =
                Request(Method.valueOf(exchange.requestMethod), exchange.requestURI.toString())
                    .body(exchange.requestBody.bufferedReader().use { it.readText() })
            val response =
                try {
                    api(request)
                } catch (error: RuntimeException) {
                    System.err.println("Local mock request failed: ${exchange.requestMethod} ${exchange.requestURI}")
                    error.printStackTrace()
                    Response(Status.INTERNAL_SERVER_ERROR).body("Local mock request failed; see server console.")
                }
            response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val payload = response.body.payload.duplicate()
            val bytes = ByteArray(payload.remaining())
            payload.get(bytes)
            exchange.sendResponseHeaders(response.status.code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
        } finally {
            exchange.close()
        }
    }
    server.start()
    return server
}

private fun findGuiDirectory(): Path =
    generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("src/main/resources/gui") }
        .firstOrNull { Files.isDirectory(it) }
        ?: error("Cannot find src/main/resources/gui. Start from the repository or a directory within it.")
