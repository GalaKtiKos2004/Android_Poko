package com.kovtun.pokevault.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cache of API content. Not owned by any profile and never referenced by user tables,
 * so clearing the cache can't cascade into user data.
 *
 * [catalogIndex] is the position in the server's `/pokemon` list (paging position).
 * It is null for rows that came from search or from a type list and are not part of the
 * contiguous catalogue — paging never depends on pokemon ids.
 *
 * [types] (display only, ",grass,poison,"), [stats], [abilities] are null until details are loaded.
 * Filtering by type uses [PokemonTypeEntity], which is also filled from the lightweight type endpoint.
 */
@Entity(
    tableName = "pokemon",
    indices = [Index("catalogIndex")],
)
data class PokemonEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val spriteUrl: String,
    val types: String? = null,
    val heightDm: Int? = null,
    val weightHg: Int? = null,
    val baseExperience: Int? = null,
    val stats: String? = null,
    val abilities: String? = null,
    val listUpdatedAt: Long,
    val detailUpdatedAt: Long? = null,
    val catalogIndex: Int? = null,
)

/** pokemon <-> type, filled from details and from `/type/{name}` (works without details). */
@Entity(
    tableName = "pokemon_types",
    primaryKeys = ["pokemonId", "type"],
    foreignKeys = [
        ForeignKey(
            entity = PokemonEntity::class,
            parentColumns = ["id"],
            childColumns = ["pokemonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("type")],
)
data class PokemonTypeEntity(
    val pokemonId: Int,
    val type: String,
    val slot: Int,
)

/** When the membership list of a type was last downloaded (TTL for the type index). */
@Entity(tableName = "type_sync")
data class TypeSyncEntity(
    @PrimaryKey val type: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "profiles",
    indices = [Index(value = ["name"], unique = true)],
)
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorIndex: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "collections",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId", "name"], unique = true)],
)
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    val description: String,
    val createdAt: Long,
)

/** Many-to-many link collection <-> pokemon with a per-link note. */
@Entity(
    tableName = "collection_pokemon",
    primaryKeys = ["collectionId", "pokemonId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pokemonId")],
)
data class CollectionPokemonEntity(
    val collectionId: Long,
    val pokemonId: Int,
    val note: String = "",
    val addedAt: Long,
)

/** Per-profile status ("want" / "caught") and a personal note for a pokemon. */
@Entity(
    tableName = "user_pokemon",
    primaryKeys = ["profileId", "pokemonId"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pokemonId")],
)
data class UserPokemonEntity(
    val profileId: Long,
    val pokemonId: Int,
    val status: String,
    val note: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "history",
    primaryKeys = ["profileId", "pokemonId"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pokemonId")],
)
data class HistoryEntity(
    val profileId: Long,
    val pokemonId: Int,
    val viewedAt: Long,
    val viewCount: Int,
)

@Entity(
    tableName = "filter_presets",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId", "name"], unique = true)],
)
data class FilterPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    val query: String,
    val type: String?,
    val status: String?,
    val sort: String,
    val createdAt: Long,
)

// ---- Query projections ----

data class CollectionWithCount(
    val id: Long,
    val name: String,
    val description: String,
    val createdAt: Long,
    val pokemonCount: Int,
    val coverSprite: String?,
)

data class CollectionItemRow(
    val pokemonId: Int,
    val name: String,
    val spriteUrl: String,
    val types: String?,
    val note: String,
    val addedAt: Long,
)

data class HistoryRow(
    val pokemonId: Int,
    val name: String,
    val spriteUrl: String,
    val viewedAt: Long,
    val viewCount: Int,
)
