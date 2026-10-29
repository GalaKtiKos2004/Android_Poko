package com.kovtun.pokevault.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kovtun.pokevault.core.ApplicationScope
import com.kovtun.pokevault.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PrefetchProgress {
    data object Idle : PrefetchProgress

    /** Waiting for constraints (network / battery) or for a retry after a partial failure. */
    data class Queued(val count: Int?, val retrying: Boolean) : PrefetchProgress
    data class Running(val count: Int?, val done: Int, val total: Int) : PrefetchProgress
    data class Finished(val updated: Int) : PrefetchProgress

    /** Still incomplete after every retry: [failed] items are not available offline. */
    data class Failed(val failed: Int) : PrefetchProgress
}

enum class PrefetchRequest {
    /** Nothing was queued — a new job started. */
    STARTED,

    /** Same amount and same network rule are already queued / running — kept as is. */
    ALREADY_RUNNING,

    /** A job with other parameters was replaced; already downloaded data is kept and skipped. */
    RESTARTED,
}

interface SyncScheduler {
    /** Keeps periodic work in line with settings for the whole app lifetime. */
    fun start()
    suspend fun prefetchNow(count: Int, wifiOnly: Boolean): PrefetchRequest
    fun observePrefetch(): Flow<PrefetchProgress>
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : SyncScheduler {

    override fun start() {
        scope.launch {
            settings.appSettings
                .map { it.backgroundSync to it.wifiOnly }
                .distinctUntilChanged()
                .collect { (enabled, wifiOnly) ->
                    if (enabled) schedulePeriodic(wifiOnly) else workManager.cancelUniqueWork(SyncWorker.UNIQUE_NAME)
                }
        }
    }

    private fun schedulePeriodic(wifiOnly: Boolean) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // UPDATE keeps the schedule but applies new constraints (e.g. Wi-Fi only toggled).
        workManager.enqueueUniquePeriodicWork(SyncWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * KEEP would silently ignore a request for another amount while a job is queued or running,
     * so the policy is chosen explicitly: identical request -> keep, different -> REPLACE.
     * Replacing loses nothing: every pokemon is saved as soon as it is downloaded and skipped next time.
     */
    override suspend fun prefetchNow(count: Int, wifiOnly: Boolean): PrefetchRequest {
        val requestTags = setOf(PrefetchWorker.countTag(count), wifiTag(wifiOnly))
        val active = workManager.getWorkInfosForUniqueWorkFlow(PrefetchWorker.UNIQUE_NAME)
            .first()
            .firstOrNull { !it.state.isFinished }
        if (active != null && active.tags.containsAll(requestTags)) return PrefetchRequest.ALREADY_RUNNING

        val builder = OneTimeWorkRequestBuilder<PrefetchWorker>()
            .setConstraints(constraints(wifiOnly))
            .setInputData(workDataOf(PrefetchWorker.KEY_COUNT to count))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
        for (tag in requestTags) builder.addTag(tag)
        val request = builder.build()
        workManager.enqueueUniqueWork(PrefetchWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        return if (active == null) PrefetchRequest.STARTED else PrefetchRequest.RESTARTED
    }

    override fun observePrefetch(): Flow<PrefetchProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(PrefetchWorker.UNIQUE_NAME)
            .map { infos -> (infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()).toProgress() }
            .distinctUntilChanged()

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    private fun wifiTag(wifiOnly: Boolean) = if (wifiOnly) "prefetch_wifi_only" else "prefetch_any_network"

    companion object {
        const val PERIOD_HOURS = 12L
    }
}

internal fun WorkInfo?.toProgress(): PrefetchProgress {
    val info = this ?: return PrefetchProgress.Idle
    val count = info.tags.firstNotNullOfOrNull { tag ->
        tag.removePrefix("prefetch_count_").takeIf { tag.startsWith("prefetch_count_") }?.toIntOrNull()
    }
    return when (info.state) {
        WorkInfo.State.CANCELLED -> PrefetchProgress.Idle
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
            PrefetchProgress.Queued(count, retrying = info.runAttemptCount > 0)
        WorkInfo.State.RUNNING -> PrefetchProgress.Running(
            count = count,
            done = info.progress.getInt(PrefetchWorker.KEY_DONE, 0),
            total = info.progress.getInt(PrefetchWorker.KEY_TOTAL, 0),
        )
        WorkInfo.State.SUCCEEDED -> PrefetchProgress.Finished(
            updated = info.outputData.getInt(PrefetchWorker.KEY_DONE, 0),
        )
        WorkInfo.State.FAILED -> PrefetchProgress.Failed(
            failed = info.outputData.getInt(PrefetchWorker.KEY_FAILED, 0),
        )
    }
}
