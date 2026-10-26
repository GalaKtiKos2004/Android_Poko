package com.kovtun.pokevault.testutil

import com.kovtun.pokevault.core.ConnectivityObserver
import com.kovtun.pokevault.core.TimeProvider
import com.kovtun.pokevault.data.remote.NamedResourceDto
import com.kovtun.pokevault.data.remote.PokeApi
import com.kovtun.pokevault.data.remote.PokemonDetailDto
import com.kovtun.pokevault.data.remote.PokemonListResponseDto
import com.kovtun.pokevault.data.remote.StatDto
import com.kovtun.pokevault.data.remote.TypeSlotDto
import com.kovtun.pokevault.data.repository.CollectionRepository
import com.kovtun.pokevault.data.repository.HistoryRepository
import com.kovtun.pokevault.data.repository.PokemonRepository
import com.kovtun.pokevault.data.repository.PresetRepository
import com.kovtun.pokevault.data.repository.UserPokemonRepository
import com.kovtun.pokevault.data.settings.AppSettings
import com.kovtun.pokevault.data.settings.ProfileSettings
import com.kovtun.pokevault.data.settings.SettingsRepository
import com.kovtun.pokevault.data.settings.ThemeMode
import com.kovtun.pokevault.domain.CacheStats
import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.domain.CollectionItem
import com.kovtun.pokevault.domain.FilterPreset
import com.kovtun.pokevault.domain.HistoryEntry
import com.kovtun.pokevault.domain.NameValidator
import com.kovtun.pokevault.domain.PokemonCollection
import com.kovtun.pokevault.domain.PokemonDetail
import com.kovtun.pokevault.domain.PokemonFilter
import com.kovtun.pokevault.domain.PokemonSummary
import com.kovtun.pokevault.domain.Profile
import com.kovtun.pokevault.domain.SessionRepository
import com.kovtun.pokevault.domain.SessionState
import com.kovtun.pokevault.domain.SortOrder
import com.kovtun.pokevault.domain.UserPokemonState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class FakeClock(var now: Long = 1_000_000_000L) : TimeProvider {
    override fun now(): Long = now
    fun advanceHours(hours: Int) {
        now += hours * 60L * 60L * 1000L
    }
}

class FakeConnectivity(initial: Boolean = true) : ConnectivityObserver {
    val online = MutableStateFlow(initial)
    override val isOnline: Flow<Boolean> = online
}

fun profile(id: Long, name: String = "P$id") = Profile(id, name, colorIndex = 0, createdAt = 0)

class FakeSessionRepository(initial: SessionState = SessionState.Active(profile(1))) : SessionRepository {
    val state = MutableStateFlow(initial)
    override val session: Flow<SessionState> = state
    override suspend fun switchTo(profileId: Long) {
        state.value = SessionState.Active(profile(profileId))
    }
    override suspend fun signOut() {
        state.value = SessionState.NoProfile
    }
}

class FakeSettingsRepository : SettingsRepository {
    val active = MutableStateFlow<Long?>(1L)
    val app = MutableStateFlow(AppSettings())
    private val perProfile = MutableStateFlow<Map<Long, ProfileSettings>>(emptyMap())

    override val activeProfileId: Flow<Long?> = active
    override val appSettings: Flow<AppSettings> = app
    override fun profileSettings(profileId: Long): Flow<ProfileSettings> =
        perProfile.map { it[profileId] ?: ProfileSettings() }

    private fun edit(profileId: Long, block: (ProfileSettings) -> ProfileSettings) =
        perProfile.update { it + (profileId to block(it[profileId] ?: ProfileSettings())) }

    override suspend fun setActiveProfile(profileId: Long?) { active.value = profileId }
    override suspend fun setCacheTtlHours(hours: Int) = app.update { it.copy(cacheTtlHours = hours) }
    override suspend fun setBackgroundSync(enabled: Boolean) = app.update { it.copy(backgroundSync = enabled) }
    override suspend fun setWifiOnly(enabled: Boolean) = app.update { it.copy(wifiOnly = enabled) }
    override suspend fun setPrefetchCount(count: Int) = app.update { it.copy(prefetchCount = count) }
    override suspend fun setLastSyncAt(time: Long) = app.update { it.copy(lastSyncAt = time) }
    override suspend fun setLastListRefreshAt(time: Long?) = app.update { it.copy(lastListRefreshAt = time) }
    override suspend fun setTheme(profileId: Long, mode: ThemeMode) = edit(profileId) { it.copy(theme = mode) }
    override suspend fun setDefaultSort(profileId: Long, sort: SortOrder) = edit(profileId) { it.copy(defaultSort = sort) }
    override suspend fun setHistoryEnabled(profileId: Long, enabled: Boolean) =
        edit(profileId) { it.copy(historyEnabled = enabled) }
    override suspend fun clearProfile(profileId: Long) = perProfile.update { it - profileId }
}

fun summary(id: Int, name: String, vararg types: String) =
    PokemonSummary(id, name, "sprite/$id", types.toList(), hasDetails = types.isNotEmpty())

