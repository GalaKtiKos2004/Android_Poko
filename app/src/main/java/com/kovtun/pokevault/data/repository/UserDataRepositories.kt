package com.kovtun.pokevault.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.kovtun.pokevault.core.IoDispatcher
import com.kovtun.pokevault.core.TimeProvider
import com.kovtun.pokevault.data.local.CollectionDao
import com.kovtun.pokevault.data.local.CollectionEntity
import com.kovtun.pokevault.data.local.CollectionPokemonEntity
import com.kovtun.pokevault.data.local.FilterPresetDao
import com.kovtun.pokevault.data.local.FilterPresetEntity
import com.kovtun.pokevault.data.local.HistoryDao
import com.kovtun.pokevault.data.local.ProfileDao
import com.kovtun.pokevault.data.local.ProfileEntity
import com.kovtun.pokevault.data.local.UserPokemonDao
import com.kovtun.pokevault.data.mapper.toCatchStatus
import com.kovtun.pokevault.data.mapper.toDomain
import com.kovtun.pokevault.data.settings.SettingsRepository
import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.domain.CollectionItem
import com.kovtun.pokevault.domain.FilterPreset
import com.kovtun.pokevault.domain.HistoryEntry
import com.kovtun.pokevault.domain.NameError
import com.kovtun.pokevault.domain.NameValidator
import com.kovtun.pokevault.domain.PokemonCollection
import com.kovtun.pokevault.domain.PokemonFilter
import com.kovtun.pokevault.domain.Profile
import com.kovtun.pokevault.domain.SessionRepository
import com.kovtun.pokevault.domain.SessionState
import com.kovtun.pokevault.domain.UserPokemonState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------- Profiles

interface ProfileRepository {
    fun observeProfiles(): Flow<List<Profile>>
    suspend fun createProfile(name: String): Result<Long>
    suspend fun deleteProfile(profileId: Long)
}

