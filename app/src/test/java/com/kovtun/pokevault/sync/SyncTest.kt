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
import com.kovtun.pokevault.data.mapper.Sprites
import com.kovtun.pokevault.testutil.FakeClock
import com.kovtun.pokevault.testutil.FakeImagePrefetcher
import com.kovtun.pokevault.testutil.FakePokeApi
import com.kovtun.pokevault.testutil.FakeSettingsRepository
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

/** Integration: SyncEngine / workers + fake API + fake image cache + real Room. */
@RunWith(AndroidJUnit4::class)
class SyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PokeVaultDatabase
    private val api = FakePokeApi(FakePokeApi.generated(60))
    private val images = FakeImagePrefetcher()
    private val clock = FakeClock()
    private val settings = FakeSettingsRepository()
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PokeVaultDatabase::class.java).allowMainThreadQueries().build()
        engine = SyncEngine(api, db.pokemonDao(), settings, images, clock)
    }

    @After
    fun tearDown() = db.close()

    // ------------------------------------------------------------- engine

    @Test
    fun prefetchDownloadsDetailsAndImagesReportsProgressAndCountsPartialFailures() = runTest {
        api.failingIds += 3
        images.failing = { it == Sprites.artwork(4) }
        val progress = mutableListOf<Pair<Int, Int>>()

        val report = engine.prefetch(count = 5) { done, total -> progress += done to total }

        assertEquals(5, report.listed)
        assertEquals(4, report.detailsUpdated)
        assertEquals(1, report.detailsFailed)
        assertEquals(1, report.imagesFailed)
        assertFalse(report.isComplete)
        assertEquals(0 to 5, progress.first())
        assertEquals(5 to 5, progress.last())
        assertEquals(6, progress.size)
        assertEquals(4, db.pokemonDao().observeDetailedCount().first())
        assertTrue("sprite and artwork go to the image cache", Sprites.artwork(1) in images.cached)
    }

    @Test
    fun repeatedPrefetchSkipsFreshDetailsAndCachedImagesAndRefetchesAfterTtl() = runTest {
        engine.prefetch(count = 5)
        assertEquals(5, api.detailCalls.size)
        assertEquals(10, images.downloads.size)

        val again = engine.prefetch(count = 5)
        assertTrue(again.isComplete)
        assertEquals("fresh details are not downloaded again", 5, api.detailCalls.size)
        assertEquals("cached images are not downloaded again", 10, images.downloads.size)

        clock.advanceHours(25)
        engine.prefetch(count = 5)
        assertEquals(10, api.detailCalls.size)
    }

    @Test
    fun prefetchWithLargerCountDownloadsOnlyTheMissingPart() = runTest {
        engine.prefetch(count = 10)
        api.detailCalls.clear()

        engine.prefetch(count = 25)

        assertEquals((11..25).map { it.toString() }.toSet(), api.detailCalls.toSet())
    }

    @Test
    fun detailRequestsRunInParallelWithABound() = runTest {
        api.detailDelayMs = 100

        val report = engine.prefetch(count = 60)

        assertEquals(60, report.detailsUpdated)
        val max = api.maxInFlight.get()
        assertTrue("requests must overlap, max in flight = $max", max > 1)
        assertTrue("never more than ${SyncEngine.CONCURRENCY}, was $max", max <= SyncEngine.CONCURRENCY)
    }

    @Test
    fun userReferencedRefreshTouchesOnlyPokemonUsersKeep() = runTest {
        engine.prefetch(count = 10)
        val profileId = db.profileDao().insert(ProfileEntity(name = "Ash", colorIndex = 0, createdAt = 0))
        val collectionId = db.collectionDao().insertCollection(
            CollectionEntity(profileId = profileId, name = "Team", description = "", createdAt = 0),
        )
        db.collectionDao().insertItem(CollectionPokemonEntity(collectionId, pokemonId = 2, addedAt = 0))
        db.userPokemonDao().setStatus(profileId, 7, "WANT", now = 0)
        api.detailCalls.clear()

        assertEquals("nothing is stale yet", 0, engine.refreshUserReferenced().detailsUpdated)

        clock.advanceHours(25)
        val report = engine.refreshUserReferenced()

        assertEquals(2, report.detailsUpdated)
        assertEquals(setOf("2", "7"), api.detailCalls.toSet())
    }

    @Test
    fun periodicSyncWithFailedDetailsIsNotReportedAsDoneUntilEverythingIsUpdated() = runTest {
        engine.prefetch(count = 10)
        val profileId = db.profileDao().insert(ProfileEntity(name = "Ash", colorIndex = 0, createdAt = 0))
        db.userPokemonDao().setStatus(profileId, 2, "CAUGHT", now = 0)
        db.userPokemonDao().setStatus(profileId, 3, "CAUGHT", now = 0)
        clock.advanceHours(25)

        // List works, every detail request fails.
        api.failDetails = true
        val partial = engine.periodicSync()

        assertFalse(partial.listFailed)
        assertEquals(2, partial.detailsFailed)
        assertNull("not a complete sync", settings.app.value.lastSyncAt)
        assertEquals(clock.now, settings.app.value.lastSyncAttemptAt)
        assertEquals(2, settings.app.value.lastSyncFailures)
        assertTrue(settings.app.value.lastAttemptIncomplete)

        api.failDetails = false
        clock.now += 1_000
        val full = engine.periodicSync()

        assertTrue(full.isComplete)
        assertEquals(clock.now, settings.app.value.lastSyncAt)
        assertFalse(settings.app.value.lastAttemptIncomplete)
    }

    @Test
    fun listFailureIsReportedAndNothingIsMarkedSynced() = runTest {
        api.failList = true

        val report = engine.periodicSync()

        assertTrue(report.listFailed)
        assertNull(settings.app.value.lastSyncAt)
        assertNull(settings.app.value.lastListRefreshAt)
    }

    // ------------------------------------------------------------- worker results

    @Test
    fun reportMapsToSuccessRetryOrFailure() {
        val partial = SyncReport(detailsFailed = 1)
        val data = workDataOf()
        assertEquals(ListenableWorker.Result.success(data), SyncReport().toWorkResult(0, 3, data))
        assertEquals(ListenableWorker.Result.retry(), partial.toWorkResult(0, 3, data))
        assertEquals(ListenableWorker.Result.retry(), partial.toWorkResult(1, 3, data))
        assertEquals(ListenableWorker.Result.failure(data), partial.toWorkResult(2, 3, data))
        assertEquals(ListenableWorker.Result.retry(), SyncReport(imagesFailed = 1).toWorkResult(0, 3, data))
    }

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

    private fun syncWorker(attempt: Int = 0) = TestListenableWorkerBuilder.from(context, SyncWorker::class.java)
        .setWorkerFactory(factory)
        .setRunAttemptCount(attempt)
        .build()

    private fun prefetchWorker(count: Int, attempt: Int = 0) =
        TestListenableWorkerBuilder.from(context, PrefetchWorker::class.java)
            .setWorkerFactory(factory)
            .setInputData(workDataOf(PrefetchWorker.KEY_COUNT to count))
            .setRunAttemptCount(attempt)
            .build()

    @Test
    fun syncWorkerRetriesWhenTheListLoadedButDetailsFailed() = runTest {
        engine.prefetch(count = 5)
        val profileId = db.profileDao().insert(ProfileEntity(name = "Ash", colorIndex = 0, createdAt = 0))
        db.userPokemonDao().setStatus(profileId, 2, "CAUGHT", now = 0)
        clock.advanceHours(25)
        api.failDetails = true

        assertEquals(ListenableWorker.Result.retry(), syncWorker().doWork())
        val last = syncWorker(attempt = SyncWorker.MAX_ATTEMPTS - 1).doWork()
        assertTrue(last is ListenableWorker.Result.Failure)

        api.failDetails = false
        assertTrue(syncWorker(attempt = 1).doWork() is ListenableWorker.Result.Success)
    }

    @Test
    fun prefetchWorkerRetriesIncompleteDownloadAndFinallyReportsFailedCount() = runTest {
        api.failingIds += setOf(2, 3)

        assertEquals(ListenableWorker.Result.retry(), prefetchWorker(count = 5).doWork())

        val last = prefetchWorker(count = 5, attempt = PrefetchWorker.MAX_ATTEMPTS - 1).doWork()
        assertTrue(last is ListenableWorker.Result.Failure)
        assertEquals(2, last.outputData.getInt(PrefetchWorker.KEY_FAILED, -1))

        api.failingIds.clear()
        val ok = prefetchWorker(count = 5, attempt = 2).doWork()
        assertTrue(ok is ListenableWorker.Result.Success)
        assertEquals("only the missing ones were downloaded", 2, ok.outputData.getInt(PrefetchWorker.KEY_DONE, -1))
        assertNotNull(db.pokemonDao().getById(3)?.detailUpdatedAt)
    }
}
