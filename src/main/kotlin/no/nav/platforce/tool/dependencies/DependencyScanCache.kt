package no.nav.platforce.tool.dependencies

class DependencyScanCache {
    @Volatile
    private var scans: List<RepositoryDependencyScan> = emptyList()

    @Volatile
    private var progress: ScanProgress = ScanProgress()

    fun get(): List<RepositoryDependencyScan> = scans

    fun update(scans: List<RepositoryDependencyScan>) {
        this.scans = scans
    }

    fun getProgress(): ScanProgress = progress

    fun setProgress(progress: ScanProgress) {
        this.progress = progress
    }
}
