package com.kovtun.pokevault.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.domain.Freshness
import com.kovtun.pokevault.domain.PokemonDetail
import com.kovtun.pokevault.domain.label
import com.kovtun.pokevault.ui.components.ErrorState
import com.kovtun.pokevault.ui.components.LoadingState
import com.kovtun.pokevault.ui.components.OfflineBanner
import com.kovtun.pokevault.ui.components.TypeChip
import com.kovtun.pokevault.ui.components.dexNumber
import com.kovtun.pokevault.ui.components.displayName
import com.kovtun.pokevault.ui.components.relativeTime

@Composable
fun DetailRoute(onBack: () -> Unit, viewModel: DetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    DetailScreen(state, viewModel::onEvent, onBack, snackbar)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    state: DetailUiState,
    onEvent: (DetailEvent) -> Unit,
    onBack: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val title = (state.content as? DetailContent.Success)?.pokemon?.let { displayName(it.name) } ?: dexNumber(state.pokemonId)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { onEvent(DetailEvent.Refresh) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.isOffline) OfflineBanner()
            when (val content = state.content) {
                DetailContent.Loading -> LoadingState()
                is DetailContent.Error -> ErrorState(content.message, onRetry = { onEvent(DetailEvent.Retry) })
                is DetailContent.Success -> DetailBody(state, content.pokemon, onEvent)
            }
        }
    }
}

@Composable
private fun DetailBody(state: DetailUiState, pokemon: PokemonDetail, onEvent: (DetailEvent) -> Unit) {
    var showCollections by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(pokemon, state)
        StatusSection(state.user.status) { onEvent(DetailEvent.SetStatus(it)) }
        NoteSection(initial = state.user.note, key = state.pokemonId) { onEvent(DetailEvent.SaveNote(it)) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Коллекции", style = MaterialTheme.typography.titleMedium)
                val inCollections = state.collections.filter { it.selected }
                Text(
                    if (inCollections.isEmpty()) "Пока ни в одной" else inCollections.joinToString { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                OutlinedButton(onClick = { showCollections = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Добавить в коллекцию")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Характеристики", style = MaterialTheme.typography.titleMedium)
                Text("Рост: ${pokemon.heightDm / 10.0} м · Вес: ${pokemon.weightHg / 10.0} кг")
                pokemon.baseExperience?.let { Text("Базовый опыт: $it") }
                if (pokemon.abilities.isNotEmpty()) {
                    Text("Способности: " + pokemon.abilities.joinToString { displayName(it) })
                }
                pokemon.stats.forEach { stat -> StatBar(stat.name, stat.value) }
            }
        }
    }

    if (showCollections) {
        CollectionsDialog(
            choices = state.collections,
            onToggle = { onEvent(DetailEvent.ToggleCollection(it)) },
            onCreate = { onEvent(DetailEvent.CreateCollectionAndAdd(it)) },
            onDismiss = { showCollections = false },
        )
    }
}

@Composable
private fun Header(pokemon: PokemonDetail, state: DetailUiState) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(model = pokemon.artworkUrl, contentDescription = pokemon.name, modifier = Modifier.size(160.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(dexNumber(pokemon.id), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            pokemon.types.forEach { TypeChip(it) }
        }
        val freshnessText = when (state.freshness) {
            Freshness.FRESH -> "Данные актуальны · обновлено ${relativeTime(pokemon.updatedAt)}"
            Freshness.STALE -> "Данные устарели · обновлено ${relativeTime(pokemon.updatedAt)}"
            Freshness.MISSING -> ""
        }
        if (freshnessText.isNotEmpty()) {
            Text(
                freshnessText,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.freshness == Freshness.STALE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        state.refreshError?.let {
            Text("Не удалось обновить: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StatusSection(status: CatchStatus, onSelect: (CatchStatus) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = status == CatchStatus.WANT,
            onClick = { onSelect(CatchStatus.WANT) },
            label = { Text(CatchStatus.WANT.label) },
            leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        FilterChip(
            selected = status == CatchStatus.CAUGHT,
            onClick = { onSelect(CatchStatus.CAUGHT) },
            label = { Text(CatchStatus.CAUGHT.label) },
            leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun NoteSection(initial: String, key: Int, onSave: (String) -> Unit) {
    var draft by rememberSaveable(key, initial) { mutableStateOf(initial) }
    Column {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Моя заметка") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        if (draft != initial) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { draft = initial }) { Text("Отмена") }
                TextButton(onClick = { onSave(draft) }) { Text("Сохранить") }
            }
        }
    }
}

@Composable
private fun StatBar(name: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(displayName(name), modifier = Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall)
        Text(value.toString(), modifier = Modifier.width(36.dp), style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(
            progress = { (value / 200f).coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CollectionsDialog(
    choices: List<CollectionChoice>,
    onToggle: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Коллекции") },
        text = {
            Column {
                if (choices.isEmpty()) {
                    Text("У вас ещё нет коллекций — создайте первую.")
                }
                choices.forEach { choice ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(choice.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = choice.selected, onCheckedChange = { onToggle(choice.id) })
                        Text(choice.name)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Новая коллекция") },
                    singleLine = true,
                )
                AssistChip(
                    onClick = {
                        onCreate(newName)
                        newName = ""
                    },
                    enabled = newName.isNotBlank(),
                    label = { Text("Создать и добавить") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}
