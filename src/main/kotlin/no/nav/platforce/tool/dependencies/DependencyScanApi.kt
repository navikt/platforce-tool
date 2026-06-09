package no.nav.platforce.tool.dependencies

import com.google.gson.Gson
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.path

fun dependencyScanRoutes(
    cache: DependencyScanCache,
    scanner: DependencyScanner,
    pullRequestService: DependencyPullRequestService,
) = listOf(
    "/internal/api/dependency-scan" bind Method.GET to {
        Response(Status.OK)
            .header("Content-Type", "application/json")
            .body(Gson().toJson(cache.get()))
    },
    "/internal/api/dependency-scan/refresh" bind Method.POST to {
        val result = scanner.scanAllRepositoriesWithProgress(cache)
        cache.update(result)
        Response(Status.OK).body("Refreshed")
    },
    "/internal/api/dependency-scan/progress" bind Method.GET to {
        Response(Status.OK)
            .header("Content-Type", "application/json")
            .body(Gson().toJson(cache.getProgress()))
    },
    "/internal/api/dependency-scan/pr/{owner}/{repo}" bind Method.POST to { req ->

        val owner = req.path("owner")!!
        val repo = req.path("repo")!!

        val url = pullRequestService.createPullRequest(owner, repo)

        Response(Status.OK)
            .header("Content-Type", "text/plain")
            .body(url)
    },
)
