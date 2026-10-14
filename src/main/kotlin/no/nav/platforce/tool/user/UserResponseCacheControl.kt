package no.nav.platforce.tool.user

import org.http4k.core.HttpHandler

fun HttpHandler.withUserCacheControl(): HttpHandler =
    { request ->
        val response = this(request)
        val path = request.uri.path
        if (path.startsWith("/internal/api/") || path == "/internal/repos/view" || path == "/internal/selectedTeam") {
            response.header("Cache-Control", "no-store")
        } else {
            response
        }
    }
