package no.nav.platforce.tool.ignore

import com.google.gson.Gson
import no.nav.platforce.tool.notes.RepositoryNotesStore
import no.nav.platforce.tool.notes.SaveRepositoryNoteRequest
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind

fun ignoredRepositoriesRoutes(store: IgnoredRepositoriesStore) =
    listOf(
        "/internal/api/ignored-repositories" bind Method.GET to {
            Response(Status.OK)
                .header("Content-Type", "application/json")
                .body(Gson().toJson(store.get()))
        },
        "/internal/api/ignored-repositories/update" bind Method.POST to { req ->

            val body = req.bodyString()

            val parsed =
                Gson().fromJson(
                    body,
                    IgnoredRepositoriesState::class.java,
                )

            store.replace(
                team = null,
                repositories = parsed.repositories,
            )

            Response(Status.OK).body("updated")
        },
    )
