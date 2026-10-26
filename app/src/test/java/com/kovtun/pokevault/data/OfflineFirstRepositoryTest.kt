package com.kovtun.pokevault.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kovtun.pokevault.data.local.CollectionEntity
import com.kovtun.pokevault.data.local.CollectionPokemonEntity
import com.kovtun.pokevault.data.local.PokeVaultDatabase
import com.kovtun.pokevault.data.local.ProfileEntity
import com.kovtun.pokevault.data.repository.OfflineFirstPokemonRepository
import com.kovtun.pokevault.domain.PokemonFilter
import com.kovtun.pokevault.testutil.FakeClock
import com.kovtun.pokevault.testutil.FakePokeApi
import com.kovtun.pokevault.testutil.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Integration: Repository + fake API + real (in-memory) Room. */
@RunWith(AndroidJUnit4::class)
class OfflineFirstRepositoryTest {

    private lateinit var db: PokeVaultDatabase
    private val api = FakePokeApi()
    private val clock = FakeClock()
    private val settings = FakeSettingsRepository()
    private lateinit var repository: OfflineFirstPokemonRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PokeVaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = OfflineFirstPokemonRepository(api, db.pokemonDao(), settings, clock, Dispatchers.IO)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun list(filter: PokemonFilter = PokemonFilter()) = repository.observeList(1L, filter).first()

    @Test
    fun refreshedDataStaysAvailableWhenNetworkDisappears() = runTest {
        assertTrue(repository.refreshFirstPage(force = false).isSuccess)
        assertEquals(10, list().size)

        api.failAll = true
        val result = repository.refreshFirstPage(force = true)

        assertTrue(result.isFailure)
        assertEquals("cached rows must survive a failed refresh", 10, list().size)
    }

    @Test
    fun firstPageIsNotRefetchedWhileFreshButIsAfterTtl() = runTest {
        repository.refreshFirstPage(force = false)
        repository.refreshFirstPage(force = false)
        assertEquals(1, api.listCalls)

        clock.advanceHours(25)
        repository.refreshFirstPage(force = false)
        assertEquals(2, api.listCalls)
    }

    @Test
    fun detailRespectsTtlAndListRefreshDoesNotWipeDetails() = runTest {
        repository.refreshFirstPage(force = false)

        assertEquals(true, repository.refreshDetail(1, force = false).getOrNull())
        assertEquals("fresh detail comes from cache", false, repository.refreshDetail(1, force = false).getOrNull())
        assertEquals(1, api.detailCalls.size)

        repository.refreshFirstPage(force = true)
        val detail = repository.observeDetail(1).first()
        assertNotNull(detail)
        assertEquals(listOf("grass", "poison"), detail!!.types)

        clock.advanceHours(25)
        assertEquals(true, repository.refreshDetail(1, force = false).getOrNull())
        assertEquals(2, api.detailCalls.size)
    }

    @Test
    fun remoteLookupReturnsNullFor404AndCachesFoundPokemon() = runTest {
        assertNull(repository.lookupRemote("missingno").getOrThrow())

        val id = repository.lookupRemote("  Charmander ").getOrThrow()

        assertEquals(4, id)
        val found = list(PokemonFilter(query = "charm")).single()
        assertEquals(4, found.id)
        assertTrue(found.hasDetails)
        assertTrue(repository.hasLocalMatch("charmander"))
        assertFalse(repository.hasLocalMatch("pikachu"))
    }

    @Test
    fun clearCacheKeepsPokemonReferencedByUserData() = runTest {
        repository.refreshFirstPage(force = false)
        val profileId = db.profileDao().insert(ProfileEntity(name = "Ash", colorIndex = 0, createdAt = 0))
        val collectionId = db.collectionDao().insertCollection(
            CollectionEntity(profileId = profileId, name = "Team", description = "", createdAt = 0),
        )
        db.collectionDao().insertItem(CollectionPokemonEntity(collectionId, pokemonId = 7, addedAt = 0))

        val removed = repository.clearCache()

        assertEquals(9, removed)
        assertEquals(listOf(7), list().map { it.id })
        assertNull("TTL marker is reset", settings.app.value.lastListRefreshAt)
    }
}
