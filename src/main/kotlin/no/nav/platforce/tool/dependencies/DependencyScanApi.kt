package no.nav.platforce.tool.dependencies

import com.google.gson.Gson
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind

fun dependencyScanRoutes(
    cache: DependencyScanCache,
    scanner: DependencyScanner,
) = listOf(
    "/internal/api/dependency-scan" bind Method.GET to {
        Response(Status.OK)
            .header("Content-Type", "application/json")
            .body(Gson().toJson(cache.get()))
    },
    "/internal/api/dependency-scan/refresh" bind Method.POST to {
        cache.update(scanner.scanAllRepositories())

        Response(Status.OK)
            .body("Refreshed")
    },
)
