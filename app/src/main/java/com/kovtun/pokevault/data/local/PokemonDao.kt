package com.kovtun.pokevault.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A row of the server list together with its paging position. */
data class CatalogRow(val id: Int, val name: String, val spriteUrl: String, val index: Int)

@Dao
abstract class PokemonDao {

    /**
     * The Pokédex list. Joined with the profile's statuses so that the status filter
     * and the list react to user changes without reloading.
     * Empty string parameters mean "no filter". With [catalogOnly] only the contiguous
     * server catalogue is shown (no rows that came from search / type lists).
     */
    @Query(
        """
        SELECT p.* FROM pokemon p
        LEFT JOIN user_pokemon u ON u.pokemonId = p.id AND u.profileId = :profileId
        WHERE (:catalogOnly = 0 OR p.catalogIndex IS NOT NULL)
          AND (:query = '' OR p.name LIKE '%' || :query || '%' OR CAST(p.id AS TEXT) = :query)
          AND (:type = '' OR EXISTS (SELECT 1 FROM pokemon_types t WHERE t.pokemonId = p.id AND t.type = :type))
          AND (:status = '' OR COALESCE(u.status, 'NONE') = :status)
        ORDER BY CASE WHEN :sort = 'NAME' THEN p.name END ASC, p.id ASC
        """,
    )
    abstract fun observeList(
        profileId: Long,
        catalogOnly: Boolean,
        query: String,
        type: String,
        status: String,
        sort: String,
    ): Flow<List<PokemonEntity>>

    @Query("SELECT * FROM pokemon WHERE id = :id")
    abstract fun observeById(id: Int): Flow<PokemonEntity?>

    @Query("SELECT * FROM pokemon WHERE id = :id")
    abstract suspend fun getById(id: Int): PokemonEntity?

    /** [query] must already be normalised (see SearchQuery). */
    @Query("SELECT EXISTS(SELECT 1 FROM pokemon WHERE name = :query OR CAST(id AS TEXT) = :query)")
    abstract suspend fun hasExactMatch(query: String): Boolean

    // ------------------------------------------------------------------ list rows

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(item: PokemonEntity): Long

    /** Updates list info only; a null [catalogIndex] keeps the existing position. Details are untouched. */
    @Query(
        """
        UPDATE pokemon SET name = :name, spriteUrl = :spriteUrl, listUpdatedAt = :updatedAt,
            catalogIndex = COALESCE(:catalogIndex, catalogIndex)
        WHERE id = :id
        """,
    )
    abstract suspend fun updateListInfo(id: Int, name: String, spriteUrl: String, updatedAt: Long, catalogIndex: Int?)

    /** Positions [from, to] now belong to other pokemon (server list shifted) — free them. */
    @Query("UPDATE pokemon SET catalogIndex = NULL WHERE catalogIndex BETWEEN :from AND :to AND id NOT IN (:keepIds)")
    abstract suspend fun releaseCatalogRange(from: Int, to: Int, keepIds: List<Int>)

    /** Stores one page of the server list at its real offset. Never wipes details. */
    @Transaction
    open suspend fun saveCatalogPage(rows: List<CatalogRow>, now: Long) {
        if (rows.isEmpty()) return
        releaseCatalogRange(rows.minOf { it.index }, rows.maxOf { it.index }, rows.map { it.id })
        rows.forEach { row ->
            val entity = PokemonEntity(
                id = row.id, name = row.name, spriteUrl = row.spriteUrl,
                listUpdatedAt = now, catalogIndex = row.index,
            )
            if (insertIgnore(entity) == -1L) {
                updateListInfo(row.id, row.name, row.spriteUrl, now, row.index)
            }
        }
    }

    /** Rows that are not part of the catalogue (search / type list): keep any existing position. */
    @Transaction
    open suspend fun saveLooseRows(items: List<PokemonEntity>) {
        items.forEach { item ->
            if (insertIgnore(item.copy(catalogIndex = null)) == -1L) {
                updateListInfo(item.id, item.name, item.spriteUrl, item.listUpdatedAt, catalogIndex = null)
            }
        }
    }

    /**
     * Length of the contiguous catalogue prefix stored locally = offset of the next page.
     * A pokemon with a large id found through search does not move it, and neither do
     * rows kept after clearing the cache (their positions are released).
     */
    @Query(
        """
        SELECT CASE
            WHEN NOT EXISTS (SELECT 1 FROM pokemon WHERE catalogIndex = 0) THEN 0
            ELSE (SELECT MIN(p.catalogIndex) + 1 FROM pokemon p
                  WHERE p.catalogIndex IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM pokemon q WHERE q.catalogIndex = p.catalogIndex + 1))
        END
        """,
    )
    abstract fun observeCatalogLoaded(): Flow<Int>

    @Query(
        """
        SELECT CASE
            WHEN NOT EXISTS (SELECT 1 FROM pokemon WHERE catalogIndex = 0) THEN 0
            ELSE (SELECT MIN(p.catalogIndex) + 1 FROM pokemon p
                  WHERE p.catalogIndex IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM pokemon q WHERE q.catalogIndex = p.catalogIndex + 1))
        END
        """,
    )
    abstract suspend fun catalogLoaded(): Int

