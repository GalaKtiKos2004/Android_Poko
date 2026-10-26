package com.kovtun.pokevault.sync

import com.kovtun.pokevault.core.TimeProvider
import com.kovtun.pokevault.core.isRemoteFailure
import com.kovtun.pokevault.data.local.PokemonDao
import com.kovtun.pokevault.data.mapper.toEntity
import com.kovtun.pokevault.data.mapper.toListEntity
import com.kovtun.pokevault.data.remote.PokeApi
import com.kovtun.pokevault.data.settings.SettingsRepository
import com.kovtun.pokevault.domain.CachePolicy
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class SyncReport(
    val listed: Int = 0,
    val detailsUpdated: Int = 0,
    val detailsFailed: Int = 0,
    val listFailed: Boolean = false,
) {
    val hadFailures: Boolean get() = listFailed || detailsFailed > 0

    operator fun plus(other: SyncReport) = SyncReport(
        listed = listed + other.listed,
        detailsUpdated = detailsUpdated + other.detailsUpdated,
        detailsFailed = detailsFailed + other.detailsFailed,
        listFailed = listFailed || other.listFailed,
    )
}

/**
 * All background data work, independent of WorkManager so it can be tested directly.
 * A failed request for one pokemon never aborts the whole run.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val api: PokeApi,
    private val dao: PokemonDao,
    private val settings: SettingsRepository,
    private val clock: TimeProvider,
) {

    /** Makes the first [count] pokemon fully available offline (list rows + details). */
    suspend fun prefetch(count: Int, onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): SyncReport {
        val listed = try {
            val page = api.getPokemonList(limit = count, offset = 0)
            val rows = page.results.mapNotNull { it.toListEntity(clock.now()) }
            dao.upsertListItems(rows)
            rows.size
        } catch (e: Exception) {
            if (!e.isRemoteFailure()) throw e
            return SyncReport(listFailed = true)
        }
        val ids = dao.idsNeedingDetails(maxId = count, staleBefore = staleBefore())
        return SyncReport(listed = listed) + fetchDetails(ids, onProgress)
    }

    /** Refreshes stale details of pokemon that users keep in collections / statuses. */
    suspend fun refreshUserReferenced(): SyncReport =
        fetchDetails(dao.userReferencedIdsNeedingDetails(staleBefore()))

    /** Periodic job: first page of the list + everything users care about. */
    suspend fun periodicSync(): SyncReport {
        val listReport = try {
            val now = clock.now()
            val page = api.getPokemonList(limit = PERIODIC_LIST_SIZE, offset = 0)
            val rows = page.results.mapNotNull { it.toListEntity(now) }
            dao.upsertListItems(rows)
            settings.setLastListRefreshAt(now)
            SyncReport(listed = rows.size)
        } catch (e: Exception) {
            if (!e.isRemoteFailure()) throw e
            SyncReport(listFailed = true)
        }
        val report = listReport + refreshUserReferenced()
        if (!report.listFailed) settings.setLastSyncAt(clock.now())
        return report
    }

    private suspend fun fetchDetails(
        ids: List<Int>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncReport {
        var updated = 0
        var failed = 0
        onProgress(0, ids.size)
        ids.forEachIndexed { index, id ->
            try {
                val dto = api.getPokemon(id.toString())
                dao.upsert(dto.toEntity(clock.now(), dao.getById(id)))
                updated++
            } catch (e: Exception) {
                if (!e.isRemoteFailure()) throw e
                failed++
            }
            onProgress(index + 1, ids.size)
        }
        return SyncReport(detailsUpdated = updated, detailsFailed = failed)
    }

    private suspend fun staleBefore(): Long {
        val ttl = CachePolicy.hoursToMillis(settings.appSettings.first().cacheTtlHours)
        return CachePolicy.staleBefore(clock.now(), ttl)
    }

    companion object {
        const val PERIODIC_LIST_SIZE = 50
    }
}
