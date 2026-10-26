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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PrefetchProgress {
    data object Idle : PrefetchProgress
    data object Queued : PrefetchProgress
    data class Running(val done: Int, val total: Int) : PrefetchProgress
    data class Finished(val updated: Int, val failed: Int) : PrefetchProgress
    data object Failed : PrefetchProgress
}

interface SyncScheduler {
    /** Keeps periodic work in line with settings for the whole app lifetime. */
    fun start()
    fun prefetchNow(count: Int, wifiOnly: Boolean)
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

    override fun prefetchNow(count: Int, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<PrefetchWorker>()
            .setConstraints(constraints(wifiOnly))
            .setInputData(workDataOf(PrefetchWorker.KEY_COUNT to count))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(PrefetchWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun observePrefetch(): Flow<PrefetchProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(PrefetchWorker.UNIQUE_NAME)
            .map { infos -> infos.lastOrNull().toProgress() }
            .distinctUntilChanged()

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    companion object {
        const val PERIOD_HOURS = 12L
    }
}

internal fun WorkInfo?.toProgress(): PrefetchProgress = when (this?.state) {
    null, WorkInfo.State.CANCELLED -> PrefetchProgress.Idle
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> PrefetchProgress.Queued
    WorkInfo.State.RUNNING -> PrefetchProgress.Running(
        done = progress.getInt(PrefetchWorker.KEY_DONE, 0),
        total = progress.getInt(PrefetchWorker.KEY_TOTAL, 0),
    )
    WorkInfo.State.SUCCEEDED -> PrefetchProgress.Finished(
        updated = outputData.getInt(PrefetchWorker.KEY_DONE, 0),
        failed = outputData.getInt(PrefetchWorker.KEY_FAILED, 0),
    )
    WorkInfo.State.FAILED -> PrefetchProgress.Failed
}
