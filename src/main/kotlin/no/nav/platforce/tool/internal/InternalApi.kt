package no.nav.platforce.tool.internal

import no.nav.platforce.tool.gui.filesHandler
import no.nav.platforce.tool.resolution.ResolutionDebugFiles
import no.nav.platforce.tool.user.userContext
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind
import java.nio.file.Files

fun internalRoutes(
    resolutionDebugFiles: ResolutionDebugFiles,
    username: (Request) -> String = { it.userContext().username },
) = run {
    val userFilesHandler: HttpHandler = { request ->
        val directory = Files.createDirectories(resolutionDebugFiles.inspectionDirectory(username(request)))
        filesHandler(directory.toFile())(request)
            .header("Cache-Control", "no-store")
    }

    listOf(
        "/internal/isAlive" bind Method.GET to { Response(OK) },
        "/internal/isReady" bind Method.GET to { Response(OK) },
        "/internal/metrics" bind Method.GET to Metrics.metricsHttpHandler,
        "/internal/hello" bind Method.GET to { Response(OK).body("Hello!") },
        "/internal/files" bind Method.GET to userFilesHandler,
        "/internal/files/{path:.*}" bind Method.GET to userFilesHandler,
    )
}
