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
    )
