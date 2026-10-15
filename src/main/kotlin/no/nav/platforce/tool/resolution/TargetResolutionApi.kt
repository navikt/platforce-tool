package no.nav.platforce.tool.resolution

import com.google.gson.Gson
import no.nav.platforce.tool.targets.snapshot
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind

fun targetResolutionRoutes(gradleTargetResolutionScanner: GradleTargetResolutionScanner) =
    listOf(
        "/internal/api/target-resolution/start" bind Method.GET to { request ->
            val context = request.userContext()
            val targetState = context.targetVersionsStore.get()
            val snapshot =
                gradleTargetResolutionScanner.start(
                    username = context.username,
                    targetState = targetState,
                    includeBoms = request.query("includeBoms") == "true",
                )
            when (snapshot.status) {
                ResolutionStatus.READY ->
                    Response(Status.OK)
                        .header("Content-Type", "application/json")
                        .body(Gson().toJson(snapshot))
                ResolutionStatus.RUNNING ->
                    Response(Status.ACCEPTED)
                        .header("Content-Type", "application/json")
                        .body(Gson().toJson(snapshot))
                ResolutionStatus.FAILED ->
                    Response(Status.INTERNAL_SERVER_ERROR)
                        .header("Content-Type", "application/json")
                        .body(Gson().toJson(snapshot))
                ResolutionStatus.IDLE ->
                    Response(Status.ACCEPTED)
                        .header("Content-Type", "application/json")
                        .body(Gson().toJson(snapshot))
            }
        },
        "/internal/api/target-resolution" bind Method.GET to { request ->
            val context = request.userContext()
            val targetState = context.targetVersionsStore.get()

            val snapshot =
                gradleTargetResolutionScanner.get(
                    username = context.username,
                    targetState = targetState,
                    includeBoms = request.query("includeBoms") == "true",
                )

            val status =
                when (snapshot.status) {
                    ResolutionStatus.READY -> Status.OK
                    ResolutionStatus.RUNNING -> Status.ACCEPTED
                    ResolutionStatus.FAILED -> Status.INTERNAL_SERVER_ERROR
                    ResolutionStatus.IDLE -> Status.OK
                }

            Response(status)
                .header("Content-Type", "application/json")
                .body(Gson().toJson(snapshot))
        },
    )
