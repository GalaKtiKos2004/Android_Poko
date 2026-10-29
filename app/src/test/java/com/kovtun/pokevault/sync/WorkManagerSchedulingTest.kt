package com.kovtun.pokevault.sync

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import com.kovtun.pokevault.data.local.PokeVaultDatabase
import com.kovtun.pokevault.testutil.FakeClock
import com.kovtun.pokevault.testutil.FakeImagePrefetcher
import com.kovtun.pokevault.testutil.FakePokeApi
import com.kovtun.pokevault.testutil.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The real WorkManager (test build) schedules our requests: constraints, periodicity,
 * unique-work policies and retries are checked here, with [TestDriver] deciding when
 * constraints / delays are met.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerSchedulingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PokeVaultDatabase
    private val api = FakePokeApi(FakePokeApi.generated(40))
    private val settings = FakeSettingsRepository()
    private val clock = FakeClock()
    private lateinit var engine: SyncEngine
    private lateinit var workManager: WorkManager
    private lateinit var driver: TestDriver
    private lateinit var scope: CoroutineScope
    private lateinit var scheduler: WorkManagerSyncScheduler

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PokeVaultDatabase::class.java).allowMainThreadQueries().build()
        engine = SyncEngine(api, db.pokemonDao(), settings, FakeImagePrefetcher(), clock)
        val factory = object : WorkerFactory() {
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
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(factory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        // Unconfined: settings changes reach WorkManager synchronously.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scheduler = WorkManagerSyncScheduler(workManager, settings, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun periodicInfo(): WorkInfo? =
        workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_NAME).get().firstOrNull { !it.state.isFinished }

    private fun prefetchInfos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(PrefetchWorker.UNIQUE_NAME).get()

    /** CoroutineWorker runs on its own dispatcher, so the final state is awaited in real time. */
    private fun awaitInfo(id: UUID, condition: (WorkInfo) -> Boolean): WorkInfo {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val info = workManager.getWorkInfoById(id).get()
            if (info != null && condition(info)) return info
            Thread.sleep(20)
        }
        error("WorkInfo $id did not reach the expected state: ${workManager.getWorkInfoById(id).get()}")
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { message }
            Thread.sleep(20)
        }
    }

    // ------------------------------------------------------------- periodic sync

    @Test
    fun periodicSyncIsScheduledWithConstraintsAndFollowsSettings() {
        scheduler.start()

        val info = checkNotNull(periodicInfo())
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
        assertEquals(TimeUnit.HOURS.toMillis(WorkManagerSyncScheduler.PERIOD_HOURS), info.periodicityInfo?.repeatIntervalMillis)

        // Wi-Fi only -> same work (UPDATE policy), stricter network constraint.
        settings.app.update { it.copy(wifiOnly = true) }
        val updated = checkNotNull(periodicInfo())
        assertEquals(info.id, updated.id)
        assertEquals(NetworkType.UNMETERED, updated.constraints.requiredNetworkType)

        // Background sync off -> cancelled.
        settings.app.update { it.copy(backgroundSync = false) }
        assertEquals(null, periodicInfo())
    }

    @Test
    fun periodicSyncRunsOnlyWhenConstraintsAndPeriodAreMet() {
        scheduler.start()
        val id = checkNotNull(periodicInfo()).id

        driver.setPeriodDelayMet(id)
        assertEquals("no network yet -> worker did not run", 0, api.listCalls)

        driver.setAllConstraintsMet(id)
        awaitCondition("periodic sync did not finish") { settings.app.value.lastSyncAt != null }

        assertEquals(1, api.listCalls)
        assertEquals(WorkInfo.State.ENQUEUED, awaitInfo(id) { it.state == WorkInfo.State.ENQUEUED }.state)
    }

    // ------------------------------------------------------------- offline prefetch

    @Test
    fun prefetchWaitsForConstraintsThenDownloads() = runBlocking {
        assertEquals(PrefetchRequest.STARTED, scheduler.prefetchNow(count = 20, wifiOnly = true))
        val info = prefetchInfos().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertEquals("no Wi-Fi yet -> nothing downloaded", 0, api.listCalls)

        driver.setAllConstraintsMet(info.id)
        val done = awaitInfo(info.id) { it.state.isFinished }

        assertEquals(WorkInfo.State.SUCCEEDED, done.state)
        assertEquals(20, db.pokemonDao().catalogIds(100).size)
        assertEquals(20, api.detailCalls.size)
    }

    @Test
    fun repeatedPrefetchKeepsIdenticalRequestButReplacesOneWithAnotherCount() = runBlocking {
        scheduler.prefetchNow(count = 10, wifiOnly = false)
        val first = prefetchInfos().single { !it.state.isFinished }

        assertEquals(PrefetchRequest.ALREADY_RUNNING, scheduler.prefetchNow(count = 10, wifiOnly = false))
        assertEquals(first.id, prefetchInfos().single { !it.state.isFinished }.id)

        assertEquals(PrefetchRequest.RESTARTED, scheduler.prefetchNow(count = 30, wifiOnly = false))
        val second = prefetchInfos().single { !it.state.isFinished }
        assertNotEquals(first.id, second.id)
        assertTrue(PrefetchWorker.countTag(30) in second.tags)
        val old = workManager.getWorkInfoById(first.id).get()
        assertTrue("old request cancelled (REPLACE may also prune it)", old == null || old.state == WorkInfo.State.CANCELLED)

        driver.setAllConstraintsMet(second.id)
        awaitInfo(second.id) { it.state.isFinished }
        assertEquals("the new amount was downloaded", 30, db.pokemonDao().catalogIds(100).size)
    }

    @Test
    fun incompletePrefetchIsRetriedByWorkManagerAndThenSucceeds() = runBlocking {
        api.failOnce += 5 // first request for #5 fails, the next one works
        scheduler.prefetchNow(count = 10, wifiOnly = false)
        val id = prefetchInfos().single().id

        driver.setAllConstraintsMet(id)
        val afterFirstRun = awaitInfo(id) {
            it.runAttemptCount >= 1 && (it.state == WorkInfo.State.ENQUEUED || it.state.isFinished)
        }
        if (afterFirstRun.state == WorkInfo.State.ENQUEUED) {
            // Retry is waiting for its backoff delay.
            runCatching { driver.setInitialDelayMet(id) }
        }
        val done = awaitInfo(id) { it.state.isFinished }

        assertEquals(WorkInfo.State.SUCCEEDED, done.state)
        assertEquals("exactly one retry", 1, done.runAttemptCount)
        assertNotNull(db.pokemonDao().getById(5)?.detailUpdatedAt)
        assertEquals("on retry only the missing pokemon was requested", 11, api.detailCalls.size)
    }
}
