package no.nav.platforce.tool.security

import com.google.gson.Gson
import mu.KotlinLogging
import no.nav.platforce.tool.targets.fingerprint
import no.nav.platforce.tool.targets.snapshot
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind

private val log = KotlinLogging.logger { }

fun targetSecurityRoutes(targetSecurityScanner: TargetSecurityScanner) =
    listOf(
        "/internal/api/target-resolution/security/start" bind Method.GET to { request ->
            val context = request.userContext()
            val targetState = context.targetVersionsStore.get()
            val snapshot =
                targetSecurityScanner.start(
                    username = context.username,
                    targetState = targetState,
                    includeBoms = request.query("includeBoms") == "true",
                )
            when (snapshot.status) {
                SecurityScanStatus.READY ->
                    Response(Status.OK)
                        .header("Content-Type", "application/json")
                        .body(Gson().toJson(snapshot))
                SecurityScanStatus.RUNNING ->
                    Response(Status.ACCEPTED)
                        .header("Content-Type", "application/json")
                        .body(Gson().toJson(snapshot))
                SecurityScanStatus.FAILED ->
                    Response(Status.INTERNAL_SERVER_ERROR)
                        .header("Content-Type", "application/json")
                        .body(Gson().toJson(snapshot))
                SecurityScanStatus.IDLE ->
                    Response(Status.ACCEPTED)
                        .header("Content-Type", "application/json")
                        .body(Gson().toJson(snapshot))
            }
        },
        "/internal/api/target-resolution/security" bind Method.GET to { request ->
            val context = request.userContext()
            val targetState = context.targetVersionsStore.get()

            val snapshot =
                targetSecurityScanner.get(
                    username = context.username,
                    targetState = targetState,
                    includeBoms = request.query("includeBoms") == "true",
                )

            log.info {
                "TARGET SECURITY CACHE: " +
                    "status=${snapshot.status}, " +
                    "fingerprint=${snapshot.targetFingerprint}, " +
                    "targets=${snapshot.result?.targets?.size}"
            }

            val status =
                when (snapshot.status) {
                    SecurityScanStatus.READY -> Status.OK
                    SecurityScanStatus.RUNNING -> Status.ACCEPTED
                    SecurityScanStatus.FAILED -> Status.INTERNAL_SERVER_ERROR
                    SecurityScanStatus.IDLE -> Status.OK
                }

            Response(status)
                .header("Content-Type", "application/json")
                .body(Gson().toJson(snapshot))
        },
    )
