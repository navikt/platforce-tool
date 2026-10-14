package no.nav.platforce.tool.workflows

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class QueuedExecutor : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var stopped = false

    val queuedTasks: Int get() = tasks.size

    fun runNext() = tasks.removeFirst().run()

    override fun execute(command: Runnable) {
        if (stopped) throw RejectedExecutionException("Test executor is shut down")
        tasks.addLast(command)
    }

    override fun shutdown() {
        stopped = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        stopped = true
        return tasks.toMutableList().also { tasks.clear() }
    }

    override fun isShutdown(): Boolean = stopped

    override fun isTerminated(): Boolean = stopped && tasks.isEmpty()

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = isTerminated
}
