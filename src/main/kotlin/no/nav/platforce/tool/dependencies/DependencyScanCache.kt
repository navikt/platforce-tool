package no.nav.platforce.tool.dependencies

class DependencyScanCache {
    @Volatile
    private var scans: List<RepositoryDependencyScan> = emptyList()

    fun get(): List<RepositoryDependencyScan> = scans

    fun update(scans: List<RepositoryDependencyScan>) {
        this.scans = scans
    }
}
