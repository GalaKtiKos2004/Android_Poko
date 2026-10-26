package com.kovtun.pokevault.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.testutil.FakeConnectivity
import com.kovtun.pokevault.testutil.FakePokemonRepository
import com.kovtun.pokevault.testutil.FakePresetRepository
import com.kovtun.pokevault.testutil.FakeSessionRepository
import com.kovtun.pokevault.testutil.FakeSettingsRepository
import com.kovtun.pokevault.testutil.FakeUserPokemonRepository
import com.kovtun.pokevault.testutil.summary
import com.kovtun.pokevault.ui.components.TestTags
import com.kovtun.pokevault.ui.navigation.Routes
import com.kovtun.pokevault.ui.pokedex.ListContent
import com.kovtun.pokevault.ui.pokedex.PokedexEvent
import com.kovtun.pokevault.ui.pokedex.PokedexScreen
import com.kovtun.pokevault.ui.pokedex.PokedexUiState
import com.kovtun.pokevault.ui.pokedex.PokedexViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Compose UI tests (Robolectric): screen states, Retry, and list -> detail navigation. */
@RunWith(AndroidJUnit4::class)
class PokedexUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val items = listOf(summary(1, "bulbasaur", "grass"), summary(25, "pikachu", "electric"))

    @Test
    fun errorStateRetryButtonSendsRetryEvent() {
        val events = mutableListOf<PokedexEvent>()
        composeRule.setContent {
            PokedexScreen(
                state = PokedexUiState(content = ListContent.Error("Нет соединения с сервером")),
                onEvent = { events += it },
                onOpenDetail = {},
            )
        }

        composeRule.onNodeWithText("Нет соединения с сервером").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.RETRY).performClick()

        assertEquals(listOf(PokedexEvent.Retry), events)
    }

    @Test
    fun clickOnListItemOpensDetailForThatId() {
        composeRule.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = Routes.POKEDEX) {
                composable(Routes.POKEDEX) {
                    PokedexScreen(
                        state = PokedexUiState(content = ListContent.Success(items)),
                        onEvent = {},
                        onOpenDetail = { nav.navigate(Routes.detail(it)) },
                    )
                }
                composable(
                    Routes.DETAIL,
                    arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.IntType }),
                ) { entry ->
                    Text("detail:${entry.arguments?.getInt(Routes.ARG_ID)}")
                }
            }
        }

        composeRule.onNodeWithTag(TestTags.row(25)).performClick()

        composeRule.onNodeWithText("detail:25").assertIsDisplayed()
    }

    @Test
    fun viewModelErrorThenRetryShowsLoadedListOnScreen() {
        val statuses = MutableStateFlow(mapOf(1L to mapOf(25 to CatchStatus.CAUGHT)))
        val repository = FakePokemonRepository(statuses).apply {
            serverItems = items
            refreshResults += Result.failure(IOException("offline"))
        }
        val viewModel = PokedexViewModel(
            repository,
            FakeUserPokemonRepository(statuses),
            FakePresetRepository(),
            FakeSettingsRepository(),
            FakeSessionRepository(),
            FakeConnectivity(),
        )
        composeRule.setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            PokedexScreen(state = state, onEvent = viewModel::onEvent, onOpenDetail = {})
        }

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(TestTags.ERROR).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(TestTags.RETRY).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(TestTags.row(25)).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithTag(TestTags.row(1)).assertIsDisplayed()
        composeRule.onNodeWithText("Pikachu").assertIsDisplayed()
        assertEquals(2, repository.refreshCalls)
    }
}
