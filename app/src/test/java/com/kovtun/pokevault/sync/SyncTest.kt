package com.kovtun.pokevault.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.kovtun.pokevault.data.local.CollectionEntity
import com.kovtun.pokevault.data.local.CollectionPokemonEntity
import com.kovtun.pokevault.data.local.PokeVaultDatabase
import com.kovtun.pokevault.data.local.ProfileEntity
import com.kovtun.pokevault.data.local.UserPokemonEntity
import com.kovtun.pokevault.testutil.FakeClock
import com.kovtun.pokevault.testutil.FakePokeApi
import com.kovtun.pokevault.testutil.FakeSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Integration: SyncEngine / workers + fake API + real Room. */
@RunWith(AndroidJUnit4::class)
class SyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PokeVaultDatabase
    private val api = FakePokeApi()
    private val clock = FakeClock()
    private val settings = FakeSettingsRepository()
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PokeVaultDatabase::class.java).allowMainThreadQueries().build()
        engine = SyncEngine(api, db.pokemonDao(), settings, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun prefetchDownloadsDetailsReportsProgressAndSurvivesPartialFailure() = runTest {
        api.failingIds += 3
        val progress = mutableListOf<Pair<Int, Int>>()

        val report = engine.prefetch(count = 5) { done, total -> progress += done to total }

        assertEquals(5, report.listed)
        assertEquals(4, report.detailsUpdated)
        assertEquals(1, report.detailsFailed)
        assertEquals(0 to 5, progress.first())
        assertEquals(5 to 5, progress.last())
        assertEquals(6, progress.size)
        assertEquals(4, db.pokemonDao().observeDetailedCount().first())
    }

    @Test
    fun prefetchSkipsFreshDetailsAndRefetchesAfterTtl() = runTest {
        engine.prefetch(count = 5)
        assertEquals(5, api.detailCalls.size)

        engine.prefetch(count = 5)
        assertEquals("fresh details are not downloaded again", 5, api.detailCalls.size)

        clock.advanceHours(25)
        engine.prefetch(count = 5)
        assertEquals(10, api.detailCalls.size)
    }

    @Test
    fun userReferencedRefreshTouchesOnlyStalePokemonUsersKeep() = runTest {
        engine.prefetch(count = 10)
        val profileId = db.profileDao().insert(ProfileEntity(name = "Ash", colorIndex = 0, createdAt = 0))
        val collectionId = db.collectionDao().insertCollection(
            CollectionEntity(profileId = profileId, name = "Team", description = "", createdAt = 0),
        )
        db.collectionDao().insertItem(CollectionPokemonEntity(collectionId, pokemonId = 2, addedAt = 0))
        db.userPokemonDao().upsert(UserPokemonEntity(profileId, 7, "WANT", "", 0))
        api.detailCalls.clear()

        assertEquals("nothing is stale yet", 0, engine.refreshUserReferenced().detailsUpdated)

        clock.advanceHours(25)
        val report = engine.refreshUserReferenced()

        assertEquals(2, report.detailsUpdated)
        assertEquals(listOf("2", "7"), api.detailCalls)
    }

    @Test
    fun periodicSyncStoresLastSyncTimeOnlyOnSuccess() = runTest {
        api.failList = true
        val failed = engine.periodicSync()
        assertTrue(failed.listFailed)
        assertNull(settings.app.value.lastSyncAt)

        api.failList = false
        engine.periodicSync()
        assertEquals(clock.now, settings.app.value.lastSyncAt)
        assertEquals(clock.now, settings.app.value.lastListRefreshAt)
    }

    // ------------------------------------------------------------- workers

    private val factory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = when (workerClassName) {
            SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters, engine)
            PrefetchWorker::class.java.name -> PrefetchWorker(appContext, workerParameters, engine)
            else -> null
        }
    }

    @Test
    fun syncWorkerSucceedsOnlineAndAsksForRetryOffline() = runTest {
        val ok = TestListenableWorkerBuilder.from(context, SyncWorker::class.java)
            .setWorkerFactory(factory)
            .build()
        assertTrue(ok.doWork() is ListenableWorker.Result.Success)

        api.failList = true
        val offline = TestListenableWorkerBuilder.from(context, SyncWorker::class.java)
            .setWorkerFactory(factory)
            .build()
        assertEquals(ListenableWorker.Result.retry(), offline.doWork())

        val lastAttempt = TestListenableWorkerBuilder.from(context, SyncWorker::class.java)
            .setWorkerFactory(factory)
            .setRunAttemptCount(SyncWorker.MAX_ATTEMPTS)
            .build()
        assertEquals(ListenableWorker.Result.failure(), lastAttempt.doWork())
    }

    @Test
    fun prefetchWorkerUsesRequestedCount() = runTest {
        val worker = TestListenableWorkerBuilder.from(context, PrefetchWorker::class.java)
            .setWorkerFactory(factory)
            .setInputData(workDataOf(PrefetchWorker.KEY_COUNT to 3))
            .build()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(3, result.outputData.getInt(PrefetchWorker.KEY_DONE, -1))
        assertEquals(3, db.pokemonDao().count())
    }
}
