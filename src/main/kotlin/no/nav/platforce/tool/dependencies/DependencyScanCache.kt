package no.nav.platforce.tool.dependencies

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DependencyScanCache {
    private class UserScans {
        var inventories: List<RepositoryDependencyInventory> = emptyList()
        var progress = ScanProgress()
        val refreshLock = ReentrantLock()
    }

    private val users = ConcurrentHashMap<String, UserScans>()

    private fun forUser(username: String): UserScans = users.computeIfAbsent(username) { UserScans() }

    fun get(username: String): List<RepositoryDependencyInventory> {
        val user = forUser(username)
        return synchronized(user) { user.inventories }
    }

    fun update(
        username: String,
        inventories: List<RepositoryDependencyInventory>,
    ) {
        val user = forUser(username)
        synchronized(user) {
            user.inventories = inventories.map { it.copy(plugins = it.plugins.toMap(), dependencies = it.dependencies.toMap()) }
        }
    }

    fun updateRepository(
        username: String,
        inventory: RepositoryDependencyInventory,
    ) {
        val user = forUser(username)
        synchronized(user) {
            val copy = inventory.copy(plugins = inventory.plugins.toMap(), dependencies = inventory.dependencies.toMap())
            user.inventories =
                if (user.inventories.any { it.repository == copy.repository }) {
                    user.inventories.map { if (it.repository == copy.repository) copy else it }
                } else {
                    user.inventories + copy
                }
        }
    }

    fun getProgress(username: String): ScanProgress {
        val user = forUser(username)
        return synchronized(user) { user.progress }
    }

    fun setProgress(
        username: String,
        progress: ScanProgress,
    ) {
        val user = forUser(username)
        synchronized(user) { user.progress = progress }
    }

    fun <T> withRefreshLock(
        username: String,
        refresh: () -> T,
    ): T = forUser(username).refreshLock.withLock(refresh)
}
