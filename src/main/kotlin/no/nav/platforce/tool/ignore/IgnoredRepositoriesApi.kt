package no.nav.platforce.tool.ignore

import com.google.gson.Gson
import no.nav.platforce.tool.notes.RepositoryNotesStore
import no.nav.platforce.tool.notes.SaveRepositoryNoteRequest
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind

fun ignoredRepositoriesRoutes() =
    listOf(
        "/internal/api/ignored-repositories" bind Method.GET to { request ->
            val context = request.userContext()
            Response(Status.OK)
                .header("Content-Type", "application/json")
                .body(Gson().toJson(context.ignoredRepositoriesStore.get()))
        },
        "/internal/api/ignored-repositories/update" bind Method.POST to { request ->
            val context = request.userContext()

            val body = request.bodyString()

            val parsed =
                Gson().fromJson(
                    body,
                    IgnoredRepositoriesState::class.java,
                )

            context.ignoredRepositoriesStore.replace(
                team = null,
                repositories = parsed.repositories,
            )

            Response(Status.OK).body("updated")
        },
    )
