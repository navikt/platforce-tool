package no.nav.platforce.tool.dependencies

class DependencyScanCache {
    @Volatile
    private var scans: List<RepositoryDependencyScan> = emptyList()

    @Volatile
    private var progress: ScanProgress = ScanProgress()

    private val repositoryScans =
        mutableMapOf<String, RepositoryDependencyScanCacheEntry>()

    fun get(): List<RepositoryDependencyScan> = scans

    fun update(scans: List<RepositoryDependencyScan>) {
        this.scans = scans
    }

    fun getRepositoryScan(repository: String): RepositoryDependencyScanCacheEntry? =
        synchronized(repositoryScans) {
            repositoryScans[repository]
        }

    fun putRepositoryScan(
        repository: String,
        entry: RepositoryDependencyScanCacheEntry,
    ) {
        synchronized(repositoryScans) {
            repositoryScans[repository] = entry
        }
    }

    fun getProgress(): ScanProgress = progress

    fun setProgress(progress: ScanProgress) {
        this.progress = progress
    }
}

data class RepositoryDependencyScanCacheEntry(
    val buildFileSha: String?,
    val wrapperFileSha: String?,
    val scan: RepositoryDependencyScan,
)
