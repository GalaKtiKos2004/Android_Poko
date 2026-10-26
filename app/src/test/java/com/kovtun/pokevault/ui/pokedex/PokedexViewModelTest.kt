package com.kovtun.pokevault.ui.pokedex

import app.cash.turbine.test
import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.domain.PokemonFilter
import com.kovtun.pokevault.testutil.FakeConnectivity
import com.kovtun.pokevault.testutil.FakePokemonRepository
import com.kovtun.pokevault.testutil.FakePresetRepository
import com.kovtun.pokevault.testutil.FakeSessionRepository
import com.kovtun.pokevault.testutil.FakeSettingsRepository
import com.kovtun.pokevault.testutil.FakeUserPokemonRepository
import com.kovtun.pokevault.testutil.MainDispatcherRule
import com.kovtun.pokevault.testutil.summary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PokedexViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val statuses = MutableStateFlow<Map<Long, Map<Int, CatchStatus>>>(emptyMap())
    private val repository = FakePokemonRepository(statuses)
    private val userRepository = FakeUserPokemonRepository(statuses)
    private val presets = FakePresetRepository()
    private val settings = FakeSettingsRepository()
    private val session = FakeSessionRepository()
    private val connectivity = FakeConnectivity()

    private val pokemon = listOf(
        summary(1, "bulbasaur", "grass"),
        summary(4, "charmander", "fire"),
        summary(25, "pikachu", "electric"),
    )

    private fun createViewModel() =
        PokedexViewModel(repository, userRepository, presets, settings, session, connectivity)

    /** Keeps uiState hot (WhileSubscribed) for tests that inspect `.value`. */
    private fun TestScope.collectState(vm: PokedexViewModel): MutableList<PokedexUiState> {
        val states = mutableListOf<PokedexUiState>()
        backgroundScope.launch { vm.uiState.collect { states += it } }
        return states
    }

    private fun PokedexUiState.ids() = (content as ListContent.Success).items.map { it.id }

    @Test
    fun `initial state is Loading and becomes Success after the first refresh`() = runTest {
        repository.serverItems = pokemon
        val vm = createViewModel()

        vm.uiState.test {
            assertEquals(ListContent.Loading, awaitItem().content)
            var state = awaitItem()
            while (state.content !is ListContent.Success) state = awaitItem()
            assertEquals(listOf(1, 4, 25), state.ids())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, repository.refreshCalls)
    }

    @Test
    fun `error then retry walks through Loading - Error - Loading - Success exactly`() = runTest {
        repository.serverItems = pokemon
        repository.refreshResults += Result.failure(IOException("offline"))
        val vm = createViewModel()

        vm.uiState.test {
            val sequence = mutableListOf<ListContent>()
            fun record(state: PokedexUiState) {
                if (sequence.lastOrNull()?.javaClass != state.content.javaClass) sequence += state.content
            }
            record(awaitItem())
            while (sequence.last() !is ListContent.Error) record(awaitItem())

            // Hold the retry request open so that the Loading state is observable.
            val gate = CompletableDeferred<Unit>()
            repository.refreshGate = gate
            vm.onEvent(PokedexEvent.Retry)
            while (sequence.last() !is ListContent.Loading) record(awaitItem())
            gate.complete(Unit)
            while (sequence.last() !is ListContent.Success) record(awaitItem())

            assertEquals(
                listOf(
                    ListContent.Loading::class,
                    ListContent.Error::class,
                    ListContent.Loading::class,
                    ListContent.Success::class,
                ),
                sequence.map { it::class },
            )
            assertEquals("Нет соединения с сервером", (sequence[1] as ListContent.Error).message)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("retry must trigger a new request", 2, repository.refreshCalls)
    }

    @Test
    fun `fast typing is debounced into a single list query`() = runTest {
        repository.serverItems = pokemon
        val vm = createViewModel()
        collectState(vm)
        advanceUntilIdle()
        repository.observedFilters.clear()

        vm.onEvent(PokedexEvent.QueryChanged("p"))
        advanceTimeBy(100)
        vm.onEvent(PokedexEvent.QueryChanged("pi"))
        advanceTimeBy(100)
        vm.onEvent(PokedexEvent.QueryChanged("pik"))
        advanceTimeBy(PokedexViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(listOf("pik"), repository.observedFilters.map { it.query })
        assertEquals(listOf(25), vm.uiState.value.ids())
    }

    @Test
    fun `nothing found gives Empty, not Success with empty list`() = runTest {
        repository.serverItems = pokemon
        val vm = createViewModel()
        collectState(vm)

        vm.onEvent(PokedexEvent.QueryChanged("zz"))
        advanceUntilIdle()

        assertEquals(ListContent.Empty(filtered = true), vm.uiState.value.content)
    }

    @Test
    fun `newer query cancels the stale server lookup and its result never reaches UI`() = runTest {
        repository.serverItems = pokemon
        repository.remoteDelayMs = 1_000
        repository.remoteResult = { query ->
            if (query == "abcd") Result.success(null) else Result.failure(IOException("offline"))
        }
        val vm = createViewModel()
        val states = collectState(vm)
        advanceUntilIdle()

        vm.onEvent(PokedexEvent.QueryChanged("abcd"))
        advanceTimeBy(PokedexViewModel.SEARCH_DEBOUNCE_MS + 10)
        runCurrent()
        vm.onEvent(PokedexEvent.QueryChanged("mewtwo"))
        advanceUntilIdle()

        assertEquals(listOf("abcd", "mewtwo"), repository.remoteStarted)
        assertEquals("stale lookup must be cancelled", listOf("mewtwo"), repository.remoteCompleted)
        assertTrue(states.none { it.remoteSearch == RemoteSearch.NotFound })
        assertEquals(RemoteSearch.Failed, vm.uiState.value.remoteSearch)
    }

    @Test
    fun `coming back online after an error retries automatically`() = runTest {
        repository.serverItems = pokemon
        repository.refreshResults += Result.failure(IOException("offline"))
        connectivity.online.value = false
        val vm = createViewModel()
        collectState(vm)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is ListContent.Error)
        assertTrue(vm.uiState.value.isOffline)

        connectivity.online.value = true
        advanceUntilIdle()

        assertEquals(2, repository.refreshCalls)
        assertEquals(listOf(1, 4, 25), vm.uiState.value.ids())
        assertFalse(vm.uiState.value.isOffline)
    }

    @Test
    fun `status filter and badges follow the active profile`() = runTest {
        repository.serverItems = pokemon
        statuses.value = mapOf(
            1L to mapOf(1 to CatchStatus.CAUGHT),
            2L to mapOf(4 to CatchStatus.CAUGHT, 25 to CatchStatus.WANT),
        )
        val vm = createViewModel()
        collectState(vm)
        vm.onEvent(PokedexEvent.StatusSelected(CatchStatus.CAUGHT))
        advanceUntilIdle()
        assertEquals(listOf(1), vm.uiState.value.ids())

        session.switchTo(2)
        advanceUntilIdle()

        assertEquals(listOf(4), vm.uiState.value.ids())
        assertEquals(CatchStatus.WANT, vm.uiState.value.statuses[25])
    }

    @Test
    fun `saved preset is announced once and restores the whole filter`() = runTest {
        repository.serverItems = pokemon
        val vm = createViewModel()
        collectState(vm)
        advanceUntilIdle()

        vm.messages.test {
            vm.onEvent(PokedexEvent.TypeSelected("fire"))
            vm.onEvent(PokedexEvent.QueryChanged("char"))
            vm.onEvent(PokedexEvent.SavePreset("  Fire  "))
            assertEquals("Фильтр «Fire» сохранён", awaitItem())
            expectNoEvents()
        }
        advanceUntilIdle()
        vm.onEvent(PokedexEvent.ClearFilters)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.filter.isDefault)

        vm.onEvent(PokedexEvent.ApplyPreset(vm.uiState.value.presets.single()))
        advanceUntilIdle()

        assertEquals("char", vm.uiState.value.query)
        assertEquals(PokemonFilter(type = "fire"), vm.uiState.value.filter)
        assertEquals(listOf(4), vm.uiState.value.ids())
    }
}
