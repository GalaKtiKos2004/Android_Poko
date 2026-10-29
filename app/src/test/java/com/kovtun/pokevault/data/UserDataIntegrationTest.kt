package com.kovtun.pokevault.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kovtun.pokevault.data.local.CatalogRow
import com.kovtun.pokevault.data.local.PokeVaultDatabase
import com.kovtun.pokevault.data.repository.OfflineFirstPokemonRepository
import com.kovtun.pokevault.data.repository.RoomCollectionRepository
import com.kovtun.pokevault.data.repository.RoomHistoryRepository
import com.kovtun.pokevault.data.repository.RoomPresetRepository
import com.kovtun.pokevault.data.repository.RoomProfileRepository
import com.kovtun.pokevault.data.repository.RoomUserPokemonRepository
import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.domain.NameError
import com.kovtun.pokevault.domain.PokemonFilter
import com.kovtun.pokevault.domain.SessionState
import com.kovtun.pokevault.testutil.FakeClock
import com.kovtun.pokevault.testutil.FakePokeApi
import com.kovtun.pokevault.testutil.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Integration: user-data repositories on top of real Room (profiles, collections, statuses, history, presets). */
@RunWith(AndroidJUnit4::class)
class UserDataIntegrationTest {

    private lateinit var db: PokeVaultDatabase
    private val clock = FakeClock()
    private val settings = FakeSettingsRepository()
    private lateinit var profiles: RoomProfileRepository
    private lateinit var collections: RoomCollectionRepository
    private lateinit var userPokemon: RoomUserPokemonRepository
    private lateinit var history: RoomHistoryRepository
    private lateinit var presets: RoomPresetRepository
    private lateinit var pokemon: OfflineFirstPokemonRepository

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PokeVaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val io = Dispatchers.IO
        profiles = RoomProfileRepository(db.profileDao(), settings, clock, io)
        collections = RoomCollectionRepository(db.collectionDao(), clock, io)
        userPokemon = RoomUserPokemonRepository(db.userPokemonDao(), clock, io)
        history = RoomHistoryRepository(db.historyDao(), clock, io)
        presets = RoomPresetRepository(db.filterPresetDao(), clock, io)
        pokemon = OfflineFirstPokemonRepository(FakePokeApi(), db.pokemonDao(), settings, clock, io)
        db.pokemonDao().saveCatalogPage((1..5).map { CatalogRow(it, "p$it", "s", index = it - 1) }, now = 0)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun dataOfOneProfileIsInvisibleToAnother() = runTest {
        val ash = profiles.createProfile("Ash").getOrThrow()
        val misty = profiles.createProfile("Misty").getOrThrow()

        val team = collections.createCollection(ash, "Team", "").getOrThrow()
        collections.addToCollection(team, 1)
        userPokemon.setStatus(ash, 1, CatchStatus.CAUGHT)
        history.recordView(ash, 1)
        presets.save(ash, "Caught", PokemonFilter(status = CatchStatus.CAUGHT))

        assertTrue(collections.observeCollections(misty).first().isEmpty())
        assertTrue(userPokemon.observeStatuses(misty).first().isEmpty())
        assertTrue(history.observe(misty).first().isEmpty())
        assertTrue(presets.observe(misty).first().isEmpty())

        val caught = PokemonFilter(status = CatchStatus.CAUGHT)
        assertEquals(listOf(1), pokemon.observeList(ash, caught).first().map { it.id })
        assertTrue(pokemon.observeList(misty, caught).first().isEmpty())
    }

    @Test
    fun deletingActiveProfileCascadesItsDataAndEndsSession() = runTest {
        val ash = profiles.createProfile("Ash").getOrThrow()
        profiles.switchTo(ash)
        assertTrue(profiles.session.first() is SessionState.Active)
        collections.addToCollection(collections.createCollection(ash, "Team", "").getOrThrow(), 2)
        userPokemon.setStatus(ash, 3, CatchStatus.WANT)
        history.recordView(ash, 4)

        profiles.deleteProfile(ash)

        assertEquals(SessionState.NoProfile, profiles.session.first())
        assertNull("no dangling active id in DataStore", settings.active.value)
        assertTrue(collections.observeCollections(ash).first().isEmpty())
        assertNull(db.userPokemonDao().get(ash, 3))
        assertTrue(history.observe(ash).first().isEmpty())
        assertEquals("cached pokemon are not user data and stay", 5, db.pokemonDao().count())
    }

