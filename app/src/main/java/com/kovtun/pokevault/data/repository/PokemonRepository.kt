package com.kovtun.pokevault.data.repository

import com.kovtun.pokevault.core.IoDispatcher
import com.kovtun.pokevault.core.TimeProvider
import com.kovtun.pokevault.core.remoteCall
import com.kovtun.pokevault.data.local.PokemonDao
import com.kovtun.pokevault.data.mapper.toDetailOrNull
import com.kovtun.pokevault.data.mapper.toEntity
import com.kovtun.pokevault.data.mapper.toListEntity
import com.kovtun.pokevault.data.mapper.toSummary
import com.kovtun.pokevault.data.remote.PokeApi
import com.kovtun.pokevault.data.settings.SettingsRepository
import com.kovtun.pokevault.domain.CacheStats
import com.kovtun.pokevault.domain.CachePolicy
import com.kovtun.pokevault.domain.PokemonDetail
import com.kovtun.pokevault.domain.PokemonFilter
import com.kovtun.pokevault.domain.PokemonSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first access to Pokémon content.
 * Room is the single source of truth for every screen; the network only writes into Room.
 */
interface PokemonRepository {
    fun observeList(profileId: Long, filter: PokemonFilter): Flow<List<PokemonSummary>>
    fun observeDetail(id: Int): Flow<PokemonDetail?>
    fun observeCacheStats(): Flow<CacheStats>

    /** Refreshes the first page if the list is empty / stale or [force] is set. */
    suspend fun refreshFirstPage(force: Boolean): Result<Unit>

    /** Loads the next page; returns true when the server has more. */
    suspend fun loadNextPage(): Result<Boolean>

    /** Respects TTL unless [force]. Returns true if the network was actually hit. */
    suspend fun refreshDetail(id: Int, force: Boolean): Result<Boolean>

    suspend fun hasLocalMatch(query: String): Boolean

    /** Finds a pokemon by exact name / number on the server and caches it. null = not found. */
    suspend fun lookupRemote(query: String): Result<Int?>

    suspend fun clearCache(): Int
}

@Singleton
class OfflineFirstPokemonRepository @Inject constructor(
    private val api: PokeApi,
    private val dao: PokemonDao,
    private val settings: SettingsRepository,
    private val clock: TimeProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PokemonRepository {

    override fun observeList(profileId: Long, filter: PokemonFilter): Flow<List<PokemonSummary>> =
        dao.observeList(
            profileId = profileId,
            query = filter.query.trim().lowercase(),
            type = filter.type.orEmpty(),
            status = filter.status?.name.orEmpty(),
            sort = filter.sort.name,
        ).map { rows -> rows.map { it.toSummary() } }

    override fun observeDetail(id: Int): Flow<PokemonDetail?> =
        dao.observeById(id).map { it?.toDetailOrNull() }.distinctUntilChanged()

    override fun observeCacheStats(): Flow<CacheStats> =
        combine(dao.observeCount(), dao.observeDetailedCount()) { all, detailed -> CacheStats(all, detailed) }

    override suspend fun refreshFirstPage(force: Boolean): Result<Unit> = withContext(io) {
        val app = settings.appSettings.first()
        val ttl = CachePolicy.hoursToMillis(app.cacheTtlHours)
        val now = clock.now()
        val hasData = dao.count() > 0
        val needed = force || !hasData || CachePolicy.shouldFetch(app.lastListRefreshAt, now, ttl, force = false)
        if (!needed) return@withContext Result.success(Unit)

        remoteCall {
            val page = api.getPokemonList(limit = PAGE_SIZE, offset = 0)
            dao.upsertListItems(page.results.mapNotNull { it.toListEntity(now) })
            settings.setLastListRefreshAt(now)
        }
    }

    override suspend fun loadNextPage(): Result<Boolean> = withContext(io) {
        remoteCall {
            val offset = dao.maxDexId()
            val page = api.getPokemonList(limit = PAGE_SIZE, offset = offset)
            dao.upsertListItems(page.results.mapNotNull { it.toListEntity(clock.now()) })
            page.next != null
        }
    }

    override suspend fun refreshDetail(id: Int, force: Boolean): Result<Boolean> = withContext(io) {
        val cached = dao.getById(id)
        val ttl = CachePolicy.hoursToMillis(settings.appSettings.first().cacheTtlHours)
        val now = clock.now()
        if (!CachePolicy.shouldFetch(cached?.detailUpdatedAt, now, ttl, force)) {
            return@withContext Result.success(false)
        }
        remoteCall {
            val dto = api.getPokemon(id.toString())
            dao.upsert(dto.toEntity(now, cached))
            true
        }
    }

    override suspend fun hasLocalMatch(query: String): Boolean = withContext(io) {
        dao.hasExactMatch(normalize(query))
    }

    override suspend fun lookupRemote(query: String): Result<Int?> = withContext(io) {
        val key = normalize(query)
        if (key.isEmpty()) return@withContext Result.success(null)
        val result = remoteCall {
            val dto = api.getPokemon(key)
            val now = clock.now()
            dao.upsert(dto.toEntity(now, dao.getById(dto.id)))
            dto.id
        }
        val error = result.exceptionOrNull()
        if (error is HttpException && error.code() == 404) Result.success(null) else result.map { it }
    }

    override suspend fun clearCache(): Int = withContext(io) {
        val removed = dao.deleteUnreferenced()
        settings.setLastListRefreshAt(null)
        removed
    }

    private fun normalize(query: String) = query.trim().lowercase().removePrefix("#").trimStart('0').ifEmpty {
        // "#000" or "0" -> keep as is so it simply finds nothing
        query.trim().lowercase()
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
