package no.nav.platforce.tool.security

import no.nav.platforce.tool.resolution.ResolvedDependency
import org.http4k.core.Status.Companion.OK

data class Vulnerability(
    val id: String,
    val summary: String?,
    val aliases: List<String>,
    val references: List<String>,
    val fixedVersions: List<String>,
    val affectedRanges: List<VulnerabilityRange> = emptyList(),
)

data class VulnerabilityRange(
    val type: String?,
    val events: List<VulnerabilityEvent>,
)

data class VulnerabilityEvent(
    val introduced: String?,
    val fixed: String?,
    val lastAffected: String?,
)

data class ResolvedDependencySecurity(
    val group: String?,
    val name: String,
    val requestedVersion: String?,
    val resolvedVersion: String,
    val vulnerabilities: List<Vulnerability>,
)

data class VersionSuggestion(
    val dependency: String, // group:name
    val version: String,
)

data class VulnerableDependency(
    val dependency: ResolvedDependency,
    val vulnerabilities: List<Vulnerability>,
    val suggestedVersion: VersionSuggestion? = null,
)

enum class TargetSecurityStatus {
    OK,
    OK_OVERRIDDEN,
    VULNERABLE,

    // Explicitly configured as a transitive dependency
    // and still required to override a vulnerability.
    OK_TRANSIENT,

    // Explicitly configured as transitive, but no longer
    // referenced by an active override.
    TRANSIENT_UNUSED,
}

data class TransientDependencyUsage(
    val dependency: String,
    val targetVersion: String,
)

data class TargetSecurityResult(
    val key: String,
    val targetVersion: String,
    val status: TargetSecurityStatus,
    val vulnerabilities: List<Vulnerability>,
    val vulnerableDependencies: List<VulnerableDependency>,
    val relatedTo: List<OverrideReason>,
    val transientUsage: List<TransientDependencyUsage> = emptyList(),
    val replacementDependencies: List<TransientDependencyUsage> = emptyList(),
)

data class OverrideReason(
    val dependency: String,
    val targetVersion: String,
    val resolvedVersion: String,
    val vulnerableVersion: String? = null,
    val causedBy: List<String> = emptyList(),
    val suggestedVersion: String? = null,
)

data class TargetSecurityScan(
    val dependencies: List<ResolvedDependencySecurity>,
    val targets: List<TargetSecurityResult>,
    val platformBoms: List<PlatformBomSecurityResult> = emptyList(),
)

data class PlatformBomSecurityResult(
    val key: String,
    val targetVersion: String,
    val status: TargetSecurityStatus?,
    val vulnerableDependencies: List<VulnerableDependency>,
    val managedDependencyCount: Int,
    val coverage: List<PlatformBomCoverage>,
    val scope: String = "COVERAGE_ONLY",
)

data class PlatformBomCoverage(
    val dependency: String,
    val targetVersion: String,
    val enforcedPlatform: Boolean,
    val overrides: List<OverrideReason>,
)

enum class SecurityScanStatus {
    IDLE,
    RUNNING,
    READY,
    FAILED,
}

data class SecurityScanSnapshot(
    val status: SecurityScanStatus,
    val targetFingerprint: String?,
    val result: TargetSecurityScan?,
    val error: String?,
    val normalReady: Boolean = false,
    val bomReady: Boolean = false,
)
