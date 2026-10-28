package com.kovtun.pokevault.data.repository

import com.kovtun.pokevault.core.IoDispatcher
import com.kovtun.pokevault.core.TimeProvider
import com.kovtun.pokevault.core.remoteCall
import com.kovtun.pokevault.data.local.PokemonDao
import com.kovtun.pokevault.data.mapper.idFromUrl
import com.kovtun.pokevault.data.mapper.toCatalogRows
import com.kovtun.pokevault.data.mapper.toDetailOrNull
import com.kovtun.pokevault.data.mapper.toEntity
import com.kovtun.pokevault.data.mapper.toListEntity
import com.kovtun.pokevault.data.mapper.toSummary
import com.kovtun.pokevault.data.mapper.toTypeEntities
import com.kovtun.pokevault.data.remote.PokeApi
import com.kovtun.pokevault.data.settings.SettingsRepository
import com.kovtun.pokevault.domain.CachePolicy
import com.kovtun.pokevault.domain.CacheStats
import com.kovtun.pokevault.domain.CatalogState
import com.kovtun.pokevault.domain.PokemonDetail
import com.kovtun.pokevault.domain.PokemonFilter
import com.kovtun.pokevault.domain.PokemonSummary
import com.kovtun.pokevault.domain.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Paging position derived from what is actually stored — survives cache clearing and search. */
    fun observeCatalog(): Flow<CatalogState>

    /** Refreshes the first page if the catalogue is empty / stale or [force] is set. */
    suspend fun refreshFirstPage(force: Boolean): Result<Unit>

    /** Loads the page right after the stored contiguous catalogue. */
    suspend fun loadNextPage(): Result<Unit>

    /** Respects TTL unless [force]. Returns true if the network was actually hit. */
    suspend fun refreshDetail(id: Int, force: Boolean): Result<Boolean>

    /** Downloads the member list of a type (TTL unless [force]); true if the network was hit. */
    suspend fun refreshType(type: String, force: Boolean): Result<Boolean>

    /** [query] is normalised inside ("#025" == "25"). */
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

    /** Page writes are serialised so two loads can't compute the same offset. */
    private val pagingMutex = Mutex()

    override fun observeList(profileId: Long, filter: PokemonFilter): Flow<List<PokemonSummary>> =
        dao.observeList(
            profileId = profileId,
            catalogOnly = filter.isDefault,
            query = SearchQuery.normalize(filter.query),
            type = filter.type.orEmpty(),
            status = filter.status?.name.orEmpty(),
            sort = filter.sort.name,
        ).map { rows -> rows.map { it.toSummary() } }

    override fun observeDetail(id: Int): Flow<PokemonDetail?> =
        dao.observeById(id).map { it?.toDetailOrNull() }.distinctUntilChanged()

    override fun observeCacheStats(): Flow<CacheStats> =
        combine(dao.observeCount(), dao.observeDetailedCount()) { all, detailed -> CacheStats(all, detailed) }

    override fun observeCatalog(): Flow<CatalogState> =
        combine(
            dao.observeCatalogLoaded(),
            settings.appSettings.map { it.catalogTotal }.distinctUntilChanged(),
        ) { loaded, total -> CatalogState(loaded, total) }
            .distinctUntilChanged()

    override suspend fun refreshFirstPage(force: Boolean): Result<Unit> = withContext(io) {
        pagingMutex.withLock {
            val app = settings.appSettings.first()
            val ttl = CachePolicy.hoursToMillis(app.cacheTtlHours)
            val needed = force ||
                dao.catalogLoaded() == 0 ||
                CachePolicy.shouldFetch(app.lastListRefreshAt, clock.now(), ttl, force = false)
            if (!needed) return@withLock Result.success(Unit)

            remoteCall {
                val now = clock.now()
                val page = api.getPokemonList(limit = PAGE_SIZE, offset = 0)
                dao.saveCatalogPage(page.results.toCatalogRows(offset = 0), now)
                settings.setCatalogTotal(page.count)
                settings.setLastListRefreshAt(now)
            }
        }
    }

    override suspend fun loadNextPage(): Result<Unit> = withContext(io) {
        pagingMutex.withLock {
            val offset = dao.catalogLoaded()
            val total = settings.appSettings.first().catalogTotal
            if (total != null && offset >= total) return@withLock Result.success(Unit)
            remoteCall {
                val page = api.getPokemonList(limit = PAGE_SIZE, offset = offset)
                dao.saveCatalogPage(page.results.toCatalogRows(offset), clock.now())
                settings.setCatalogTotal(page.count)
            }
        }
    }

    override suspend fun refreshDetail(id: Int, force: Boolean): Result<Boolean> = withContext(io) {
        val cached = dao.getById(id)
        val ttl = CachePolicy.hoursToMillis(settings.appSettings.first().cacheTtlHours)
        if (!CachePolicy.shouldFetch(cached?.detailUpdatedAt, clock.now(), ttl, force)) {
            return@withContext Result.success(false)
        }
        remoteCall {
            val dto = api.getPokemon(id.toString())
            dao.saveDetail(dto.toEntity(clock.now(), cached), dto.toTypeEntities())
            true
        }
    }

    override suspend fun refreshType(type: String, force: Boolean): Result<Boolean> = withContext(io) {
        val ttl = CachePolicy.hoursToMillis(settings.appSettings.first().cacheTtlHours)
        if (!CachePolicy.shouldFetch(dao.typeSyncedAt(type), clock.now(), ttl, force)) {
            return@withContext Result.success(false)
        }
        remoteCall {
            val dto = api.getType(type)
            val now = clock.now()
            val rows = dto.pokemon.mapNotNull { it.pokemon.toListEntity(now) }
            val slots = dto.pokemon.mapNotNull { entry -> idFromUrl(entry.pokemon.url)?.let { it to entry.slot } }.toMap()
            dao.saveTypeMembers(type, rows, slots, now)
            true
        }
    }

    override suspend fun hasLocalMatch(query: String): Boolean = withContext(io) {
        val key = SearchQuery.normalize(query)
        key.isNotEmpty() && dao.hasExactMatch(key)
    }

    override suspend fun lookupRemote(query: String): Result<Int?> = withContext(io) {
        val key = SearchQuery.normalize(query)
        if (!SearchQuery.isRemoteLookupAllowed(key)) return@withContext Result.success(null)
        val result = remoteCall {
            val dto = api.getPokemon(key)
            dao.saveDetail(dto.toEntity(clock.now(), dao.getById(dto.id)), dto.toTypeEntities())
            dto.id
        }
        val error = result.exceptionOrNull()
        if (error is HttpException && error.code() == 404) Result.success(null) else result
    }

    override suspend fun clearCache(): Int = withContext(io) {
        pagingMutex.withLock {
            val removed = dao.clearCache()
            settings.setLastListRefreshAt(null)
            removed
        }
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
