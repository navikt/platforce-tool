package no.nav.platforce.tool.notes

import com.google.gson.Gson
import no.nav.platforce.tool.user.userContext
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

fun repositoryNotesRoutes() =
    listOf(
        "/internal/api/repository-notes" bind Method.GET to { request ->
            val context = request.userContext()
            Response(Status.OK)
                .header("Content-Type", "application/json")
                .body(Gson().toJson(context.repositoryNotesStore.get()))
        },
        "/internal/api/repository-notes" bind Method.POST to { request ->
            val context = request.userContext()
            val body =
                Gson().fromJson(
                    request.bodyString(),
                    SaveRepositoryNoteRequest::class.java,
                )

            context.repositoryNotesStore.save(
                repository = body.repository,
                note = body.note,
            )

            Response(Status.OK)
                .body("saved")
        },
    )
