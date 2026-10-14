package no.nav.platforce.tool.user

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind

private val gson = Gson()

fun selectedTeamRoutes() =
    listOf(
        "/internal/selectedTeam" bind Method.GET to { request ->
            val context = request.userContext()
            Response(OK)
                .header("Content-Type", "application/json")
                .body(
                    gson.toJson(
                        mapOf(
                            "selectedTeam" to context.selectedTeam,
                        ),
                    ),
                )
        },
        "/internal/selectedTeam" bind Method.POST to { request ->
            val context = request.userContext()
            val body = JsonParser.parseString(request.bodyString()).asJsonObject
            val team = body["selectedTeam"]?.takeIf { !it.isJsonNull }?.asString
            context.selectedTeam = team
            Response(OK)
        },
    )
