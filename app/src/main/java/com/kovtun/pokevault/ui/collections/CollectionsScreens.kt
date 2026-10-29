package com.kovtun.pokevault.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kovtun.pokevault.domain.CollectionItem
import com.kovtun.pokevault.domain.PokemonCollection
import com.kovtun.pokevault.ui.components.EmptyState
import com.kovtun.pokevault.ui.components.LoadingState
import com.kovtun.pokevault.ui.components.PokemonRow

@Composable
fun CollectionsRoute(onOpenCollection: (Long) -> Unit, viewModel: CollectionsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CollectionsScreen(state, viewModel::onEvent, onOpenCollection)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    state: CollectionsUiState,
    onEvent: (CollectionsEvent) -> Unit,
    onOpenCollection: (Long) -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Мои коллекции") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEvent(CollectionsEvent.OpenCreate) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Новая") },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> LoadingState()
                state.collections.isEmpty() -> EmptyState(
                    title = "Коллекций пока нет",
                    subtitle = "Создайте, например, «Команда мечты» и добавляйте покемонов со страницы деталей",
                    icon = Icons.Filled.CollectionsBookmark,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.collections, key = { it.id }) { collection ->
                        CollectionRow(
                            collection = collection,
                            onClick = { onOpenCollection(collection.id) },
                            onRename = { onEvent(CollectionsEvent.OpenRename(collection.id, collection.name)) },
                            onDelete = { confirmDelete = collection.id },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    state.dialog?.let { dialog ->
        CollectionDialogView(
            dialog = dialog,
            onConfirm = { name, description -> onEvent(CollectionsEvent.Confirm(name, description)) },
            onDismiss = { onEvent(CollectionsEvent.DismissDialog) },
        )
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить коллекцию?") },
            text = { Text("Покемоны останутся в Покедексе, удалится только подборка и заметки в ней.") },
            confirmButton = {
                TextButton(onClick = {
                    onEvent(CollectionsEvent.Delete(id))
                    confirmDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun CollectionRow(
    collection: PokemonCollection,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (collection.coverSpriteUrl != null) {
                AsyncImage(model = collection.coverSpriteUrl, contentDescription = null, modifier = Modifier.size(52.dp))
            } else {
                Icon(Icons.Filled.CollectionsBookmark, contentDescription = null)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(collection.name, style = MaterialTheme.typography.titleMedium)
            Text(
                pokemonCountText(collection.pokemonCount) +
                    if (collection.description.isNotBlank()) " · ${collection.description}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, contentDescription = "Переименовать") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Удалить") }
    }
}

@Composable
private fun CollectionDialogView(
    dialog: CollectionDialog,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isRename = dialog is CollectionDialog.Rename
    val initial = (dialog as? CollectionDialog.Rename)?.currentName.orEmpty()
    val error = when (dialog) {
        is CollectionDialog.Create -> dialog.error
        is CollectionDialog.Rename -> dialog.error
    }
    var name by rememberSaveable(isRename, initial) { mutableStateOf(initial) }
    var description by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRename) "Переименовать" else "Новая коллекция") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    singleLine = true,
                )
                if (!isRename) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Описание (необязательно)") },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, description) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

fun pokemonCountText(count: Int): String {
    val mod10 = count % 10
    val mod100 = count % 100
    val word = when {
        mod10 == 1 && mod100 != 11 -> "покемон"
        mod10 in 2..4 && mod100 !in 12..14 -> "покемона"
        else -> "покемонов"
    }
    return "$count $word"
}

// ------------------------------------------------------------------ Collection detail

@Composable
fun CollectionDetailRoute(
    onBack: () -> Unit,
    onOpenDetail: (Int) -> Unit,
    viewModel: CollectionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CollectionDetailScreen(state, viewModel::onEvent, onBack, onOpenDetail)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    state: CollectionDetailUiState,
    onEvent: (CollectionDetailEvent) -> Unit,
    onBack: () -> Unit,
    onOpenDetail: (Int) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> LoadingState()
                !state.exists -> EmptyState(title = "Коллекция удалена")
                state.items.isEmpty() -> EmptyState(
                    title = "В коллекции пусто",
                    subtitle = "Откройте покемона и нажмите «Добавить в коллекцию»",
                    icon = Icons.Filled.CollectionsBookmark,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.items, key = { it.pokemonId }) { item ->
                        PokemonRow(
                            id = item.pokemonId,
                            name = item.name,
                            spriteUrl = item.spriteUrl,
                            types = item.types,
                            onClick = { onOpenDetail(item.pokemonId) },
                            subtitle = item.note.ifBlank { null },
                            trailing = {
                                Row {
                                    IconButton(onClick = { editing = item.pokemonId }) {
                                        Icon(Icons.Filled.EditNote, contentDescription = "Заметка")
                                    }
                                    IconButton(onClick = { onEvent(CollectionDetailEvent.Remove(item.pokemonId)) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Убрать")
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    val editedItem: CollectionItem? = editing?.let { id -> state.items.firstOrNull { it.pokemonId == id } }
    if (editedItem != null) {
        var note by rememberSaveable(editedItem.pokemonId) { mutableStateOf(editedItem.note) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Заметка в коллекции") },
            text = {
                OutlinedTextField(value = note, onValueChange = { note = it }, minLines = 3, label = { Text("Например: для какой команды") })
            },
            confirmButton = {
                TextButton(onClick = {
                    onEvent(CollectionDetailEvent.EditNote(editedItem.pokemonId, note))
                    editing = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } },
        )
    }
}
