package no.nav.platforce.tool.localdev

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import no.nav.platforce.tool.ignore.parseIgnoredDependencies
import no.nav.platforce.tool.nais.RebuildWorkflow
import no.nav.platforce.tool.nais.RunningApplication
import no.nav.platforce.tool.nais.RunningDeployments
import no.nav.platforce.tool.nais.RunningImage
import no.nav.platforce.tool.nais.deploymentRefreshMarkerResponse
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.RepositoryViewService
import no.nav.platforce.tool.repositories.withoutIgnoredDependencies
import no.nav.platforce.tool.resolution.ResolutionStatus
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.resolution.TargetResolutionSnapshot
import no.nav.platforce.tool.security.PluginScanPhase
import no.nav.platforce.tool.security.PluginSecuritySnapshot
import no.nav.platforce.tool.security.SecurityScanSnapshot
import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.TargetSecurityScan
import no.nav.platforce.tool.targets.AvailableVersion
import no.nav.platforce.tool.targets.AvailableVersions
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.fingerprint
import no.nav.platforce.tool.targets.preservingIgnoredDependencies
import no.nav.platforce.tool.targets.snapshot
import no.nav.platforce.tool.targets.withoutIgnoredDependencies
import no.nav.platforce.tool.workflows.RecordingGithubClient
import org.apache.maven.artifact.versioning.ComparableVersion
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
import java.time.Instant

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
    private var pluginSecurityReadyAt: Long? = null
    private val upgradeScans = mutableMapOf<String, Long>()
    private var deploymentRefreshAfter: Instant? = null

    private fun pluginSecurity(): PluginSecuritySnapshot {
        val status =
            when {
                pluginSecurityReadyAt == null -> SecurityScanStatus.IDLE
                now() < checkNotNull(pluginSecurityReadyAt) -> SecurityScanStatus.RUNNING
                else -> SecurityScanStatus.READY
            }
        return PluginSecuritySnapshot(
            status,
            fingerprint(targets),
            if (status == SecurityScanStatus.READY) LocalFrontendFixtures.pluginSecurity(targets) else null,
            phase =
                if (status == SecurityScanStatus.RUNNING) {
                    if (checkNotNull(pluginSecurityReadyAt) - now() > 1200) PluginScanPhase.RESOLUTION else PluginScanPhase.OSV
                } else {
                    null
                },
        )
    }

    private fun securityResult(): TargetSecurityScan {
        val result = LocalFrontendFixtures.security(targets.withoutIgnoredDependencies())
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
        inventories.values.map { cached ->
            val inventory = cached.withoutIgnoredDependencies(targets.ignoredDependencies)
            val scan = scanner.compare(inventory, targets.withoutIgnoredDependencies())
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
        val roots = targets.withoutIgnoredDependencies().dependencies.map { (key, version) -> LocalFrontendFixtures.resolved(key, version) }
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
            "/internal/api/deployments/refresh-marker" bind Method.GET to { deploymentRefreshMarkerResponse(deploymentRefreshAfter) },
            "/internal/api/deployments/refresh-marker" bind Method.POST to {
                deploymentRefreshAfter = Instant.ofEpochMilli(now())
                deploymentRefreshMarkerResponse(deploymentRefreshAfter)
            },
            "/internal/api/deployments/refresh-marker" bind Method.DELETE to {
                deploymentRefreshAfter = null
                deploymentRefreshMarkerResponse(null)
            },
            "/internal/api/deployments" bind Method.GET to {
                json(
                    RunningDeployments(
                        listOf("demo-commerce", "demo-platform"),
                        listOf(
                            RunningApplication(
                                "demo-commerce",
                                "orders-api",
                                "dev-gcp",
                                "demo/orders",
                                1,
                                1,
                                emptyList(),
                                null,
                                lastDeploymentActivityTime = Instant.ofEpochMilli(now() - 3_600_000).toString(),
                            ),
                            RunningApplication(
                                "demo-commerce",
                                "orders-api",
                                "prod-gcp",
                                "demo/orders",
                                2,
                                2,
                                emptyList(),
                                null,
                                lastDeploymentActivityTime = Instant.ofEpochMilli(now()).toString(),
                            ),
                            RunningApplication("demo-commerce", "orders-worker", "prod-gcp", "demo/orders", 1, 1, emptyList(), null),
                            RunningApplication("demo-platform", "customer-api", "prod-gcp", "demo/orders", 1, 1, emptyList(), null),
                            RunningApplication(
                                "demo-platform",
                                "unmapped-app",
                                "dev-gcp",
                                null,
                                1,
                                0,
                                listOf(RunningImage("registry/demo", "abc", "sha256:abc")),
                                null,
                            ),
                        ),
                        rebuildWorkflows = mapOf("demo/orders" to RebuildWorkflow(monorepo = true, branch = "main")),
                    ),
                )
            },
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
            *listOf(false, true)
                .map { start ->
                    ("/internal/api/target-versions/upgrade-security" + if (start) "/start" else "") bind Method.GET to { request ->
                        val key = "${request.query("kind")}:${request.query("key")}:${request.query("currentVersion")}"
                        if (start) upgradeScans.putIfAbsent(key, now() + 1200)
                        val readyAt = upgradeScans[key]
                        val status =
                            when {
                                readyAt == null -> "IDLE"
                                now() < readyAt -> "RUNNING"
                                else -> "READY"
                            }
                        val major =
                            request
                                .query("currentVersion")
                                .orEmpty()
                                .substringBefore('.')
                                .toIntOrNull() ?: 1
                        json(
                            mapOf(
                                "status" to status,
                                "targetFingerprint" to fingerprint(targets),
                                "result" to
                                    if (status == "READY") {
                                        listOf(
                                            mapOf("version" to "${major + 1}.0.0", "status" to "VULNERABLE", "vulnerabilityCount" to 2),
                                            mapOf("version" to "$major.99.0", "status" to "OK", "vulnerabilityCount" to 0),
                                        )
                                    } else {
                                        null
                                    },
                            ),
                            if (status == "RUNNING") Status.ACCEPTED else Status.OK,
                        )
                    }
                }.toTypedArray(),
            "/internal/api/target-versions/available" bind Method.GET to { request ->
                val key = request.query("key").orEmpty()
                val current = request.query("currentVersion").orEmpty()
                val major = current.substringBefore('.').toIntOrNull() ?: 1
                json(
                    AvailableVersions(
                        key,
                        current,
                        listOf(
                            AvailableVersion("${major + 1}.1.0-RC1", prerelease = true, majorUpgrade = true),
                            AvailableVersion("${major + 1}.0.0", prerelease = false, majorUpgrade = true),
                            AvailableVersion("$major.99.0", prerelease = false, majorUpgrade = false),
                        ).filter { ComparableVersion(it.version) > ComparableVersion(current) },
                    ),
                )
            },
            "/internal/api/target-versions/update" bind Method.POST to { request ->
                val parsed = body(request)
                upgradeScans.clear()
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
                targets = gson.fromJson(parsed, TargetVersionsState::class.java).snapshot().preservingIgnoredDependencies(targets)
                resolutionReadyAt = null
                securityReadyAt = null
                bomSecurityReadyAt = null
                pluginSecurityReadyAt = null
                Response(Status.OK).body("updated")
            },
            "/internal/api/plugin-security/start" bind Method.GET to {
                if (pluginSecurity().status != SecurityScanStatus.RUNNING) pluginSecurityReadyAt = now() + 2400
                json(pluginSecurity(), Status.ACCEPTED)
            },
            "/internal/api/plugin-security" bind Method.GET to {
                val snapshot = pluginSecurity()
                json(snapshot, if (snapshot.status == SecurityScanStatus.RUNNING) Status.ACCEPTED else Status.OK)
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
            "/internal/api/ignored-repositories" bind Method.GET to {
                json(mapOf("repositories" to ignored, "dependencies" to targets.ignoredDependencies))
            },
            "/internal/api/ignored-dependencies/update" bind Method.POST to { request ->
                targets = targets.copy(ignoredDependencies = parseIgnoredDependencies(request.bodyString()))
                resolutionReadyAt = null
                securityReadyAt = null
                bomSecurityReadyAt = null
                pluginSecurityReadyAt = null
                upgradeScans.clear()
                Response(Status.OK).body("updated")
            },
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
