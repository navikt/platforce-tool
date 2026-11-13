package no.nav.platforce.tool.auto

import mu.KotlinLogging
import no.nav.platforce.tool.scanning.scanExecutor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AutoProcessService(
    private val store: AutoRunStore,
    private val workflow: AutoWorkflow,
    private val executor: ExecutorService = scanExecutor("auto-process"),
    private val heartbeatExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "auto-heartbeat").apply { isDaemon = true } },
    private val notify: (AutoRunNotification) -> Unit = {},
) {
    private val log = KotlinLogging.logger { }

    fun start(username: String): AutoRun? {
        val run = store.tryStart(username) ?: return null
        try {
            executor.execute { execute(run) }
        } catch (failure: RejectedExecutionException) {
            store.finish(run.id, AutoRunStatus.FAILED, AutoReport("Auto process could not be scheduled", failure.message))
            throw failure
        }
        return run
    }

    fun snapshot(before: Long? = null): AutoProcessSnapshot = store.snapshot(before)

    fun deleteRun(runId: String): AutoRunDeletion = store.deleteRun(runId)

    private fun execute(run: AutoRun) {
        val healthy = AtomicBoolean(true)
        val pullRequests = linkedSetOf<String>()
        var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            heartbeat =
                heartbeatExecutor.scheduleAtFixedRate({
                    try {
                        if (!store.heartbeat(run.id)) healthy.set(false)
                    } catch (failure: Exception) {
                        healthy.set(false)
                        log.error(failure) { "Could not renew Auto run ${run.id}" }
                    }
                }, 10, 10, TimeUnit.SECONDS)
            val progress =
                object : AutoProgress {
                    override fun checkActive() {
                        if (Thread.currentThread().isInterrupted || !healthy.get() || !store.heartbeat(run.id)) {
                            throw AutoInterrupted("Auto worker was interrupted or lost its global process lock")
                        }
                    }

                    override fun step(
                        name: String,
                        repository: String?,
                        action: () -> AutoReport,
                    ) {
                        checkActive()
                        store.startStep(run.id, name, repository)
                        val report = action()
                        checkActive()
                        store.report(run.id, report)
                        report.url?.let { pullRequests += it }
                    }
                }
            val report = workflow.run(run.username, progress)
            progress.checkActive()
            store.finish(run.id, AutoRunStatus.COMPLETED, report)
            if (pullRequests.isNotEmpty()) notifySafely(run, AutoRunNotification(pullRequests = pullRequests.toList()))
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            val status =
                when (failure) {
                    is AutoStopped -> AutoRunStatus.STOPPED
                    is AutoInterrupted, is InterruptedException, is java.util.concurrent.CancellationException -> AutoRunStatus.INTERRUPTED
                    else -> AutoRunStatus.FAILED
                }
            log.error(failure) { "Auto run ${run.id} ended with $status" }
            try {
                store.finish(
                    run.id,
                    status,
                    if (failure is AutoStopped) {
                        failure.report
                    } else {
                        AutoReport(
                            "Auto process ${status.name.lowercase()}; work is unfinished",
                            "${failure.message ?: failure.javaClass.simpleName}\nReview any created PRs before triggering another run.",
                        )
                    },
                )
                if (failure is AutoStopped && failure.vulnerabilitiesFound) {
                    notifySafely(run, AutoRunNotification(vulnerabilitiesFound = true))
                }
            } catch (persistenceFailure: Exception) {
                log.error(
                    persistenceFailure,
                ) { "Could not persist final status for Auto run ${run.id}; lease recovery will mark it interrupted" }
            }
        } finally {
            heartbeat?.cancel(false)
        }
    }

    private fun notifySafely(
        run: AutoRun,
        notification: AutoRunNotification,
    ) {
        try {
            notify(notification)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            log.error {
                "Could not deliver Slack report for Auto run ${run.id} (${failure.javaClass.simpleName}); run outcome is unchanged"
            }
        }
    }
}
