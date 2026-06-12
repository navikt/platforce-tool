package no.nav.platforce.tool.notes

import com.google.gson.Gson
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Response.Companion.invoke
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.path

data class SaveRepositoryNoteRequest(
    val repository: String,
    val note: String,
)

fun repositoryNotesRoutes(store: RepositoryNotesStore) =
    listOf(
        "/internal/api/repository-notes" bind Method.GET to {
            Response(Status.OK)
                .header("Content-Type", "application/json")
                .body(Gson().toJson(store.get()))
        },
        "/internal/api/repository-notes" bind Method.POST to { req ->

            val body =
                Gson().fromJson(
                    req.bodyString(),
                    SaveRepositoryNoteRequest::class.java,
                )

            store.save(
                repository = body.repository,
                note = body.note,
            )

            Response(Status.OK)
                .body("saved")
        },
    )
