package com.kovtun.pokevault.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PokemonListResponseDto(
    val count: Int,
    val next: String? = null,
    val results: List<NamedResourceDto>,
)

@Serializable
data class NamedResourceDto(
    val name: String,
    val url: String,
)

@Serializable
data class PokemonDetailDto(
    val id: Int,
    val name: String,
    val height: Int,
    val weight: Int,
    @SerialName("base_experience") val baseExperience: Int? = null,
    val types: List<TypeSlotDto> = emptyList(),
    val stats: List<StatDto> = emptyList(),
    val abilities: List<AbilitySlotDto> = emptyList(),
)

@Serializable
data class TypeSlotDto(
    val slot: Int,
    val type: NamedResourceDto,
)

@Serializable
data class StatDto(
    @SerialName("base_stat") val baseStat: Int,
    val stat: NamedResourceDto,
)

@Serializable
data class AbilitySlotDto(
    val ability: NamedResourceDto,
    @SerialName("is_hidden") val isHidden: Boolean = false,
)
