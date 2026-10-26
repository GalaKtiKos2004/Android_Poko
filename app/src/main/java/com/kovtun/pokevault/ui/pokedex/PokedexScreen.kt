package com.kovtun.pokevault.ui.pokedex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.domain.POKEMON_TYPES
import com.kovtun.pokevault.domain.SortOrder
import com.kovtun.pokevault.domain.label
import com.kovtun.pokevault.ui.components.EmptyState
import com.kovtun.pokevault.ui.components.ErrorState
import com.kovtun.pokevault.ui.components.LoadingState
import com.kovtun.pokevault.ui.components.OfflineBanner
import com.kovtun.pokevault.ui.components.PokemonRow
import com.kovtun.pokevault.ui.components.TestTags

@Composable
fun PokedexRoute(onOpenDetail: (Int) -> Unit, viewModel: PokedexViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    PokedexScreen(state = state, onEvent = viewModel::onEvent, onOpenDetail = onOpenDetail, snackbar = snackbar)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PokedexScreen(
    state: PokedexUiState,
    onEvent: (PokedexEvent) -> Unit,
    onOpenDetail: (Int) -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Покедекс") },
                actions = {
                    IconButton(onClick = { showSaveDialog = true }) {
                        Icon(Icons.Filled.BookmarkAdd, contentDescription = "Сохранить фильтр")
                    }
                    IconButton(onClick = { onEvent(PokedexEvent.Refresh) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(state.query, onQueryChange = { onEvent(PokedexEvent.QueryChanged(it)) })
            FiltersBar(state, onEvent)
            if (state.isOffline) OfflineBanner()
            state.refreshError?.let { OfflineBanner(text = "Не удалось обновить: $it") }

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { onEvent(PokedexEvent.Refresh) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (val content = state.content) {
                    ListContent.Loading -> LoadingState()
                    is ListContent.Error -> ErrorState(content.message, onRetry = { onEvent(PokedexEvent.Retry) })
                    is ListContent.Empty -> EmptyContent(state, content.filtered, onEvent)
                    is ListContent.Success -> PokemonList(state, content, onEvent, onOpenDetail)
                }
            }
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            description = state.filter.copy(query = state.query).describe(),
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                onEvent(PokedexEvent.SavePreset(name))
                showSaveDialog = false
            },
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("search_field"),
        placeholder = { Text("Имя или номер") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Очистить")
                }
            }
        },
        singleLine = true,
    )
}

@Composable
private fun FiltersBar(state: PokedexUiState, onEvent: (PokedexEvent) -> Unit) {
    if (state.presets.isNotEmpty()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.presets, key = { it.id }) { preset ->
                AssistChip(
                    onClick = { onEvent(PokedexEvent.ApplyPreset(preset)) },
                    label = { Text(preset.name) },
                    leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
            }
            item {
                TextButton(onClick = { onEvent(PokedexEvent.ClearFilters) }) { Text("Сбросить") }
            }
        }
        // Deleting a preset: long list of chips stays simple, delete is a separate row of buttons.
        PresetDeleteRow(state, onEvent)
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = state.filter.sort == SortOrder.NAME,
                onClick = {
                    val next = if (state.filter.sort == SortOrder.NAME) SortOrder.ID else SortOrder.NAME
                    onEvent(PokedexEvent.SortSelected(next))
                },
                label = { Text("A→Z") },
            )
        }
        items(listOf(CatchStatus.WANT, CatchStatus.CAUGHT)) { status ->
            FilterChip(
                selected = state.filter.status == status,
                onClick = {
                    onEvent(PokedexEvent.StatusSelected(if (state.filter.status == status) null else status))
                },
                label = { Text(status.label) },
            )
        }
        items(POKEMON_TYPES) { type ->
            FilterChip(
                selected = state.filter.type == type,
                onClick = { onEvent(PokedexEvent.TypeSelected(if (state.filter.type == type) null else type)) },
                label = { Text(type) },
            )
        }
    }
}

@Composable
private fun PresetDeleteRow(state: PokedexUiState, onEvent: (PokedexEvent) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        TextButton(onClick = { editing = !editing }) {
            Text(if (editing) "Готово" else "Управлять фильтрами", style = MaterialTheme.typography.labelMedium)
        }
    }
    if (editing) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.presets, key = { "del_${it.id}" }) { preset ->
                OutlinedButton(onClick = { onEvent(PokedexEvent.DeletePreset(preset.id)) }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" ${preset.name}")
                }
            }
        }
    }
}

@Composable
private fun EmptyContent(state: PokedexUiState, filtered: Boolean, onEvent: (PokedexEvent) -> Unit) {
    val subtitle = when (state.remoteSearch) {
        RemoteSearch.NotFound -> "На сервере такого покемона тоже нет"
        RemoteSearch.Failed -> "Поиск на сервере недоступен без сети"
        else -> if (filtered) "Попробуйте изменить фильтры" else "Потяните вниз, чтобы загрузить"
    }
    EmptyState(
        title = if (filtered) "Ничего не найдено" else "Список пуст",
        subtitle = subtitle,
        icon = Icons.Filled.SearchOff,
        actionLabel = if (filtered) "Сбросить фильтры" else null,
        onAction = { onEvent(PokedexEvent.ClearFilters) },
    )
}

@Composable
private fun PokemonList(
    state: PokedexUiState,
    content: ListContent.Success,
    onEvent: (PokedexEvent) -> Unit,
    onOpenDetail: (Int) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().testTag(TestTags.LIST)) {
        items(content.items, key = { it.id }) { pokemon ->
            PokemonRow(
                id = pokemon.id,
                name = pokemon.name,
                spriteUrl = pokemon.spriteUrl,
                types = pokemon.types,
                status = state.statuses[pokemon.id] ?: CatchStatus.NONE,
                onClick = { onOpenDetail(pokemon.id) },
                subtitle = if (pokemon.hasDetails) null else "Детали ещё не загружены",
            )
            HorizontalDivider()
        }
        if (state.showLoadMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (state.isLoadingMore) {
                        CircularProgressIndicator()
                    } else {
                        OutlinedButton(onClick = { onEvent(PokedexEvent.LoadMore) }) { Text("Загрузить ещё") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavePresetDialog(description: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сохранить фильтр") },
        text = {
            Column {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text(description, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
