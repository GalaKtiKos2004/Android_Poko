package com.kovtun.pokevault.domain

data class PokemonSummary(
    val id: Int,
    val name: String,
    val spriteUrl: String,
    val types: List<String>,
    val hasDetails: Boolean,
)

data class PokemonStat(val name: String, val value: Int)

data class PokemonDetail(
    val id: Int,
    val name: String,
    val spriteUrl: String,
    val artworkUrl: String,
    val types: List<String>,
    val heightDm: Int,
    val weightHg: Int,
    val baseExperience: Int?,
    val stats: List<PokemonStat>,
    val abilities: List<String>,
    val updatedAt: Long,
)

enum class CatchStatus { NONE, WANT, CAUGHT }

enum class SortOrder { ID, NAME }

/** Everything that defines what the Pokédex list shows. Persisted as a [FilterPreset]. */
data class PokemonFilter(
    val query: String = "",
    val type: String? = null,
    val status: CatchStatus? = null,
    val sort: SortOrder = SortOrder.ID,
) {
    val isDefault: Boolean
        get() = query.isBlank() && type == null && status == null

    fun describe(): String = buildList {
        if (query.isNotBlank()) add("«${query.trim()}»")
        type?.let { add(it) }
        status?.let { add(it.label) }
        if (sort == SortOrder.NAME) add("A→Z")
    }.joinToString(" · ").ifEmpty { "Все" }
}

val CatchStatus.label: String
    get() = when (this) {
        CatchStatus.NONE -> "Нет статуса"
        CatchStatus.WANT -> "Хочу"
        CatchStatus.CAUGHT -> "Поймал"
    }

data class FilterPreset(
    val id: Long,
    val name: String,
    val filter: PokemonFilter,
)

data class Profile(
    val id: Long,
    val name: String,
    val colorIndex: Int,
    val createdAt: Long,
)

data class PokemonCollection(
    val id: Long,
    val name: String,
    val description: String,
    val pokemonCount: Int,
    val coverSpriteUrl: String?,
)

data class CollectionItem(
    val pokemonId: Int,
    val name: String,
    val spriteUrl: String,
    val types: List<String>,
    val note: String,
    val addedAt: Long,
)

data class HistoryEntry(
    val pokemonId: Int,
    val name: String,
    val spriteUrl: String,
    val viewedAt: Long,
    val viewCount: Int,
)

data class UserPokemonState(
    val status: CatchStatus = CatchStatus.NONE,
    val note: String = "",
)

data class CacheStats(
    val cachedCount: Int,
    val detailedCount: Int,
)

/**
 * Paging position of the national catalogue, derived from Room on every change.
 * [loaded] = length of the contiguous prefix of the server list stored locally;
 * [total] = list size reported by the server (null until the first page is loaded).
 */
data class CatalogState(
    val loaded: Int,
    val total: Int?,
) {
    val canLoadMore: Boolean get() = total == null || loaded < total
}

val POKEMON_TYPES = listOf(
    "normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground",
    "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy",
)