@Singleton
class RoomProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val settings: SettingsRepository,
    private val clock: TimeProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ProfileRepository, SessionRepository {

    override fun observeProfiles(): Flow<List<Profile>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun createProfile(name: String): Result<Long> = withContext(io) {
        runCatching {
            val valid = NameValidator.validate(name, dao.allNames())
            val existing = dao.allNames().size
            try {
                dao.insert(ProfileEntity(name = valid, colorIndex = existing % PROFILE_COLORS, createdAt = clock.now()))
            } catch (e: SQLiteConstraintException) {
                throw NameError.Duplicate
            }
        }
    }

    /** Room cascades every personal table; DataStore keys of the profile are removed too. */
    override suspend fun deleteProfile(profileId: Long) = withContext(io) {
        dao.delete(profileId)
        settings.clearProfile(profileId)
        // Don't leave a dangling reference to the deleted profile in DataStore.
        settings.clearActiveProfileIf(profileId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val session: Flow<SessionState> = settings.activeProfileId.flatMapLatest { id ->
        if (id == null) {
            flowOf<SessionState>(SessionState.NoProfile)
        } else {
            dao.observeById(id).map { entity ->
                if (entity == null) SessionState.NoProfile else SessionState.Active(entity.toDomain())
            }
        }
    }.distinctUntilChanged()

    override suspend fun switchTo(profileId: Long) = settings.setActiveProfile(profileId)

    override suspend fun signOut() = settings.setActiveProfile(null)

    companion object {
        const val PROFILE_COLORS = 6
    }
}

// ---------------------------------------------------------------- Collections

interface CollectionRepository {
    fun observeCollections(profileId: Long): Flow<List<PokemonCollection>>
    fun observeCollectionName(collectionId: Long): Flow<String?>
    fun observeItems(collectionId: Long): Flow<List<CollectionItem>>
    fun observeMembership(profileId: Long, pokemonId: Int): Flow<Set<Long>>

    suspend fun createCollection(profileId: Long, name: String, description: String): Result<Long>
    suspend fun renameCollection(collectionId: Long, name: String): Result<Unit>
    suspend fun deleteCollection(collectionId: Long)

    /** false when the pokemon was already there (no duplicates). */
    suspend fun addToCollection(collectionId: Long, pokemonId: Int): Boolean
    suspend fun removeFromCollection(collectionId: Long, pokemonId: Int)
    suspend fun updateNote(collectionId: Long, pokemonId: Int, note: String)
}

@Singleton
class RoomCollectionRepository @Inject constructor(
    private val dao: CollectionDao,
    private val clock: TimeProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CollectionRepository {

    override fun observeCollections(profileId: Long): Flow<List<PokemonCollection>> =
        dao.observeForProfile(profileId).map { list -> list.map { it.toDomain() } }

    override fun observeCollectionName(collectionId: Long): Flow<String?> =
        dao.observeById(collectionId).map { it?.name }.distinctUntilChanged()

    override fun observeItems(collectionId: Long): Flow<List<CollectionItem>> =
        dao.observeItems(collectionId).map { list -> list.map { it.toDomain() } }

    override fun observeMembership(profileId: Long, pokemonId: Int): Flow<Set<Long>> =
        dao.observeMembership(profileId, pokemonId).map { it.toSet() }.distinctUntilChanged()

    override suspend fun createCollection(profileId: Long, name: String, description: String): Result<Long> =
        withContext(io) {
            runCatching {
                val valid = NameValidator.validate(name, dao.names(profileId))
                try {
                    dao.insertCollection(
                        CollectionEntity(
                            profileId = profileId,
                            name = valid,
                            description = description.trim(),
                            createdAt = clock.now(),
                        ),
                    )
                } catch (e: SQLiteConstraintException) {
                    throw NameError.Duplicate
                }
            }
        }

    override suspend fun renameCollection(collectionId: Long, name: String): Result<Unit> = withContext(io) {
        runCatching {
            val current = dao.getById(collectionId) ?: return@runCatching
            val others = dao.names(current.profileId).filterNot { it == current.name }
            dao.rename(collectionId, NameValidator.validate(name, others))
        }
    }

    override suspend fun deleteCollection(collectionId: Long) = withContext(io) {
        dao.deleteCollection(collectionId)
    }

    override suspend fun addToCollection(collectionId: Long, pokemonId: Int): Boolean = withContext(io) {
        dao.insertItem(CollectionPokemonEntity(collectionId, pokemonId, note = "", addedAt = clock.now())) != -1L
    }

    override suspend fun removeFromCollection(collectionId: Long, pokemonId: Int) = withContext(io) {
        dao.deleteItem(collectionId, pokemonId)
    }

    override suspend fun updateNote(collectionId: Long, pokemonId: Int, note: String) = withContext(io) {
        dao.updateItemNote(collectionId, pokemonId, note.trim())
    }
}

// ---------------------------------------------------------------- Status + notes

interface UserPokemonRepository {
    fun observe(profileId: Long, pokemonId: Int): Flow<UserPokemonState>
    fun observeStatuses(profileId: Long): Flow<Map<Int, CatchStatus>>
    suspend fun setStatus(profileId: Long, pokemonId: Int, status: CatchStatus)
    suspend fun setNote(profileId: Long, pokemonId: Int, note: String)
}

@Singleton
class RoomUserPokemonRepository @Inject constructor(
    private val dao: UserPokemonDao,
    private val clock: TimeProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
) : UserPokemonRepository {

    override fun observe(profileId: Long, pokemonId: Int): Flow<UserPokemonState> =
        dao.observe(profileId, pokemonId)
            .map { e -> if (e == null) UserPokemonState() else UserPokemonState(e.status.toCatchStatus(), e.note) }
            .distinctUntilChanged()

    override fun observeStatuses(profileId: Long): Flow<Map<Int, CatchStatus>> =
        dao.observeWithStatus(profileId)
            .map { list -> list.associate { it.pokemonId to it.status.toCatchStatus() } }
            .distinctUntilChanged()

    override suspend fun setStatus(profileId: Long, pokemonId: Int, status: CatchStatus) = withContext(io) {
        dao.setStatus(profileId, pokemonId, status.name, clock.now())
    }

    override suspend fun setNote(profileId: Long, pokemonId: Int, note: String) = withContext(io) {
        dao.setNote(profileId, pokemonId, note.trim(), clock.now())
    }
}

// ---------------------------------------------------------------- History

interface HistoryRepository {
    fun observe(profileId: Long): Flow<List<HistoryEntry>>
    suspend fun recordView(profileId: Long, pokemonId: Int)
    suspend fun remove(profileId: Long, pokemonId: Int)
    suspend fun clear(profileId: Long)
}

@Singleton
class RoomHistoryRepository @Inject constructor(
    private val dao: HistoryDao,
    private val clock: TimeProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
) : HistoryRepository {

    override fun observe(profileId: Long): Flow<List<HistoryEntry>> =
        dao.observe(profileId).map { list -> list.map { it.toDomain() } }

    override suspend fun recordView(profileId: Long, pokemonId: Int) = withContext(io) {
        dao.recordView(profileId, pokemonId, clock.now(), keep = MAX_HISTORY)
    }

    override suspend fun remove(profileId: Long, pokemonId: Int) = withContext(io) {
        dao.delete(profileId, pokemonId)
    }

    override suspend fun clear(profileId: Long) = withContext(io) { dao.clear(profileId) }

    companion object {
        const val MAX_HISTORY = 100
    }
}

// ---------------------------------------------------------------- Saved filters

interface PresetRepository {
    fun observe(profileId: Long): Flow<List<FilterPreset>>
    suspend fun save(profileId: Long, name: String, filter: PokemonFilter): Result<Long>
    suspend fun delete(presetId: Long)
}

@Singleton
class RoomPresetRepository @Inject constructor(
    private val dao: FilterPresetDao,
    private val clock: TimeProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PresetRepository {

    override fun observe(profileId: Long): Flow<List<FilterPreset>> =
        dao.observe(profileId).map { list -> list.map { it.toDomain() } }

    override suspend fun save(profileId: Long, name: String, filter: PokemonFilter): Result<Long> =
        withContext(io) {
            runCatching {
                val valid = NameValidator.validate(name, dao.names(profileId))
                try {
                    dao.insert(
                        FilterPresetEntity(
                            profileId = profileId,
                            name = valid,
                            query = filter.query.trim(),
                            type = filter.type,
                            status = filter.status?.name,
                            sort = filter.sort.name,
                            createdAt = clock.now(),
                        ),
                    )
                } catch (e: SQLiteConstraintException) {
                    throw NameError.Duplicate
                }
            }
        }

    override suspend fun delete(presetId: Long) = withContext(io) { dao.delete(presetId) }
}
