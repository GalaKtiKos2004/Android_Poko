package com.kovtun.pokevault.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observeById(id: Long): Flow<ProfileEntity?>

    @Query("SELECT name FROM profiles")
    suspend fun allNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: ProfileEntity): Long

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CollectionDao {
    @Query(
        """
        SELECT c.id, c.name, c.description, c.createdAt,
               COUNT(cp.pokemonId) AS pokemonCount,
               (SELECT p.spriteUrl FROM collection_pokemon cp2
                  JOIN pokemon p ON p.id = cp2.pokemonId
                 WHERE cp2.collectionId = c.id
                 ORDER BY cp2.addedAt DESC LIMIT 1) AS coverSprite
        FROM collections c
        LEFT JOIN collection_pokemon cp ON cp.collectionId = c.id
        WHERE c.profileId = :profileId
        GROUP BY c.id
        ORDER BY c.createdAt DESC
        """,
    )
    fun observeForProfile(profileId: Long): Flow<List<CollectionWithCount>>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observeById(id: Long): Flow<CollectionEntity?>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getById(id: Long): CollectionEntity?

    @Query("SELECT name FROM collections WHERE profileId = :profileId")
    suspend fun names(profileId: Long): List<String>

    @Query(
        """
        SELECT p.id AS pokemonId, p.name AS name, p.spriteUrl AS spriteUrl, p.types AS types,
               cp.note AS note, cp.addedAt AS addedAt
        FROM collection_pokemon cp
        JOIN pokemon p ON p.id = cp.pokemonId
        WHERE cp.collectionId = :collectionId
        ORDER BY cp.addedAt DESC
        """,
    )
    fun observeItems(collectionId: Long): Flow<List<CollectionItemRow>>

    @Query(
        """
        SELECT cp.collectionId FROM collection_pokemon cp
        JOIN collections c ON c.id = cp.collectionId
        WHERE c.profileId = :profileId AND cp.pokemonId = :pokemonId
        """,
    )
    fun observeMembership(profileId: Long, pokemonId: Int): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCollection(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    /** Returns -1 when the pokemon is already in the collection (no duplicates). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: CollectionPokemonEntity): Long

    @Query("DELETE FROM collection_pokemon WHERE collectionId = :collectionId AND pokemonId = :pokemonId")
    suspend fun deleteItem(collectionId: Long, pokemonId: Int)

    @Query("UPDATE collection_pokemon SET note = :note WHERE collectionId = :collectionId AND pokemonId = :pokemonId")
    suspend fun updateItemNote(collectionId: Long, pokemonId: Int, note: String)
}

@Dao
interface UserPokemonDao {
    @Query("SELECT * FROM user_pokemon WHERE profileId = :profileId AND pokemonId = :pokemonId")
    fun observe(profileId: Long, pokemonId: Int): Flow<UserPokemonEntity?>

    @Query("SELECT * FROM user_pokemon WHERE profileId = :profileId AND pokemonId = :pokemonId")
    suspend fun get(profileId: Long, pokemonId: Int): UserPokemonEntity?

    @Query("SELECT * FROM user_pokemon WHERE profileId = :profileId AND status != 'NONE'")
    fun observeWithStatus(profileId: Long): Flow<List<UserPokemonEntity>>

    @Upsert
    suspend fun upsert(entity: UserPokemonEntity)

    @Query("DELETE FROM user_pokemon WHERE profileId = :profileId AND pokemonId = :pokemonId")
    suspend fun delete(profileId: Long, pokemonId: Int)
}

@Dao
abstract class HistoryDao {
    @Query(
        """
        SELECT h.pokemonId AS pokemonId, p.name AS name, p.spriteUrl AS spriteUrl,
               h.viewedAt AS viewedAt, h.viewCount AS viewCount
        FROM history h
        JOIN pokemon p ON p.id = h.pokemonId
        WHERE h.profileId = :profileId
        ORDER BY h.viewedAt DESC
        """,
    )
    abstract fun observe(profileId: Long): Flow<List<HistoryRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(entity: HistoryEntity): Long

    @Query(
        """
        UPDATE history SET viewedAt = :viewedAt, viewCount = viewCount + 1
        WHERE profileId = :profileId AND pokemonId = :pokemonId
        """,
    )
    abstract suspend fun bump(profileId: Long, pokemonId: Int, viewedAt: Long)

    @Query(
        """
        DELETE FROM history WHERE profileId = :profileId AND pokemonId NOT IN (
            SELECT pokemonId FROM history WHERE profileId = :profileId
            ORDER BY viewedAt DESC LIMIT :keep
        )
        """,
    )
    abstract suspend fun trim(profileId: Long, keep: Int)

    /** One row per (profile, pokemon): repeated views move it to the top and bump the counter. */
    @Transaction
    open suspend fun recordView(profileId: Long, pokemonId: Int, viewedAt: Long, keep: Int) {
        val inserted = insertIgnore(HistoryEntity(profileId, pokemonId, viewedAt, viewCount = 1))
        if (inserted == -1L) bump(profileId, pokemonId, viewedAt)
        trim(profileId, keep)
    }

    @Query("DELETE FROM history WHERE profileId = :profileId AND pokemonId = :pokemonId")
    abstract suspend fun delete(profileId: Long, pokemonId: Int)

    @Query("DELETE FROM history WHERE profileId = :profileId")
    abstract suspend fun clear(profileId: Long)
}

@Dao
interface FilterPresetDao {
    @Query("SELECT * FROM filter_presets WHERE profileId = :profileId ORDER BY createdAt ASC")
    fun observe(profileId: Long): Flow<List<FilterPresetEntity>>

    @Query("SELECT name FROM filter_presets WHERE profileId = :profileId")
    suspend fun names(profileId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: FilterPresetEntity): Long

    @Query("DELETE FROM filter_presets WHERE id = :id")
    suspend fun delete(id: Long)
}
