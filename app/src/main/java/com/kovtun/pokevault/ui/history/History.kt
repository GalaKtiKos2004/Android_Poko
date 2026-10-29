package com.kovtun.pokevault.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kovtun.pokevault.data.repository.HistoryRepository
import com.kovtun.pokevault.data.settings.SettingsRepository
import com.kovtun.pokevault.domain.HistoryEntry
import com.kovtun.pokevault.domain.SessionRepository
import com.kovtun.pokevault.domain.activeProfileId
import com.kovtun.pokevault.ui.components.EmptyState
import com.kovtun.pokevault.ui.components.LoadingState
import com.kovtun.pokevault.ui.components.PokemonRow
import com.kovtun.pokevault.ui.components.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val isLoading: Boolean = true,
    val entries: List<HistoryEntry> = emptyList(),
    val historyEnabled: Boolean = true,
)

sealed interface HistoryEvent {
    data object Clear : HistoryEvent
    data class Remove(val pokemonId: Int) : HistoryEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: HistoryRepository,
    settings: SettingsRepository,
    session: SessionRepository,
) : ViewModel() {

    private val profileId: Flow<Long> =
        session.activeProfileId.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    val uiState: StateFlow<HistoryUiState> = profileId
        .flatMapLatest { id ->
            combine(repository.observe(id), settings.profileSettings(id)) { entries, prefs ->
                HistoryUiState(isLoading = false, entries = entries, historyEnabled = prefs.historyEnabled)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun onEvent(event: HistoryEvent) {
        viewModelScope.launch {
            val id = profileId.first()
            when (event) {
                HistoryEvent.Clear -> repository.clear(id)
                is HistoryEvent.Remove -> repository.remove(id, event.pokemonId)
            }
        }
    }
}

@Composable
fun HistoryRoute(onOpenDetail: (Int) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryScreen(state, viewModel::onEvent, onOpenDetail)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(state: HistoryUiState, onEvent: (HistoryEvent) -> Unit, onOpenDetail: (Int) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Недавние") },
                actions = {
                    if (state.entries.isNotEmpty()) {
                        IconButton(onClick = { onEvent(HistoryEvent.Clear) }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Очистить историю")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.historyEnabled) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Запись истории выключена в настройках профиля",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> LoadingState()
                    state.entries.isEmpty() -> EmptyState(
                        title = "История пуста",
                        subtitle = "Здесь появятся покемоны, которых вы открывали",
                        icon = if (state.historyEnabled) Icons.Filled.History else Icons.Filled.VisibilityOff,
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.entries, key = { it.pokemonId }) { entry ->
                            PokemonRow(
                                id = entry.pokemonId,
                                name = entry.name,
                                spriteUrl = entry.spriteUrl,
                                types = emptyList(),
                                onClick = { onOpenDetail(entry.pokemonId) },
                                subtitle = "${relativeTime(entry.viewedAt)} · просмотров: ${entry.viewCount}",
                                trailing = {
                                    IconButton(onClick = { onEvent(HistoryEvent.Remove(entry.pokemonId)) }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Убрать из истории")
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
