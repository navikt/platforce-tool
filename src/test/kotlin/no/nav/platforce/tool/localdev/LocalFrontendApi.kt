package no.nav.platforce.tool.localdev

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.RepositoryViewService
import no.nav.platforce.tool.resolution.ResolutionStatus
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.resolution.TargetResolutionSnapshot
import no.nav.platforce.tool.security.SecurityScanSnapshot
import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.TargetSecurityScan
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.fingerprint
import no.nav.platforce.tool.targets.snapshot
import no.nav.platforce.tool.workflows.RecordingGithubClient
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.ResourceLoader
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import org.http4k.routing.static
import java.nio.file.Path

class LocalFrontendApi(
    guiDirectory: Path,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gson = Gson()
    private val scanner = DependencyScanner(RecordingGithubClient())
    private var targets = LocalFrontendFixtures.targets()
    private val inventories =
        listOf("demo/orders-api", "demo/orders-worker", "demo/customer-api", "demo/archived-service")
            .associateWith { LocalFrontendFixtures.inventory(it) }
            .toMutableMap()
    private var ignored = setOf("demo/archived-service")
    private val notes =
        mutableMapOf("demo/orders-api" to "Local demo: expand this repository to explore updates, overrides and vulnerabilities.")
    private var selectedTeam: String? = "demo-commerce"
    private var resolutionReadyAt: Long? = null
    private var securityReadyAt: Long? = 0L
    private var bomSecurityReadyAt: Long? = null

    private fun securityResult(): TargetSecurityScan {
        val result = LocalFrontendFixtures.security(targets)
        return if (bomSecurityReadyAt?.let { now() >= it } == true) result else result.copy(platformBoms = emptyList())
    }

    private fun json(
        value: Any,
        status: Status = Status.OK,
    ): Response = Response(status).header("Content-Type", "application/json").body(gson.toJson(value))

    private fun body(request: Request): JsonObject {
        val element = JsonParser.parseString(request.bodyString())
        require(element.isJsonObject) { "Expected a JSON object" }
        return element.asJsonObject
    }

    private fun JsonObject.string(name: String): String {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Expected string: $name" }
        return value.asString
    }

    private fun scans() =
        inventories.values.map { inventory ->
            val scan = scanner.compare(inventory, targets)
            if (securityReadyAt?.let { now() >= it } == true) {
                scanner.enrichSecurityScan(scan, securityResult(), inventory)
            } else {
                scan
            }
        }

    private fun resolution(): TargetResolutionSnapshot {
        val readyAt = resolutionReadyAt
        val status =
            when {
                readyAt == null -> ResolutionStatus.IDLE
                now() < readyAt -> ResolutionStatus.RUNNING
                else -> ResolutionStatus.READY
            }
        val roots = targets.dependencies.map { (key, version) -> LocalFrontendFixtures.resolved(key, version) }
        return TargetResolutionSnapshot(
            status,
            fingerprint(targets),
            if (status ==
                ResolutionStatus.READY
            ) {
                TargetResolution(targets.gradleVersion, roots, roots.associate { it.key to listOf(it) })
            } else {
                null
            },
            null,
        )
    }

    private fun security(includeBoms: Boolean = false): SecurityScanSnapshot {
        val readyAt = if (includeBoms) bomSecurityReadyAt else securityReadyAt
        val status =
            when {
                readyAt == null -> SecurityScanStatus.IDLE
                now() < readyAt -> SecurityScanStatus.RUNNING
                else -> SecurityScanStatus.READY
            }
        return SecurityScanSnapshot(
            status,
            fingerprint(targets),
            if (status == SecurityScanStatus.READY) securityResult() else null,
            null,
            normalReady = securityReadyAt?.let { now() >= it } == true,
            bomReady = securityReadyAt?.let { now() >= it } == true && bomSecurityReadyAt?.let { now() >= it } == true,
        )
    }

    private fun snapshotResponse(snapshot: TargetResolutionSnapshot) =
        json(snapshot, if (snapshot.status == ResolutionStatus.RUNNING) Status.ACCEPTED else Status.OK)

    private fun snapshotResponse(snapshot: SecurityScanSnapshot) =
        json(snapshot, if (snapshot.status == SecurityScanStatus.RUNNING) Status.ACCEPTED else Status.OK)

    private val api =
        routes(
            "/" bind Method.GET to { Response(Status.FOUND).header("Location", "/internal/gui") },
            "/internal/gui" bind Method.GET to static(ResourceLoader.Directory(guiDirectory.toString())),
            "/internal/repos/view" bind Method.GET to {
                json(
                    RepositoryViewService().buildRepoView(
                        LocalFrontendFixtures.repositories.map { (name, team) -> name to team },
                        LocalFrontendFixtures.installed,
                        scans().associateBy { it.repository },
                    ),
                )
            },
            "/internal/api/dependency-scan" bind Method.GET to { json(scans()) },
            "/internal/api/dependency-scan/progress" bind Method.GET to {
                json(mapOf("total" to inventories.size, "done" to inventories.size, "running" to false))
            },
            "/internal/api/dependency-scan/refresh" bind Method.POST to {
                LocalFrontendFixtures.installed.forEach { inventories[it] = LocalFrontendFixtures.inventory(it) }
                Response(Status.OK).body("Mock repositories refreshed")
            },
            "/internal/api/dependency-scan/refresh/{owner}/{repo}" bind Method.POST to { request ->
                val repository = "${request.path("owner")}/${request.path("repo")}"
                if (repository !in LocalFrontendFixtures.installed) {
                    Response(Status.NOT_FOUND).body("Unknown or inaccessible mock repository")
                } else {
                    inventories[repository] = LocalFrontendFixtures.inventory(repository)
                    Response(Status.OK)
                }
            },
            "/internal/api/dependency-scan/pr/{owner}/{repo}" bind Method.POST to { request ->
                val repository = "${request.path("owner")}/${request.path("repo")}"
                if (repository !in inventories) {
                    Response(Status.NOT_FOUND).body("Unknown mock repository")
                } else {
                    Response(Status.OK).header("Content-Type", "text/plain").body("/internal/local-demo/pr")
                }
            },
            "/internal/local-demo/pr" bind Method.GET to {
                Response(Status.OK)
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body(
                        "<h1>Simulated pull request</h1><p>No GitHub calls were made and no pull request was created.</p><a href='/internal/gui'>Back to GUI</a>",
                    )
            },
            "/internal/api/target-versions" bind Method.GET to { json(targets) },
            "/internal/api/target-versions/update" bind Method.POST to { request ->
                val parsed = body(request)
                require(parsed["plugins"]?.isJsonObject == true && parsed["dependencies"]?.isJsonObject == true) {
                    "Expected plugins and dependencies objects"
                }
                parsed["plugins"].asJsonObject.keySet().forEach { parsed["plugins"].asJsonObject.string(it) }
                parsed["dependencies"].asJsonObject.keySet().forEach { parsed["dependencies"].asJsonObject.string(it) }
                parsed["platformBoms"]?.let { boms ->
                    require(boms.isJsonObject) { "Expected platformBoms object" }
                    boms.asJsonObject.keySet().forEach { boms.asJsonObject.string(it) }
                }
                parsed.string("gradleVersion")
                require(parsed["transientDependencies"]?.isJsonArray == true) { "Expected transientDependencies array" }
                require(
                    parsed["transientDependencies"].asJsonArray.all {
                        it.isJsonPrimitive && it.asJsonPrimitive.isString
                    },
                ) { "Expected transient dependency names" }
                targets = gson.fromJson(parsed, TargetVersionsState::class.java).snapshot()
                resolutionReadyAt = null
                securityReadyAt = null
                bomSecurityReadyAt = null
                Response(Status.OK).body("updated")
            },
            "/internal/api/target-resolution/start" bind Method.GET to {
                if (resolutionReadyAt == null) resolutionReadyAt = now() + 1200
                snapshotResponse(resolution())
            },
            "/internal/api/target-resolution" bind Method.GET to { snapshotResponse(resolution()) },
            "/internal/api/target-resolution/security/start" bind Method.GET to { request ->
                val includeBoms = request.query("includeBoms") == "true"
                if (security(includeBoms).status != SecurityScanStatus.RUNNING) {
                    if (includeBoms) {
                        bomSecurityReadyAt = now() + 1200
                        securityReadyAt = bomSecurityReadyAt
                    } else {
                        bomSecurityReadyAt = null
                        securityReadyAt = now() + 1200
                    }
                }
                snapshotResponse(security(includeBoms))
            },
            "/internal/api/target-resolution/security" bind Method.GET to { request ->
                snapshotResponse(security(request.query("includeBoms") == "true"))
            },
            "/internal/api/ignored-repositories" bind Method.GET to { json(mapOf("repositories" to ignored)) },
            "/internal/api/ignored-repositories/update" bind Method.POST to { request ->
                val repositories = body(request)["repositories"]
                require(repositories != null && repositories.isJsonArray) { "Expected repositories array" }
                require(repositories.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) { "Expected repository names" }
                ignored = repositories.asJsonArray.map { it.asString }.toSet()
                Response(Status.OK).body("updated")
            },
            "/internal/api/repository-notes" bind Method.GET to { json(notes) },
            "/internal/api/repository-notes" bind Method.POST to { request ->
                val parsed = body(request)
                val repository = parsed.string("repository")
                require(repository in LocalFrontendFixtures.repositories) { "Unknown mock repository" }
                notes[repository] = parsed.string("note")
                Response(Status.OK).body("saved")
            },
            "/internal/selectedTeam" bind Method.GET to { json(mapOf("selectedTeam" to selectedTeam)) },
            "/internal/selectedTeam" bind Method.POST to { request ->
                val parsed = body(request)
                val team = parsed["selectedTeam"]
                selectedTeam =
                    if (team == null || team.isJsonNull) {
                        null
                    } else {
                        parsed.string("selectedTeam").also {
                            require(it in LocalFrontendFixtures.repositories.values) { "Unknown mock team" }
                        }
                    }
                Response(Status.OK)
            },
        )

    val handler: HttpHandler = { request ->
        synchronized(this) {
            val response =
                try {
                    api(request)
                } catch (error: JsonParseException) {
                    Response(Status.BAD_REQUEST).body("Invalid JSON: ${error.message}")
                } catch (error: IllegalArgumentException) {
                    Response(Status.BAD_REQUEST).body(error.message ?: "Invalid mock request")
                }
            response.header("Cache-Control", "no-store").header("X-Platforce-Mode", "local-mock")
        }
    }
}
