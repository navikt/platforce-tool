package no.nav.platforce.tool.localdev

import no.nav.platforce.tool.repositories.RepositoryDependencyInventory
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.security.OverrideReason
import no.nav.platforce.tool.security.TargetSecurityResult
import no.nav.platforce.tool.security.TargetSecurityScan
import no.nav.platforce.tool.security.TargetSecurityStatus
import no.nav.platforce.tool.security.TransientDependencyUsage
import no.nav.platforce.tool.security.VersionSuggestion
import no.nav.platforce.tool.security.Vulnerability
import no.nav.platforce.tool.security.VulnerableDependency
import no.nav.platforce.tool.targets.TargetVersionsState
import java.time.Instant

object LocalFrontendFixtures {
    const val WEB = "example:web-client"
    const val JSON = "example:json-codec"
    const val LEGACY = "example:legacy-parser"
    const val UNUSED = "example:unused-override"

    val repositories =
        linkedMapOf(
            "demo/orders-api" to "demo-commerce",
            "demo/orders-worker" to "demo-commerce",
            "demo/new-service" to "demo-commerce",
            "demo/no-app-access" to "demo-commerce",
            "demo/customer-api" to "demo-platform",
            "demo/archived-service" to "demo-platform",
        )
    val installed = repositories.keys - "demo/no-app-access"

    fun targets() =
        TargetVersionsState(
            plugins = mutableMapOf("org.jetbrains.kotlin.jvm" to "2.2.0", "org.springframework.boot" to "3.5.0"),
            dependencies =
                mutableMapOf(
                    WEB to "3.0.0",
                    JSON to "2.0.0",
                    LEGACY to "1.0.0",
                    UNUSED to "1.0.0",
                ),
            gradleVersion = "8.14.4",
            transientDependencies = setOf(JSON, UNUSED),
        )

    fun inventory(repository: String): RepositoryDependencyInventory {
        require(repository in installed) { "No mock GitHub access to $repository" }
        return RepositoryDependencyInventory(
            repository = repository,
            scannedAt = Instant.now().toString(),
            plugins =
                mapOf(
                    "org.jetbrains.kotlin.jvm" to if (repository.endsWith("worker")) "2.1.0" else "2.2.0",
                    "org.springframework.boot" to "3.6.0",
                    "example.untracked-plugin" to "1.0.0",
                ),
            dependencies =
                buildMap {
                    put(WEB, if (repository.endsWith("worker")) "2.0.0" else "3.0.0")
                    put(LEGACY, "1.0.0")
                    put(UNUSED, "1.0.0")
                    put("example:untracked-library", "4.0.0")
                    if (repository.endsWith("customer-api")) put(JSON, "2.0.0")
                },
            gradleVersion = "8.13",
        )
    }

    fun resolved(
        key: String,
        version: String,
    ) = ResolvedDependency(key.substringBefore(":"), key.substringAfter(":"), version, version, emptyList())

    fun security(targets: TargetVersionsState): TargetSecurityScan {
        val vulnerability =
            Vulnerability(
                id = "DEMO-LOCAL-001",
                summary = "Synthetic vulnerability for frontend development, not a real security advisory.",
                aliases = emptyList(),
                references = emptyList(),
                fixedVersions = listOf("2.0.0"),
            )
        val results =
            targets.dependencies.map { (key, version) ->
                val vulnerable = key == LEGACY && version.startsWith("1.")
                val overridden = key == WEB && JSON in targets.dependencies
                TargetSecurityResult(
                    key = key,
                    targetVersion = version,
                    status =
                        when {
                            vulnerable -> TargetSecurityStatus.VULNERABLE
                            overridden -> TargetSecurityStatus.OK_OVERRIDDEN
                            key == UNUSED && key in targets.transientDependencies -> TargetSecurityStatus.TRANSIENT_UNUSED
                            key in targets.transientDependencies -> TargetSecurityStatus.OK_TRANSIENT
                            else -> TargetSecurityStatus.OK
                        },
                    vulnerabilities = if (vulnerable) listOf(vulnerability) else emptyList(),
                    vulnerableDependencies =
                        if (vulnerable) {
                            listOf(VulnerableDependency(resolved(key, version), listOf(vulnerability), VersionSuggestion(key, "2.0.0")))
                        } else {
                            emptyList()
                        },
                    relatedTo =
                        if (overridden) {
                            listOf(
                                OverrideReason(
                                    JSON,
                                    targets.dependencies.getValue(JSON),
                                    targets.dependencies.getValue(JSON),
                                    "1.0.0",
                                    listOf(WEB),
                                ),
                            )
                        } else {
                            emptyList()
                        },
                    transientUsage =
                        if (key == JSON && WEB in targets.dependencies) {
                            listOf(TransientDependencyUsage(WEB, targets.dependencies.getValue(WEB)))
                        } else {
                            emptyList()
                        },
                )
            }
        return TargetSecurityScan(emptyList(), results)
    }
}
