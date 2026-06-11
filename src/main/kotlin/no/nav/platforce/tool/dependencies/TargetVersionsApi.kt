package no.nav.platforce.tool.dependencies

import com.google.gson.Gson
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Response.Companion.invoke
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.path

fun targetVersionsRoutes(store: TargetVersionsStore) =
    listOf(
        "/internal/api/target-versions" bind Method.GET to {
            Response(Status.OK)
                .header("Content-Type", "application/json")
                .body(Gson().toJson(store.get()))
        },
        "/internal/api/target-versions/update" bind Method.POST to { req ->
            val body = req.bodyString()
            val parsed = Gson().fromJson(body, TargetVersionsState::class.java)

            store.update(parsed)

            Response(Status.OK).body("updated")
        },
    )
