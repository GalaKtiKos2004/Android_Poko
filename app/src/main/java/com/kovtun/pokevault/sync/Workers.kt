package com.kovtun.pokevault.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * How a worker turns a [SyncReport] into a WorkManager result:
 * complete -> success; anything failed (list, details or images) -> retry with backoff;
 * still incomplete after [maxAttempts] -> failure (periodic work then simply waits for the next period).
 * Retrying is cheap: fresh details and cached images are skipped.
 */
internal fun SyncReport.toWorkResult(runAttemptCount: Int, maxAttempts: Int, output: Data): ListenableWorker.Result =
    when {
        isComplete -> ListenableWorker.Result.success(output)
        runAttemptCount + 1 < maxAttempts -> ListenableWorker.Result.retry()
        else -> ListenableWorker.Result.failure(output)
    }

internal fun SyncReport.toOutputData(): Data = workDataOf(
    SyncWorker.KEY_UPDATED to detailsUpdated,
    SyncWorker.KEY_FAILED to failures,
)

/** Periodic background refresh of the list and of every pokemon users care about. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val report = engine.periodicSync()
        return report.toWorkResult(runAttemptCount, MAX_ATTEMPTS, report.toOutputData())
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
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_ATTEMPT to runAttemptCount))
        }
        val output = workDataOf(
            KEY_COUNT to count,
            KEY_DONE to report.detailsUpdated,
            KEY_FAILED to report.failures,
        )
        return report.toWorkResult(runAttemptCount, MAX_ATTEMPTS, output)
    }

    companion object {
        const val UNIQUE_NAME = "offline_prefetch"
        const val KEY_COUNT = "count"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val KEY_ATTEMPT = "attempt"
        const val DEFAULT_COUNT = 151
        const val MAX_ATTEMPTS = 5

        /** Tag that tells which amount a queued / running request downloads. */
        fun countTag(count: Int) = "prefetch_count_$count"
    }
}
