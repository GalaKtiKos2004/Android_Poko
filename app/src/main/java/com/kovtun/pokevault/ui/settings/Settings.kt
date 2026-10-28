package com.kovtun.pokevault.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kovtun.pokevault.data.repository.PokemonRepository
import com.kovtun.pokevault.data.repository.UserPokemonRepository
import com.kovtun.pokevault.data.settings.AppSettings
import com.kovtun.pokevault.data.settings.ProfileSettings
import com.kovtun.pokevault.data.settings.SettingsRepository
import com.kovtun.pokevault.data.settings.ThemeMode
import com.kovtun.pokevault.domain.CacheStats
import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.domain.Profile
import com.kovtun.pokevault.domain.SessionRepository
import com.kovtun.pokevault.domain.SessionState
import com.kovtun.pokevault.domain.SortOrder
import com.kovtun.pokevault.sync.PrefetchProgress
import com.kovtun.pokevault.sync.PrefetchRequest
import com.kovtun.pokevault.sync.SyncScheduler
import com.kovtun.pokevault.ui.components.relativeTime
import com.kovtun.pokevault.ui.profiles.Avatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileSummary(
    val profile: Profile,
    val settings: ProfileSettings,
    val caught: Int,
    val wanted: Int,
)

data class SettingsUiState(
    val profile: ProfileSummary? = null,
    val app: AppSettings = AppSettings(),
    val cache: CacheStats = CacheStats(0, 0),
    val prefetch: PrefetchProgress = PrefetchProgress.Idle,
)

sealed interface SettingsEvent {
    data class SetTheme(val mode: ThemeMode) : SettingsEvent
    data class SetDefaultSort(val sort: SortOrder) : SettingsEvent
    data class SetHistoryEnabled(val enabled: Boolean) : SettingsEvent
    data class SetTtl(val hours: Int) : SettingsEvent
    data class SetBackgroundSync(val enabled: Boolean) : SettingsEvent
    data class SetWifiOnly(val enabled: Boolean) : SettingsEvent
    data class SetPrefetchCount(val count: Int) : SettingsEvent
    data object PrefetchNow : SettingsEvent
    data object ClearCache : SettingsEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val pokemonRepository: PokemonRepository,
    private val scheduler: SyncScheduler,
    userPokemonRepository: UserPokemonRepository,
    session: SessionRepository,
) : ViewModel() {

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val activeProfile: Flow<Profile> =
        session.session.filterIsInstance<SessionState.Active>().map { it.profile }

    private val profileSummary: Flow<ProfileSummary> = activeProfile.flatMapLatest { profile ->
        combine(
            settings.profileSettings(profile.id),
            userPokemonRepository.observeStatuses(profile.id),
        ) { prefs, statuses ->
            ProfileSummary(
                profile = profile,
                settings = prefs,
                caught = statuses.count { it.value == CatchStatus.CAUGHT },
                wanted = statuses.count { it.value == CatchStatus.WANT },
            )
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        profileSummary,
        settings.appSettings,
        pokemonRepository.observeCacheStats(),
        scheduler.observePrefetch(),
    ) { profile, app, cache, prefetch ->
        SettingsUiState(profile, app, cache, prefetch)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onEvent(event: SettingsEvent) {
        viewModelScope.launch {
            when (event) {
                is SettingsEvent.SetTheme -> withProfile { settings.setTheme(it, event.mode) }
                is SettingsEvent.SetDefaultSort -> withProfile { settings.setDefaultSort(it, event.sort) }
                is SettingsEvent.SetHistoryEnabled -> withProfile { settings.setHistoryEnabled(it, event.enabled) }
                is SettingsEvent.SetTtl -> settings.setCacheTtlHours(event.hours)
                is SettingsEvent.SetBackgroundSync -> settings.setBackgroundSync(event.enabled)
                is SettingsEvent.SetWifiOnly -> settings.setWifiOnly(event.enabled)
                is SettingsEvent.SetPrefetchCount -> settings.setPrefetchCount(event.count)
                SettingsEvent.PrefetchNow -> {
                    val app = settings.appSettings.first()
                    val message = when (scheduler.prefetchNow(app.prefetchCount, app.wifiOnly)) {
                        PrefetchRequest.STARTED -> "Загрузка первых ${app.prefetchCount} поставлена в очередь"
                        PrefetchRequest.ALREADY_RUNNING -> "Загрузка первых ${app.prefetchCount} уже идёт"
                        PrefetchRequest.RESTARTED ->
                            "Загрузка перезапущена: первые ${app.prefetchCount} (скачанное сохранено)"
                    }
                    _messages.send(message)
                }
                SettingsEvent.ClearCache -> {
                    val removed = pokemonRepository.clearCache()
                    _messages.send("Удалено из кэша: $removed (ваши покемоны сохранены)")
                }
            }
        }
    }

    private suspend fun withProfile(block: suspend (Long) -> Unit) {
        block(activeProfile.first().id)
    }
}

@Composable
fun SettingsRoute(onSwitchProfile: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    SettingsScreen(state, viewModel::onEvent, onSwitchProfile, snackbar)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    onSwitchProfile: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Настройки") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.profile?.let { ProfileSection(it, onEvent, onSwitchProfile) }
            OfflineSection(state, onEvent)
            SyncSection(state.app, onEvent)
        }
    }
}

