package com.kovtun.pokevault.sync

import com.kovtun.pokevault.core.TimeProvider
import com.kovtun.pokevault.core.isRemoteFailure
import com.kovtun.pokevault.data.local.PokemonDao
import com.kovtun.pokevault.data.mapper.Sprites
import com.kovtun.pokevault.data.mapper.toCatalogRows
import com.kovtun.pokevault.data.mapper.toEntity
import com.kovtun.pokevault.data.mapper.toTypeEntities
import com.kovtun.pokevault.data.remote.PokeApi
import com.kovtun.pokevault.data.settings.SettingsRepository
import com.kovtun.pokevault.domain.CachePolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

data class SyncReport(
    val listed: Int = 0,
    val detailsUpdated: Int = 0,
    val detailsFailed: Int = 0,
    val imagesFailed: Int = 0,
    val listFailed: Boolean = false,
) {
    /** Number of items that could not be brought up to date. */
    val failures: Int get() = detailsFailed + imagesFailed + if (listFailed) 1 else 0

    /** True only when everything that should be available offline actually is. */
    val isComplete: Boolean get() = failures == 0

    operator fun plus(other: SyncReport) = SyncReport(
        listed = listed + other.listed,
        detailsUpdated = detailsUpdated + other.detailsUpdated,
        detailsFailed = detailsFailed + other.detailsFailed,
        imagesFailed = imagesFailed + other.imagesFailed,
        listFailed = listFailed || other.listFailed,
    )
}

/**
 * All background data work, independent of WorkManager so it can be tested directly.
 *
 * - Requests run in parallel, limited to [CONCURRENCY] at a time (hundreds of sequential
 *   requests would hit the worker's execution limit on a slow network).
 * - Every pokemon is saved as soon as it is downloaded and fresh ones are skipped, so a retried
 *   or interrupted run continues where the previous one stopped.
 * - A failed request for one pokemon never aborts the run, but it is counted: the caller
 *   decides whether to retry, and "last sync" is stored only for a complete run.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val api: PokeApi,
    private val dao: PokemonDao,
    private val settings: SettingsRepository,
    private val images: ImagePrefetcher,
    private val clock: TimeProvider,
) {

    /** Makes the first [count] catalogue positions fully available offline: list, details, images. */
    suspend fun prefetch(
        count: Int,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncReport {
        val listReport = loadCatalogPrefix(count)
        if (listReport.listFailed) return listReport
        val ids = dao.catalogIds(count)
        val stale = dao.catalogIdsNeedingDetails(count, staleBefore()).toSet()
        return listReport + processAll(ids, stale, onProgress)
    }

    /** Pokemon users keep (collections / statuses): stale details and missing images. */
    suspend fun refreshUserReferenced(): SyncReport {
        val ids = dao.userReferencedIds()
        val stale = dao.userReferencedIdsNeedingDetails(staleBefore()).toSet()
        return processAll(ids, stale)
    }

    /** Periodic job: first page of the list + everything users care about. */
    suspend fun periodicSync(): SyncReport {
        val listReport = loadCatalogPrefix(PERIODIC_LIST_SIZE)
        if (!listReport.listFailed) settings.setLastListRefreshAt(clock.now())
        val report = listReport + refreshUserReferenced()
        settings.recordSyncAttempt(clock.now(), report.failures)
        return report
    }

    private suspend fun loadCatalogPrefix(count: Int): SyncReport = try {
        val page = api.getPokemonList(limit = count, offset = 0)
        val rows = page.results.toCatalogRows(offset = 0)
        dao.saveCatalogPage(rows, clock.now())
        settings.setCatalogTotal(page.count)
        SyncReport(listed = rows.size)
    } catch (e: Exception) {
        if (!e.isRemoteFailure()) throw e
        SyncReport(listFailed = true)
    }

    private suspend fun processAll(
        ids: List<Int>,
        staleIds: Set<Int>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncReport {
        val semaphore = Semaphore(CONCURRENCY)
        val progressLock = Mutex()
        var done = 0
        onProgress(0, ids.size)
        val reports = coroutineScope {
            ids.map { id ->
                async {
                    val report = semaphore.withPermit { processOne(id, needsDetails = id in staleIds) }
                    progressLock.withLock {
                        done++
                        onProgress(done, ids.size)
                    }
                    report
                }
            }.awaitAll()
        }
        return reports.fold(SyncReport()) { acc, r -> acc + r }
    }

    private suspend fun processOne(id: Int, needsDetails: Boolean): SyncReport {
        var report = SyncReport()
        if (needsDetails) {
            report = try {
                val dto = api.getPokemon(id.toString())
                dao.saveDetail(dto.toEntity(clock.now(), dao.getById(id)), dto.toTypeEntities())
                SyncReport(detailsUpdated = 1)
            } catch (e: Exception) {
                if (!e.isRemoteFailure()) throw e
                SyncReport(detailsFailed = 1)
            }
        }
        val missingImages = images.ensureCached(listOf(Sprites.sprite(id), Sprites.artwork(id)))
        return report + SyncReport(imagesFailed = missingImages)
    }

    private suspend fun staleBefore(): Long {
        val ttl = CachePolicy.hoursToMillis(settings.appSettings.first().cacheTtlHours)
        return CachePolicy.staleBefore(clock.now(), ttl)
    }

    companion object {
        const val PERIODIC_LIST_SIZE = 50
        const val CONCURRENCY = 8
    }
}