    // ------------------------------------------------------------------ details

    @Query(
        """
        UPDATE pokemon SET name = :name, spriteUrl = :spriteUrl, types = :types, heightDm = :heightDm,
            weightHg = :weightHg, baseExperience = :baseExperience, stats = :stats, abilities = :abilities,
            detailUpdatedAt = :detailUpdatedAt
        WHERE id = :id
        """,
    )
    abstract suspend fun updateDetailColumns(
        id: Int,
        name: String,
        spriteUrl: String,
        types: String?,
        heightDm: Int?,
        weightHg: Int?,
        baseExperience: Int?,
        stats: String?,
        abilities: String?,
        detailUpdatedAt: Long?,
    )

    @Query("DELETE FROM pokemon_types WHERE pokemonId = :pokemonId")
    abstract suspend fun deleteTypesOf(pokemonId: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTypes(types: List<PokemonTypeEntity>)

    /**
     * Saves details with column-level updates, so it can't overwrite a paging position or list
     * info written concurrently by a page load.
     */
    @Transaction
    open suspend fun saveDetail(entity: PokemonEntity, types: List<PokemonTypeEntity>) {
        insertIgnore(entity.copy(catalogIndex = null))
        updateDetailColumns(
            id = entity.id,
            name = entity.name,
            spriteUrl = entity.spriteUrl,
            types = entity.types,
            heightDm = entity.heightDm,
            weightHg = entity.weightHg,
            baseExperience = entity.baseExperience,
            stats = entity.stats,
            abilities = entity.abilities,
            detailUpdatedAt = entity.detailUpdatedAt,
        )
        deleteTypesOf(entity.id)
        insertTypes(types)
    }

    // ------------------------------------------------------------------ type index

    @Query("SELECT updatedAt FROM type_sync WHERE type = :type")
    abstract suspend fun typeSyncedAt(type: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setTypeSynced(entity: TypeSyncEntity)

    @Query("DELETE FROM pokemon_types WHERE type = :type AND pokemonId NOT IN (:ids)")
    abstract suspend fun deleteTypeMembersExcept(type: String, ids: List<Int>)

    /** Replaces the member list of a type downloaded from `/type/{name}`. */
    @Transaction
    open suspend fun saveTypeMembers(type: String, rows: List<PokemonEntity>, slots: Map<Int, Int>, now: Long) {
        saveLooseRows(rows)
        deleteTypeMembersExcept(type, rows.map { it.id })
        insertTypes(rows.map { PokemonTypeEntity(it.id, type, slots[it.id] ?: 1) })
        setTypeSynced(TypeSyncEntity(type, now))
    }

    @Query("DELETE FROM type_sync")
    abstract suspend fun clearTypeSync()

    // ------------------------------------------------------------------ stats / sync helpers

    @Query("SELECT COUNT(*) FROM pokemon")
    abstract fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pokemon")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM pokemon WHERE detailUpdatedAt IS NOT NULL")
    abstract fun observeDetailedCount(): Flow<Int>

    /** Ids of the first [limit] catalogue positions. */
    @Query("SELECT id FROM pokemon WHERE catalogIndex IS NOT NULL AND catalogIndex < :limit ORDER BY catalogIndex")
    abstract suspend fun catalogIds(limit: Int): List<Int>

    /** Of the first [limit] catalogue positions: ids whose details are missing or older than [staleBefore]. */
    @Query(
        """
        SELECT id FROM pokemon
        WHERE catalogIndex IS NOT NULL AND catalogIndex < :limit
          AND (detailUpdatedAt IS NULL OR detailUpdatedAt < :staleBefore)
        ORDER BY catalogIndex
        """,
    )
    abstract suspend fun catalogIdsNeedingDetails(limit: Int, staleBefore: Long): List<Int>

    /** Pokemon any profile cares about (in a collection or with a status). */
    @Query(
        """
        SELECT id FROM pokemon
        WHERE id IN (SELECT pokemonId FROM collection_pokemon)
           OR id IN (SELECT pokemonId FROM user_pokemon WHERE status != 'NONE')
        ORDER BY id
        """,
    )
    abstract suspend fun userReferencedIds(): List<Int>

    /** Of those: ids whose details are stale. */
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

    // ------------------------------------------------------------------ clearing

    /** Deletes every pokemon no profile references (type rows go with them via CASCADE). */
    @Query(
        """
        DELETE FROM pokemon
        WHERE id NOT IN (SELECT pokemonId FROM collection_pokemon)
          AND id NOT IN (SELECT pokemonId FROM user_pokemon)
          AND id NOT IN (SELECT pokemonId FROM history)
        """,
    )
    abstract suspend fun deleteUnreferenced(): Int

    @Query("UPDATE pokemon SET catalogIndex = NULL")
    abstract suspend fun releaseAllCatalogPositions()

    /**
     * Clears the cache but keeps everything users reference. The kept rows lose their paging
     * position, so the catalogue starts again from offset 0 instead of after the largest kept id.
     */
    @Transaction
    open suspend fun clearCache(): Int {
        val removed = deleteUnreferenced()
        releaseAllCatalogPositions()
        clearTypeSync()
        return removed
    }
}
