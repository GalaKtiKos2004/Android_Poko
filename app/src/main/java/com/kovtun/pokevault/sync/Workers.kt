package com.kovtun.pokevault.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Periodic background refresh of the list and of every pokemon users care about. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val report = engine.periodicSync()
        return when {
            report.listFailed && runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            report.listFailed -> Result.failure()
            else -> Result.success(
                workDataOf(KEY_UPDATED to report.detailsUpdated, KEY_FAILED to report.detailsFailed),
            )
        }
    }

    companion object {
        const val UNIQUE_NAME = "periodic_sync"
        const val KEY_UPDATED = "updated"
        const val KEY_FAILED = "failed"
        const val MAX_ATTEMPTS = 3
    }
}

/** One-time "download for offline" job started from Settings. Reports progress. */
@HiltWorker
class PrefetchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val count = inputData.getInt(KEY_COUNT, DEFAULT_COUNT)
        val report = engine.prefetch(count) { done, total ->
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
        }
        if (report.listFailed) {
            return if (runAttemptCount < SyncWorker.MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
        return Result.success(
            workDataOf(KEY_DONE to report.detailsUpdated, KEY_FAILED to report.detailsFailed),
        )
    }

    companion object {
        const val UNIQUE_NAME = "offline_prefetch"
        const val KEY_COUNT = "count"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val DEFAULT_COUNT = 151
    }
}