fun detail(id: Int, name: String, updatedAt: Long = 0L) = PokemonDetail(
    id = id, name = name, spriteUrl = "s", artworkUrl = "a", types = listOf("electric"),
    heightDm = 4, weightHg = 60, baseExperience = 112, stats = emptyList(), abilities = emptyList(),
    updatedAt = updatedAt,
)

/**
 * In-memory PokemonRepository. Network behaviour is scripted with [refreshResults]
 * (consumed one per call) and [remoteDelayMs].
 */
class FakePokemonRepository(
    private val statuses: MutableStateFlow<Map<Long, Map<Int, CatchStatus>>> = MutableStateFlow(emptyMap()),
) : PokemonRepository {
    val all = MutableStateFlow<List<PokemonSummary>>(emptyList())
    val details = MutableStateFlow<Map<Int, PokemonDetail>>(emptyMap())

    /** Items that appear in [all] when a refresh succeeds. */
    var serverItems: List<PokemonSummary> = emptyList()
    val refreshResults = ArrayDeque<Result<Unit>>()
    var refreshCalls = 0
        private set
    var refreshGate: CompletableDeferred<Unit>? = null

    val observedFilters = mutableListOf<PokemonFilter>()

    var detailResults = ArrayDeque<Result<Boolean>>()
    val detailRefreshIds = mutableListOf<Pair<Int, Boolean>>()
    var serverDetail: PokemonDetail? = null

    var remoteDelayMs = 0L
    val remoteStarted = mutableListOf<String>()
    val remoteCompleted = mutableListOf<String>()
    var remoteResult: (String) -> Result<Int?> = { Result.success(null) }

    override fun observeList(profileId: Long, filter: PokemonFilter): Flow<List<PokemonSummary>> {
        observedFilters += filter
        return combine(all, statuses) { list, st ->
            val profileStatuses = st[profileId].orEmpty()
            list.filter { p ->
                (filter.query.isBlank() || p.name.contains(filter.query) || p.id.toString() == filter.query) &&
                    (filter.type == null || filter.type in p.types) &&
                    (filter.status == null || (profileStatuses[p.id] ?: CatchStatus.NONE) == filter.status)
            }.let { if (filter.sort == SortOrder.NAME) it.sortedBy { p -> p.name } else it.sortedBy { p -> p.id } }
        }
    }

    override fun observeDetail(id: Int): Flow<PokemonDetail?> = details.map { it[id] }

    override fun observeCacheStats(): Flow<CacheStats> = all.map { CacheStats(it.size, it.count { p -> p.hasDetails }) }

    override suspend fun refreshFirstPage(force: Boolean): Result<Unit> {
        refreshCalls++
        refreshGate?.await()
        val result = refreshResults.removeFirstOrNull() ?: Result.success(Unit)
        if (result.isSuccess) all.value = serverItems
        return result
    }

    override suspend fun loadNextPage(): Result<Boolean> = Result.success(false)

    override suspend fun refreshDetail(id: Int, force: Boolean): Result<Boolean> {
        detailRefreshIds += id to force
        val result = detailResults.removeFirstOrNull() ?: Result.success(true)
        if (result.getOrNull() == true) serverDetail?.let { d -> details.update { it + (id to d) } }
        return result
    }

    override suspend fun hasLocalMatch(query: String): Boolean = all.value.any { it.name == query }

    override suspend fun lookupRemote(query: String): Result<Int?> {
        remoteStarted += query
        delay(remoteDelayMs)
        remoteCompleted += query
        return remoteResult(query)
    }

    override suspend fun clearCache(): Int = 0
}

class FakeUserPokemonRepository(
    val statuses: MutableStateFlow<Map<Long, Map<Int, CatchStatus>>> = MutableStateFlow(emptyMap()),
) : UserPokemonRepository {
    private val notes = MutableStateFlow<Map<Pair<Long, Int>, String>>(emptyMap())

    override fun observe(profileId: Long, pokemonId: Int): Flow<UserPokemonState> =
        combine(statuses, notes) { s, n ->
            UserPokemonState(s[profileId]?.get(pokemonId) ?: CatchStatus.NONE, n[profileId to pokemonId].orEmpty())
        }

    override fun observeStatuses(profileId: Long): Flow<Map<Int, CatchStatus>> = statuses.map { it[profileId].orEmpty() }

    override suspend fun setStatus(profileId: Long, pokemonId: Int, status: CatchStatus) {
        statuses.update { all ->
            val current = all[profileId].orEmpty()
            val next = if (status == CatchStatus.NONE) current - pokemonId else current + (pokemonId to status)
            all + (profileId to next)
        }
    }

    override suspend fun setNote(profileId: Long, pokemonId: Int, note: String) {
        notes.update { it + ((profileId to pokemonId) to note) }
    }
}

class FakePresetRepository : PresetRepository {
    private val presets = MutableStateFlow<Map<Long, List<FilterPreset>>>(emptyMap())
    private var nextId = 1L

    override fun observe(profileId: Long): Flow<List<FilterPreset>> = presets.map { it[profileId].orEmpty() }

