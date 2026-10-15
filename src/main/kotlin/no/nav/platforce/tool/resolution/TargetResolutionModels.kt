package no.nav.platforce.tool.resolution

data class TargetResolution(
    val gradleVersion: String,
    val roots: List<ResolvedDependency>,
    val individual: Map<String, List<ResolvedDependency>>,
    val platformBoms: Map<String, PlatformBomResolution> = emptyMap(),
)

data class PlatformBomResolution(
    val version: String,
    val managed: List<ResolvedDependency>,
    val coverage: List<PlatformBomDependencyResolution>,
)

data class PlatformBomDependencyResolution(
    val dependency: String,
    val targetVersion: String,
    val enforcedPlatform: Boolean,
    val roots: List<ResolvedDependency>,
)

data class ResolvedDependency(
    val group: String?,
    val name: String,
    val version: String,
    val requestedVersion: String?,
    val dependencies: List<ResolvedDependency>,
) {
    val key: String
        get() = if (group != null) "$group:$name" else name
}

enum class ResolutionStatus {
    IDLE,
    RUNNING,
    READY,
    FAILED,
}

data class TargetResolutionSnapshot(
    val status: ResolutionStatus,
    val targetFingerprint: String?,
    val result: TargetResolution?,
    val error: String?,
)
