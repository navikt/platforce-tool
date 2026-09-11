package no.nav.platforce.tool.dependencies

data class DependencyResolutionCacheKey(
    val dependency: String,
    val version: String,
)

class DependencyResolutionCache {
    private val cache =
        mutableMapOf<DependencyResolutionCacheKey, TargetResolution>()

    fun get(key: DependencyResolutionCacheKey): TargetResolution? = cache[key]

    fun put(
        key: DependencyResolutionCacheKey,
        resolution: TargetResolution,
    ) {
        cache[key] = resolution
    }

    fun clear() {
        cache.clear()
    }
}
