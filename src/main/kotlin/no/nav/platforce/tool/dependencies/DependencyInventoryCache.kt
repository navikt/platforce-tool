package no.nav.platforce.tool.dependencies

data class RepositoryDependencyInventory(
    val repository: String,
    val scannedAt: String,
    val plugins: List<ConfiguredDependency>,
    val dependencies: List<ConfiguredDependency>,
)

data class ConfiguredDependency(
    val key: String,
    val version: String,
)

object DependencyInventoryCache {
    private val inventory =
        mutableMapOf<String, RepositoryDependencyInventory>()

    fun put(item: RepositoryDependencyInventory) {
        inventory[item.repository] = item
    }

    fun get(repo: String): RepositoryDependencyInventory? = inventory[repo]

    fun all(): List<RepositoryDependencyInventory> = inventory.values.toList()
}
