package com.kovtun.pokevault.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kovtun.pokevault.data.local.CollectionEntity
import com.kovtun.pokevault.data.local.CollectionPokemonEntity
import com.kovtun.pokevault.data.local.PokeVaultDatabase
import com.kovtun.pokevault.data.local.ProfileEntity
import com.kovtun.pokevault.data.repository.OfflineFirstPokemonRepository
import com.kovtun.pokevault.domain.CatalogState
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

    /** 120 pokemon on the "server": pages of 50 -> 50, 50, 20. */
    private val api = FakePokeApi(FakePokeApi.generated(120))
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

    private suspend fun list(filter: PokemonFilter = PokemonFilter()) =
        repository.observeList(1L, filter).first()

    private suspend fun catalog() = repository.observeCatalog().first()

    @Test
    fun refreshedDataStaysAvailableWhenNetworkDisappears() = runTest {
        assertTrue(repository.refreshFirstPage(force = false).isSuccess)
        assertEquals(50, list().size)

        api.failAll = true
        val result = repository.refreshFirstPage(force = true)

        assertTrue(result.isFailure)
        assertEquals("cached rows must survive a failed refresh", 50, list().size)
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
    fun detailRespectsTtlAndNeitherListRefreshNorDetailSaveBreakEachOther() = runTest {
        repository.refreshFirstPage(force = false)

        assertEquals(true, repository.refreshDetail(1, force = false).getOrNull())
        assertEquals("fresh detail comes from cache", false, repository.refreshDetail(1, force = false).getOrNull())
        assertEquals(1, api.detailCalls.size)
        assertEquals("saving details keeps the paging position", 50, catalog().loaded)

        repository.refreshFirstPage(force = true)
        val detail = repository.observeDetail(1).first()
        assertNotNull("list refresh must not wipe details", detail)
        assertEquals(listOf(FakePokeApi.typeOf(1)), detail!!.types)

        clock.advanceHours(25)
        assertEquals(true, repository.refreshDetail(1, force = false).getOrNull())
        assertEquals(2, api.detailCalls.size)
    }

    @Test
    fun searchByNumberIsNormalisedLocallyAndRemotely() = runTest {
        assertNull("404 means not found", repository.lookupRemote("mon999").getOrThrow())

        assertEquals(4, repository.lookupRemote("#004").getOrThrow())
        assertEquals("server got the plain number", "4", api.detailCalls.last())

        assertTrue(repository.hasLocalMatch("#004"))
        assertTrue(repository.hasLocalMatch("004"))
        assertEquals(listOf(4), list(PokemonFilter(query = "#004")).map { it.id })
        assertFalse(repository.hasLocalMatch("5"))
    }

    @Test
    fun paginationContinuesFromTheCatalogueNotFromTheLargestIdFoundBySearch() = runTest {
        repository.refreshFirstPage(force = false)
        repository.lookupRemote("100").getOrThrow()

        assertEquals("search result is not part of the catalogue", CatalogState(50, 120), catalog())
        assertEquals((1..50).toList(), list().map { it.id })

        repository.loadNextPage().getOrThrow()

        assertEquals(listOf(0, 50), api.listOffsets)
        assertEquals((1..100).toList(), list().map { it.id })
        assertEquals(CatalogState(100, 120), catalog())
    }

    @Test
    fun afterClearingTheCachePagingStartsAgainEvenIfALargeIdIsKept() = runTest {
        repository.refreshFirstPage(force = false)
        repository.loadNextPage().getOrThrow()
        repository.loadNextPage().getOrThrow()
        assertFalse("whole catalogue loaded", catalog().canLoadMore)
        repository.loadNextPage().getOrThrow()
        assertEquals("no request past the end", 3, api.listCalls)

        val profileId = db.profileDao().insert(ProfileEntity(name = "Ash", colorIndex = 0, createdAt = 0))
        val collectionId = db.collectionDao().insertCollection(
            CollectionEntity(profileId = profileId, name = "Team", description = "", createdAt = 0),
        )
        db.collectionDao().insertItem(CollectionPokemonEntity(collectionId, pokemonId = 90, addedAt = 0))

        val removed = repository.clearCache()

        assertEquals(119, removed)
        assertEquals("kept pokemon is not a catalogue position", 0, catalog().loaded)
        assertTrue("load more is possible again", catalog().canLoadMore)
        assertNull("TTL marker is reset", settings.app.value.lastListRefreshAt)

        repository.refreshFirstPage(force = false)
        repository.loadNextPage().getOrThrow()
        assertEquals(listOf(0, 50), api.listOffsets.takeLast(2))
        assertEquals((1..100).toList(), list().map { it.id })
    }

    @Test
    fun typeFilterWorksOnACacheWithoutDetails() = runTest {
        repository.refreshFirstPage(force = false)
        val fire = PokemonFilter(type = "fire")
        assertTrue("no details yet -> types unknown locally", list(fire).isEmpty())

        assertEquals(true, repository.refreshType("fire", force = false).getOrNull())

        val expected = (1..120).filter { FakePokeApi.typeOf(it) == "fire" }
        val found = list(fire)
        assertEquals(expected, found.map { it.id })
        assertTrue(found.none { it.hasDetails })
        assertEquals("type members don't extend the catalogue", 50, catalog().loaded)

        assertEquals("fresh type list is not downloaded again", false, repository.refreshType("fire", false).getOrNull())
        assertEquals(1, api.typeCalls.size)
    }

    @Test
    fun clearCacheKeepsPokemonReferencedByUserData() = runTest {
        repository.refreshFirstPage(force = false)
        val profileId = db.profileDao().insert(ProfileEntity(name = "Ash", colorIndex = 0, createdAt = 0))
        val collectionId = db.collectionDao().insertCollection(
            CollectionEntity(profileId = profileId, name = "Team", description = "", createdAt = 0),
        )
        db.collectionDao().insertItem(CollectionPokemonEntity(collectionId, pokemonId = 7, addedAt = 0))

        repository.clearCache()

        assertEquals(listOf(7), list(PokemonFilter(query = "7")).map { it.id })
        assertEquals(1, db.pokemonDao().count())
    }
}
