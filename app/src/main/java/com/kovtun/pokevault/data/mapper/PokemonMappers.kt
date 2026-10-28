package com.kovtun.pokevault.data.mapper

import com.kovtun.pokevault.data.local.CatalogRow
import com.kovtun.pokevault.data.local.CollectionItemRow
import com.kovtun.pokevault.data.local.CollectionWithCount
import com.kovtun.pokevault.data.local.FilterPresetEntity
import com.kovtun.pokevault.data.local.HistoryRow
import com.kovtun.pokevault.data.local.PokemonEntity
import com.kovtun.pokevault.data.local.PokemonTypeEntity
import com.kovtun.pokevault.data.local.ProfileEntity
import com.kovtun.pokevault.data.remote.NamedResourceDto
import com.kovtun.pokevault.data.remote.PokemonDetailDto
import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.domain.CollectionItem
import com.kovtun.pokevault.domain.FilterPreset
import com.kovtun.pokevault.domain.HistoryEntry
import com.kovtun.pokevault.domain.PokemonCollection
import com.kovtun.pokevault.domain.PokemonDetail
import com.kovtun.pokevault.domain.PokemonFilter
import com.kovtun.pokevault.domain.PokemonStat
import com.kovtun.pokevault.domain.PokemonSummary
import com.kovtun.pokevault.domain.Profile
import com.kovtun.pokevault.domain.SortOrder

object Sprites {
    private const val BASE = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon"
    fun sprite(id: Int) = "$BASE/$id.png"
    fun artwork(id: Int) = "$BASE/other/official-artwork/$id.png"
}

/** "https://pokeapi.co/api/v2/pokemon/25/" -> 25 */
fun idFromUrl(url: String): Int? = url.trimEnd('/').substringAfterLast('/').toIntOrNull()

fun encodeTypes(types: List<String>): String = types.joinToString(separator = ",", prefix = ",", postfix = ",")

fun decodeTypes(raw: String?): List<String> =
    raw?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

fun encodeStats(stats: List<PokemonStat>): String = stats.joinToString(";") { "${it.name}=${it.value}" }

fun decodeStats(raw: String?): List<PokemonStat> =
    raw?.split(';')?.mapNotNull { part ->
        val name = part.substringBefore('=', missingDelimiterValue = "")
        val value = part.substringAfter('=', missingDelimiterValue = "").toIntOrNull()
        if (name.isBlank() || value == null) null else PokemonStat(name, value)
    } ?: emptyList()

fun NamedResourceDto.toListEntity(now: Long): PokemonEntity? {
    val id = idFromUrl(url) ?: return null
    return PokemonEntity(id = id, name = name, spriteUrl = Sprites.sprite(id), listUpdatedAt = now)
}

/** Server list page -> rows with their paging positions (offset + index in page). */
fun List<NamedResourceDto>.toCatalogRows(offset: Int): List<CatalogRow> =
    mapIndexedNotNull { i, dto ->
        idFromUrl(dto.url)?.let { id -> CatalogRow(id, dto.name, Sprites.sprite(id), offset + i) }
    }

/** Types of a detail response, in slot order, for the pokemon_types index. */
fun PokemonDetailDto.toTypeEntities(): List<PokemonTypeEntity> =
    types.sortedBy { it.slot }.map { PokemonTypeEntity(id, it.type.name, it.slot) }

/**
 * Full entity from the detail endpoint. Keeps listUpdatedAt of an already cached row.
 * catalogIndex is never taken from here (see PokemonDao.saveDetail).
 */
fun PokemonDetailDto.toEntity(now: Long, existing: PokemonEntity?): PokemonEntity =
    PokemonEntity(
        id = id,
        name = name,
        spriteUrl = Sprites.sprite(id),
        types = encodeTypes(types.sortedBy { it.slot }.map { it.type.name }),
        heightDm = height,
        weightHg = weight,
        baseExperience = baseExperience,
        stats = encodeStats(stats.map { PokemonStat(it.stat.name, it.baseStat) }),
        abilities = abilities.map { it.ability.name }.joinToString(","),
        listUpdatedAt = existing?.listUpdatedAt ?: now,
        detailUpdatedAt = now,
    )

fun PokemonEntity.toSummary() = PokemonSummary(
    id = id,
    name = name,
    spriteUrl = spriteUrl,
    types = decodeTypes(types),
    hasDetails = detailUpdatedAt != null,
)

/** null when only the list row is cached. */
fun PokemonEntity.toDetailOrNull(): PokemonDetail? {
    val updatedAt = detailUpdatedAt ?: return null
    return PokemonDetail(
        id = id,
        name = name,
        spriteUrl = spriteUrl,
        artworkUrl = Sprites.artwork(id),
        types = decodeTypes(types),
        heightDm = heightDm ?: 0,
        weightHg = weightHg ?: 0,
        baseExperience = baseExperience,
        stats = decodeStats(stats),
        abilities = abilities?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
        updatedAt = updatedAt,
    )
}

fun ProfileEntity.toDomain() = Profile(id, name, colorIndex, createdAt)

fun CollectionWithCount.toDomain() = PokemonCollection(
    id = id,
    name = name,
    description = description,
    pokemonCount = pokemonCount,
    coverSpriteUrl = coverSprite,
)

fun CollectionItemRow.toDomain() = CollectionItem(
    pokemonId = pokemonId,
    name = name,
    spriteUrl = spriteUrl,
    types = decodeTypes(types),
    note = note,
    addedAt = addedAt,
)

fun HistoryRow.toDomain() = HistoryEntry(pokemonId, name, spriteUrl, viewedAt, viewCount)

fun FilterPresetEntity.toDomain() = FilterPreset(
    id = id,
    name = name,
    filter = PokemonFilter(
        query = query,
        type = type,
        status = status?.let { runCatching { CatchStatus.valueOf(it) }.getOrNull() },
        sort = runCatching { SortOrder.valueOf(sort) }.getOrDefault(SortOrder.ID),
    ),
)

fun String?.toCatchStatus(): CatchStatus =
    this?.let { runCatching { CatchStatus.valueOf(it) }.getOrNull() } ?: CatchStatus.NONE
