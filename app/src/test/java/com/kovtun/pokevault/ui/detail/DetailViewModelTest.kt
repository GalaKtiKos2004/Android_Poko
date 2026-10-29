package com.kovtun.pokevault.ui.detail

import androidx.lifecycle.SavedStateHandle
import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.domain.Freshness
import com.kovtun.pokevault.testutil.FakeClock
import com.kovtun.pokevault.testutil.FakeCollectionRepository
import com.kovtun.pokevault.testutil.FakeConnectivity
import com.kovtun.pokevault.testutil.FakeHistoryRepository
import com.kovtun.pokevault.testutil.FakePokemonRepository
import com.kovtun.pokevault.testutil.FakeSessionRepository
import com.kovtun.pokevault.testutil.FakeSettingsRepository
import com.kovtun.pokevault.testutil.FakeUserPokemonRepository
import com.kovtun.pokevault.testutil.MainDispatcherRule
import com.kovtun.pokevault.testutil.detail
import com.kovtun.pokevault.ui.navigation.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val repository = FakePokemonRepository()
    private val userRepository = FakeUserPokemonRepository()
    private val collections = FakeCollectionRepository()
    private val history = FakeHistoryRepository()
    private val settings = FakeSettingsRepository()
    private val clock = FakeClock()
    private val session = FakeSessionRepository()
    private val connectivity = FakeConnectivity()

    private fun TestScope.createViewModel(id: Int = 25): DetailViewModel {
        val vm = DetailViewModel(
            SavedStateHandle(mapOf(Routes.ARG_ID to id)),
            repository, userRepository, collections, history, settings, clock, session, connectivity,
        )
        backgroundScope.launch { vm.uiState.collect { } }
        return vm
    }

    @Test
    fun `screen loads the pokemon whose id came through navigation and records a view`() = runTest {
        repository.serverDetail = detail(25, "pikachu", updatedAt = clock.now)
        val vm = createViewModel(25)
        advanceUntilIdle()

        assertEquals(listOf(25 to false), repository.detailRefreshIds)
        val content = vm.uiState.value.content as DetailContent.Success
        assertEquals("pikachu", content.pokemon.name)
        assertEquals(Freshness.FRESH, vm.uiState.value.freshness)
        assertEquals(listOf(1L to 25), history.views)
    }

    @Test
    fun `offline with cached details shows them as stale instead of an error`() = runTest {
        repository.details.value = mapOf(25 to detail(25, "pikachu", updatedAt = clock.now))
        clock.advanceHours(48)
        repository.detailResults += Result.failure(IOException("offline"))
        connectivity.online.value = false
        val vm = createViewModel(25)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.content is DetailContent.Success)
        assertEquals(Freshness.STALE, state.freshness)
        assertNotNull(state.refreshError)
        assertTrue(state.isOffline)
    }

    @Test
    fun `no cache and failure gives Error, Retry issues a new request and succeeds`() = runTest {
        repository.detailResults += Result.failure(IOException("offline"))
        repository.serverDetail = detail(25, "pikachu", updatedAt = clock.now)
        val vm = createViewModel(25)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is DetailContent.Error)

        vm.onEvent(DetailEvent.Retry)
        advanceUntilIdle()

        assertEquals(2, repository.detailRefreshIds.size)
        assertTrue(vm.uiState.value.content is DetailContent.Success)
    }

    @Test
    fun `tapping the selected status again clears it`() = runTest {
        repository.serverDetail = detail(25, "pikachu")
        val vm = createViewModel(25)
        advanceUntilIdle()

        vm.onEvent(DetailEvent.SetStatus(CatchStatus.CAUGHT))
        advanceUntilIdle()
        assertEquals(CatchStatus.CAUGHT, vm.uiState.value.user.status)

        vm.onEvent(DetailEvent.SetStatus(CatchStatus.CAUGHT))
        advanceUntilIdle()
        assertEquals(CatchStatus.NONE, vm.uiState.value.user.status)
    }

    @Test
    fun `history is not written when the profile turned it off`() = runTest {
        settings.setHistoryEnabled(1L, false)
        repository.serverDetail = detail(25, "pikachu")
        createViewModel(25)
        advanceUntilIdle()

        assertTrue(history.views.isEmpty())
    }

    @Test
    fun `new collection is created with the pokemon and can be toggled off`() = runTest {
        repository.serverDetail = detail(25, "pikachu")
        val vm = createViewModel(25)
        advanceUntilIdle()

        vm.onEvent(DetailEvent.CreateCollectionAndAdd("Team"))
        advanceUntilIdle()
        val created = vm.uiState.value.collections.single()
        assertEquals("Team", created.name)
        assertTrue(created.selected)

        vm.onEvent(DetailEvent.ToggleCollection(created.id))
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.collections.single().selected)
    }

    @Test
    fun `a card that failed to load is not a view, the view is recorded once data arrives`() = runTest {
        repository.detailResults += Result.failure(IOException("offline"))
        repository.serverDetail = detail(25, "pikachu", updatedAt = clock.now)
        val vm = createViewModel(25)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is DetailContent.Error)
        assertTrue("no data -> no history entry", history.views.isEmpty())

        vm.onEvent(DetailEvent.Retry)
        advanceUntilIdle()
        vm.onEvent(DetailEvent.Refresh)
        advanceUntilIdle()

        assertEquals("exactly one view per opened card", listOf(1L to 25), history.views)
    }

    @Test
    fun `cached card counts as a view even when the refresh fails offline`() = runTest {
        repository.details.value = mapOf(25 to detail(25, "pikachu", updatedAt = clock.now))
        repository.detailResults += Result.failure(IOException("offline"))
        createViewModel(25)
        advanceUntilIdle()

        assertEquals(listOf(1L to 25), history.views)
    }
}