@Composable
private fun ProfileSection(summary: ProfileSummary, onEvent: (SettingsEvent) -> Unit, onSwitchProfile: () -> Unit) {
    SectionCard("Профиль") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(summary.profile)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(summary.profile.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Поймано: ${summary.caught} · Хочу: ${summary.wanted}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onSwitchProfile) { Text("Сменить") }
        }
        Label("Тема")
        ChipRow(
            options = ThemeMode.entries,
            selected = summary.settings.theme,
            label = { mode ->
                when (mode) {
                    ThemeMode.SYSTEM -> "Системная"
                    ThemeMode.LIGHT -> "Светлая"
                    ThemeMode.DARK -> "Тёмная"
                }
            },
            onSelect = { onEvent(SettingsEvent.SetTheme(it)) },
        )
        Label("Сортировка по умолчанию")
        ChipRow(
            options = SortOrder.entries,
            selected = summary.settings.defaultSort,
            label = { if (it == SortOrder.ID) "По номеру" else "По имени" },
            onSelect = { onEvent(SettingsEvent.SetDefaultSort(it)) },
        )
        SwitchRow(
            title = "Записывать историю просмотров",
            checked = summary.settings.historyEnabled,
            onChange = { onEvent(SettingsEvent.SetHistoryEnabled(it)) },
        )
    }
}

@Composable
private fun OfflineSection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    SectionCard("Офлайн-режим") {
        Text(
            "В кэше: ${state.cache.cachedCount} покемонов, с деталями: ${state.cache.detailedCount}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Label("Скачивать для офлайна")
        ChipRow(
            options = AppSettings.PREFETCH_OPTIONS,
            selected = state.app.prefetchCount,
            label = { "Первые $it" },
            onSelect = { onEvent(SettingsEvent.SetPrefetchCount(it)) },
        )
        when (val p = state.prefetch) {
            is PrefetchProgress.Running -> {
                Text(
                    "Загрузка" + (p.count?.let { " первых $it" } ?: "") + ": ${p.done} / ${p.total}",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is PrefetchProgress.Queued -> Text(
                if (p.retrying) {
                    "Скачалось не всё — повтор после паузы…"
                } else {
                    "Ожидает сети / заряда…"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            is PrefetchProgress.Finished -> Text(
                "Готово: всё доступно без сети (обновлено ${p.updated})",
                style = MaterialTheme.typography.bodySmall,
            )
            is PrefetchProgress.Failed -> Text(
                "Скачано не полностью: не удалось загрузить ${p.failed}. Проверьте сеть и повторите",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            PrefetchProgress.Idle -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                // Always enabled: pressing with another amount restarts the job with it.
                onClick = { onEvent(SettingsEvent.PrefetchNow) },
            ) { Text("Скачать сейчас") }
            OutlinedButton(onClick = { onEvent(SettingsEvent.ClearCache) }) { Text("Очистить кэш") }
        }
    }
}

@Composable
private fun SyncSection(app: AppSettings, onEvent: (SettingsEvent) -> Unit) {
    SectionCard("Актуальность данных") {
        Label("Срок жизни кэша (TTL)")
        ChipRow(
            options = AppSettings.TTL_OPTIONS,
            selected = app.cacheTtlHours,
            label = { "$it ч" },
            onSelect = { onEvent(SettingsEvent.SetTtl(it)) },
        )
        SwitchRow(
            title = "Фоновое обновление (каждые 12 ч)",
            checked = app.backgroundSync,
            onChange = { onEvent(SettingsEvent.SetBackgroundSync(it)) },
        )
        SwitchRow(
            title = "Только по Wi-Fi",
            checked = app.wifiOnly,
            onChange = { onEvent(SettingsEvent.SetWifiOnly(it)) },
        )
        Text(
            "Последняя полная синхронизация: " + (app.lastSyncAt?.let { relativeTime(it) } ?: "ещё не было"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (app.lastAttemptIncomplete) {
            Text(
                "Последняя попытка ${relativeTime(app.lastSyncAttemptAt!!)} обновила не всё " +
                    "(ошибок: ${app.lastSyncFailures}) — будет повтор",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
