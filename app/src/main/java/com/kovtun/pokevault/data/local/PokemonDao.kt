package com.kovtun.pokevault.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PokemonDao {

    /**
     * The Pokédex list. Joined with the profile's statuses so that the status filter
     * and the list react to user changes without reloading.
     * Empty string parameters mean "no filter".
     */
    @Query(
        """
        SELECT p.* FROM pokemon p
        LEFT JOIN user_pokemon u ON u.pokemonId = p.id AND u.profileId = :profileId
        WHERE (:query = '' OR p.name LIKE '%' || :query || '%' OR CAST(p.id AS TEXT) = :query)
          AND (:type = '' OR p.types LIKE '%,' || :type || ',%')
          AND (:status = '' OR COALESCE(u.status, 'NONE') = :status)
        ORDER BY CASE WHEN :sort = 'NAME' THEN p.name END ASC, p.id ASC
        """,
    )
    abstract fun observeList(
        profileId: Long,
        query: String,
        type: String,
        status: String,
        sort: String,
    ): Flow<List<PokemonEntity>>

    @Query("SELECT * FROM pokemon WHERE id = :id")
    abstract fun observeById(id: Int): Flow<PokemonEntity?>

    @Query("SELECT * FROM pokemon WHERE id = :id")
    abstract suspend fun getById(id: Int): PokemonEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM pokemon WHERE name = :query OR CAST(id AS TEXT) = :query)")
    abstract suspend fun hasExactMatch(query: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(items: List<PokemonEntity>): List<Long>

    @Query("UPDATE pokemon SET name = :name, spriteUrl = :spriteUrl, listUpdatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateListInfo(id: Int, name: String, spriteUrl: String, updatedAt: Long)

    /** Inserts list rows without wiping already loaded details. */
    @Transaction
    open suspend fun upsertListItems(items: List<PokemonEntity>) {
        val results = insertIgnore(items)
        items.forEachIndexed { index, item ->
            if (results[index] == -1L) {
                updateListInfo(item.id, item.name, item.spriteUrl, item.listUpdatedAt)
            }
        }
    }

    @Upsert
    abstract suspend fun upsert(entity: PokemonEntity)

    @Query("SELECT COUNT(*) FROM pokemon")
    abstract fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pokemon")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM pokemon WHERE detailUpdatedAt IS NOT NULL")
    abstract fun observeDetailedCount(): Flow<Int>

    /** Highest id of the regular national dex (forms have ids >= 10000). */
    @Query("SELECT COALESCE(MAX(id), 0) FROM pokemon WHERE id < 10000")
    abstract suspend fun maxDexId(): Int

    /** Ids from 1..maxId whose details are missing or older than [staleBefore]. */
    @Query(
        """
        SELECT id FROM pokemon
        WHERE id <= :maxId AND (detailUpdatedAt IS NULL OR detailUpdatedAt < :staleBefore)
        ORDER BY id
        """,
    )
    abstract suspend fun idsNeedingDetails(maxId: Int, staleBefore: Long): List<Int>

    /** Pokemon any profile cares about (in a collection or with a status) whose details are stale. */
    @Query(
        """
        SELECT id FROM pokemon
        WHERE (id IN (SELECT pokemonId FROM collection_pokemon)
               OR id IN (SELECT pokemonId FROM user_pokemon WHERE status != 'NONE'))
          AND (detailUpdatedAt IS NULL OR detailUpdatedAt < :staleBefore)
        ORDER BY id
        """,
    )
    abstract suspend fun userReferencedIdsNeedingDetails(staleBefore: Long): List<Int>

    /** Clears the cache but keeps every pokemon some profile references. */
    @Query(
        """
        DELETE FROM pokemon
        WHERE id NOT IN (SELECT pokemonId FROM collection_pokemon)
          AND id NOT IN (SELECT pokemonId FROM user_pokemon)
          AND id NOT IN (SELECT pokemonId FROM history)
        """,
    )
    abstract suspend fun deleteUnreferenced(): Int
}
