package com.kovtun.pokevault.domain

import com.kovtun.pokevault.data.local.FilterPresetEntity
import com.kovtun.pokevault.data.local.PokemonEntity
import com.kovtun.pokevault.data.mapper.decodeStats
import com.kovtun.pokevault.data.mapper.idFromUrl
import com.kovtun.pokevault.data.mapper.toDetailOrNull
import com.kovtun.pokevault.data.mapper.toDomain
import com.kovtun.pokevault.data.mapper.toEntity
import com.kovtun.pokevault.data.remote.NamedResourceDto
import com.kovtun.pokevault.data.remote.PokemonDetailDto
import com.kovtun.pokevault.data.remote.StatDto
import com.kovtun.pokevault.data.remote.TypeSlotDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePolicyTest {

    private val ttl = CachePolicy.hoursToMillis(24)

    @Test
    fun `no timestamp means missing and must be fetched`() {
        assertEquals(Freshness.MISSING, CachePolicy.freshness(null, now = 10, ttlMillis = ttl))
        assertTrue(CachePolicy.shouldFetch(null, now = 10, ttlMillis = ttl, force = false))
    }

    @Test
    fun `entry younger than ttl is fresh and is not refetched unless forced`() {
        val now = 100 * ttl
        val updated = now - ttl + 1
        assertEquals(Freshness.FRESH, CachePolicy.freshness(updated, now, ttl))
        assertFalse(CachePolicy.shouldFetch(updated, now, ttl, force = false))
        assertTrue(CachePolicy.shouldFetch(updated, now, ttl, force = true))
    }

    @Test
    fun `entry exactly ttl old is already stale`() {
        val now = 100 * ttl
        assertEquals(Freshness.STALE, CachePolicy.freshness(now - ttl, now, ttl))
        assertEquals(now - ttl, CachePolicy.staleBefore(now, ttl))
    }
}

class NameValidatorTest {

    @Test
    fun `name is trimmed and inner whitespace collapsed`() {
        assertEquals("Dream Team", NameValidator.validate("  Dream    Team ", existing = emptyList()))
    }

    @Test
    fun `blank and too long names are rejected`() {
        assertThrows(NameError.Blank::class.java) { NameValidator.validate("   ", emptyList()) }
        assertThrows(NameError.TooLong::class.java) {
            NameValidator.validate("x".repeat(MAX_NAME_LENGTH + 1), emptyList())
        }
    }

    @Test
    fun `duplicates are detected case-insensitively`() {
        assertThrows(NameError.Duplicate::class.java) {
            NameValidator.validate("fire team", existing = listOf("Fire Team"))
        }
    }
}

class MappersTest {

    @Test
    fun `id is parsed from resource url with or without trailing slash`() {
        assertEquals(25, idFromUrl("https://pokeapi.co/api/v2/pokemon/25/"))
        assertEquals(10034, idFromUrl("https://pokeapi.co/api/v2/pokemon/10034"))
        assertNull(idFromUrl("https://pokeapi.co/api/v2/pokemon/"))
    }

    @Test
    fun `detail dto maps to entity with ordered types and keeps list timestamp`() {
        val dto = PokemonDetailDto(
            id = 1, name = "bulbasaur", height = 7, weight = 69, baseExperience = 64,
            types = listOf(
                TypeSlotDto(2, NamedResourceDto("poison", "")),
                TypeSlotDto(1, NamedResourceDto("grass", "")),
            ),
            stats = listOf(StatDto(45, NamedResourceDto("hp", ""))),
        )
        val existing = PokemonEntity(id = 1, name = "bulbasaur", spriteUrl = "s", listUpdatedAt = 5L)

        val entity = dto.toEntity(now = 99L, existing = existing)

        assertEquals(",grass,poison,", entity.types)
        assertEquals(5L, entity.listUpdatedAt)
        assertEquals(99L, entity.detailUpdatedAt)
        val detail = entity.toDetailOrNull()!!
        assertEquals(listOf("grass", "poison"), detail.types)
        assertEquals(listOf(PokemonStat("hp", 45)), detail.stats)
    }

    @Test
    fun `list-only row has no detail and broken stats are skipped`() {
        assertNull(PokemonEntity(id = 1, name = "a", spriteUrl = "s", listUpdatedAt = 1).toDetailOrNull())
        assertEquals(listOf(PokemonStat("hp", 1)), decodeStats("hp=1;broken;atk=x"))
    }

    @Test
    fun `preset with unknown enum values falls back to defaults`() {
        val preset = FilterPresetEntity(
            id = 1, profileId = 1, name = "x", query = "pi", type = "fire",
            status = "BROKEN", sort = "WHATEVER", createdAt = 0,
        ).toDomain()
        assertEquals(PokemonFilter(query = "pi", type = "fire", status = null, sort = SortOrder.ID), preset.filter)
    }
}

class SearchQueryTest {

    @Test
    fun `number with hash and leading zeros is the same as the plain number`() {
        assertEquals("25", SearchQuery.normalize("#025"))
        assertEquals("25", SearchQuery.normalize(" 025 "))
        assertEquals("25", SearchQuery.normalize("# 25"))
        assertEquals("0", SearchQuery.normalize("000"))
    }

    @Test
    fun `names are lowercased and spaces become dashes like in the API`() {
        assertEquals("mr-mime", SearchQuery.normalize("  Mr   Mime "))
        assertEquals("", SearchQuery.normalize("   "))
    }

    @Test
    fun `short numbers may be looked up on the server, short names may not`() {
        assertTrue(SearchQuery.isRemoteLookupAllowed("7"))
        assertTrue(SearchQuery.isRemoteLookupAllowed("pik"))
        assertFalse(SearchQuery.isRemoteLookupAllowed("pi"))
        assertFalse(SearchQuery.isRemoteLookupAllowed("0"))
    }
}

class CatalogStateTest {

    @Test
    fun `more pages exist until the stored prefix reaches the server total`() {
        assertTrue(CatalogState(loaded = 0, total = null).canLoadMore)
        assertTrue(CatalogState(loaded = 50, total = 120).canLoadMore)
        assertFalse(CatalogState(loaded = 120, total = 120).canLoadMore)
    }
}