    @Test
    fun addingTheSamePokemonTwiceDoesNotCreateDuplicate() = runTest {
        val ash = profiles.createProfile("Ash").getOrThrow()
        val team = collections.createCollection(ash, "Team", "").getOrThrow()

        assertTrue(collections.addToCollection(team, 1))
        assertFalse(collections.addToCollection(team, 1))

        assertEquals(1, collections.observeItems(team).first().size)
        assertEquals(1, collections.observeCollections(ash).first().single().pokemonCount)
        assertEquals(setOf(team), collections.observeMembership(ash, 1).first())
    }

    @Test
    fun historyKeepsOneRowPerPokemonOrderedByLastView() = runTest {
        val ash = profiles.createProfile("Ash").getOrThrow()

        history.recordView(ash, 1)
        clock.now += 1_000
        history.recordView(ash, 2)
        clock.now += 1_000
        history.recordView(ash, 1)

        val entries = history.observe(ash).first()
        assertEquals(listOf(1, 2), entries.map { it.pokemonId })
        assertEquals(listOf(2, 1), entries.map { it.viewCount })
    }

    @Test
    fun duplicateNamesAreRejectedPerProfile() = runTest {
        val ash = profiles.createProfile("Ash").getOrThrow()
        val misty = profiles.createProfile("Misty").getOrThrow()
        collections.createCollection(ash, "Team", "").getOrThrow()

        val duplicate = collections.createCollection(ash, "team", "")
        val otherProfile = collections.createCollection(misty, "Team", "")

        assertTrue(duplicate.exceptionOrNull() is NameError.Duplicate)
        assertTrue("same name is fine for another profile", otherProfile.isSuccess)
        assertTrue(profiles.createProfile(" ash ").exceptionOrNull() is NameError.Duplicate)
    }

    @Test
    fun statusAndNoteAreStoredTogetherAndEmptyEntryIsRemoved() = runTest {
        val ash = profiles.createProfile("Ash").getOrThrow()

        userPokemon.setStatus(ash, 1, CatchStatus.CAUGHT)
        userPokemon.setNote(ash, 1, "  shiny  ")
        assertEquals(CatchStatus.CAUGHT, userPokemon.observe(ash, 1).first().status)
        assertEquals("shiny", userPokemon.observe(ash, 1).first().note)

        userPokemon.setStatus(ash, 1, CatchStatus.NONE)
        assertTrue("note keeps the row alive", db.userPokemonDao().get(ash, 1) != null)
        userPokemon.setNote(ash, 1, "")
        assertNull(db.userPokemonDao().get(ash, 1))
    }

    @Test
    fun deletingAnotherProfileKeepsTheActiveOne() = runTest {
        val ash = profiles.createProfile("Ash").getOrThrow()
        val misty = profiles.createProfile("Misty").getOrThrow()
        profiles.switchTo(ash)

        profiles.deleteProfile(misty)

        assertEquals(ash, settings.active.value)
        assertEquals(ash, (profiles.session.first() as SessionState.Active).profile.id)
    }

    @Test
    fun simultaneousStatusChangeAndNoteSaveKeepBothFields() = runTest {
        val ash = profiles.createProfile("Ash").getOrThrow()
        val ids = 1..300

        // Real threads: each pokemon gets a status and a note written at the same time.
        ids.flatMap { id ->
            listOf(
                async(Dispatchers.IO) { userPokemon.setStatus(ash, id, CatchStatus.CAUGHT) },
                async(Dispatchers.IO) { userPokemon.setNote(ash, id, "note $id") },
            )
        }.awaitAll()

        ids.forEach { id ->
            val row = db.userPokemonDao().get(ash, id)
            assertEquals("status of $id", "CAUGHT", row?.status)
            assertEquals("note of $id", "note $id", row?.note)
        }
    }

    @Test
    fun clearingStatusWhileNoteIsSavedNeverLeavesAnEmptyRowOrLosesTheNote() = runTest {
        val ash = profiles.createProfile("Ash").getOrThrow()
        userPokemon.setStatus(ash, 1, CatchStatus.WANT)

        listOf(
            async(Dispatchers.IO) { userPokemon.setStatus(ash, 1, CatchStatus.NONE) },
            async(Dispatchers.IO) { userPokemon.setNote(ash, 1, "keep me") },
        ).awaitAll()

        val row = db.userPokemonDao().get(ash, 1)
        assertEquals("NONE", row?.status)
        assertEquals("keep me", row?.note)
    }
}