    override suspend fun save(profileId: Long, name: String, filter: PokemonFilter): Result<Long> = runCatching {
        val existing = presets.value[profileId].orEmpty()
        val valid = NameValidator.validate(name, existing.map { it.name })
        val preset = FilterPreset(nextId++, valid, filter)
        presets.update { it + (profileId to existing + preset) }
        preset.id
    }

    override suspend fun delete(presetId: Long) {
        presets.update { all -> all.mapValues { (_, list) -> list.filterNot { it.id == presetId } } }
    }
}

class FakeCollectionRepository : CollectionRepository {
    private val collections = MutableStateFlow<List<Pair<Long, PokemonCollection>>>(emptyList()) // profileId to collection
    private val members = MutableStateFlow<Set<Pair<Long, Int>>>(emptySet())
    private var nextId = 1L

    override fun observeCollections(profileId: Long): Flow<List<PokemonCollection>> =
        combine(collections, members) { list, m ->
            list.filter { it.first == profileId }.map { (_, c) -> c.copy(pokemonCount = m.count { it.first == c.id }) }
        }

    override fun observeCollectionName(collectionId: Long): Flow<String?> =
        collections.map { list -> list.firstOrNull { it.second.id == collectionId }?.second?.name }

    override fun observeItems(collectionId: Long): Flow<List<CollectionItem>> =
        members.map { m -> m.filter { it.first == collectionId }.map { CollectionItem(it.second, "p", "s", emptyList(), "", 0) } }

    override fun observeMembership(profileId: Long, pokemonId: Int): Flow<Set<Long>> =
        members.map { m -> m.filter { it.second == pokemonId }.map { it.first }.toSet() }

    override suspend fun createCollection(profileId: Long, name: String, description: String): Result<Long> = runCatching {
        val valid = NameValidator.validate(name, collections.value.filter { it.first == profileId }.map { it.second.name })
        val id = nextId++
        collections.update { it + (profileId to PokemonCollection(id, valid, description, 0, null)) }
        id
    }

    override suspend fun renameCollection(collectionId: Long, name: String): Result<Unit> = Result.success(Unit)
    override suspend fun deleteCollection(collectionId: Long) {
        collections.update { list -> list.filterNot { it.second.id == collectionId } }
    }

    override suspend fun addToCollection(collectionId: Long, pokemonId: Int): Boolean {
        val before = members.value
        members.update { it + (collectionId to pokemonId) }
        return before != members.value
    }

    override suspend fun removeFromCollection(collectionId: Long, pokemonId: Int) {
        members.update { it - (collectionId to pokemonId) }
    }

    override suspend fun updateNote(collectionId: Long, pokemonId: Int, note: String) = Unit
}

class FakeHistoryRepository : HistoryRepository {
    val views = mutableListOf<Pair<Long, Int>>()
    override fun observe(profileId: Long): Flow<List<HistoryEntry>> = MutableStateFlow(emptyList())
    override suspend fun recordView(profileId: Long, pokemonId: Int) {
        views += profileId to pokemonId
    }
    override suspend fun remove(profileId: Long, pokemonId: Int) = Unit
    override suspend fun clear(profileId: Long) = Unit
}

// ------------------------------------------------------------------ Fake network

fun httpError(code: Int): HttpException =
    HttpException(Response.error<Any>(code, "".toResponseBody(null)))

/** Fake PokeAPI backed by a list of names; ids are 1-based positions. */
class FakePokeApi(private val names: List<String> = DEFAULT_NAMES) : PokeApi {
    var listCalls = 0
        private set
    val detailCalls = mutableListOf<String>()
    var failList = false
    var failAll = false
    val failingIds = mutableSetOf<Int>()

    override suspend fun getPokemonList(limit: Int, offset: Int): PokemonListResponseDto {
        listCalls++
        if (failList || failAll) throw IOException("offline")
        val page = names.drop(offset).take(limit).mapIndexed { i, name ->
            NamedResourceDto(name, "https://pokeapi.co/api/v2/pokemon/${offset + i + 1}/")
        }
        val next = if (offset + limit < names.size) "next" else null
        return PokemonListResponseDto(count = names.size, next = next, results = page)
    }

    override suspend fun getPokemon(idOrName: String): PokemonDetailDto {
        detailCalls += idOrName
        if (failAll) throw IOException("offline")
        val id = idOrName.toIntOrNull() ?: (names.indexOf(idOrName) + 1).takeIf { it > 0 } ?: throw httpError(404)
        if (id !in 1..names.size) throw httpError(404)
        if (id in failingIds) throw IOException("flaky")
        return PokemonDetailDto(
            id = id,
            name = names[id - 1],
            height = 7,
            weight = 69,
            baseExperience = 64,
            types = listOf(
                TypeSlotDto(2, NamedResourceDto("poison", "")),
                TypeSlotDto(1, NamedResourceDto("grass", "")),
            ),
            stats = listOf(StatDto(45, NamedResourceDto("hp", "")), StatDto(49, NamedResourceDto("attack", ""))),
        )
    }

    companion object {
        val DEFAULT_NAMES = listOf(
            "bulbasaur", "ivysaur", "venusaur", "charmander", "charmeleon",
            "charizard", "squirtle", "wartortle", "blastoise", "caterpie",
        )
    }
}
