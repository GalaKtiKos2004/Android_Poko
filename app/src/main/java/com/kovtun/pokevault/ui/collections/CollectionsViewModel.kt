package com.kovtun.pokevault.ui.collections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kovtun.pokevault.data.repository.CollectionRepository
import com.kovtun.pokevault.domain.CollectionItem
import com.kovtun.pokevault.domain.PokemonCollection
import com.kovtun.pokevault.domain.SessionRepository
import com.kovtun.pokevault.domain.activeProfileId
import com.kovtun.pokevault.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CollectionDialog {
    data class Create(val error: String? = null) : CollectionDialog
    data class Rename(val collectionId: Long, val currentName: String, val error: String? = null) : CollectionDialog
}

data class CollectionsUiState(
    val isLoading: Boolean = true,
    val collections: List<PokemonCollection> = emptyList(),
    val dialog: CollectionDialog? = null,
)

sealed interface CollectionsEvent {
    data object OpenCreate : CollectionsEvent
    data class OpenRename(val collectionId: Long, val currentName: String) : CollectionsEvent
    data object DismissDialog : CollectionsEvent
    data class Confirm(val name: String, val description: String) : CollectionsEvent
    data class Delete(val collectionId: Long) : CollectionsEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val repository: CollectionRepository,
    session: SessionRepository,
) : ViewModel() {

    private val profileId: Flow<Long> =
        session.activeProfileId.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val dialog = MutableStateFlow<CollectionDialog?>(null)

    val uiState: StateFlow<CollectionsUiState> =
        combine(profileId.flatMapLatest { repository.observeCollections(it) }, dialog) { list, d ->
            CollectionsUiState(isLoading = false, collections = list, dialog = d)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionsUiState())

    fun onEvent(event: CollectionsEvent) {
        when (event) {
            CollectionsEvent.OpenCreate -> dialog.value = CollectionDialog.Create()
            is CollectionsEvent.OpenRename -> dialog.value = CollectionDialog.Rename(event.collectionId, event.currentName)
            CollectionsEvent.DismissDialog -> dialog.value = null
            is CollectionsEvent.Delete -> viewModelScope.launch { repository.deleteCollection(event.collectionId) }
            is CollectionsEvent.Confirm -> confirm(event.name, event.description)
        }
    }

    private fun confirm(name: String, description: String) {
        val current = dialog.value ?: return
        viewModelScope.launch {
            val result = when (current) {
                is CollectionDialog.Create ->
                    repository.createCollection(profileId.first(), name, description).map { }
                is CollectionDialog.Rename -> repository.renameCollection(current.collectionId, name)
            }
            result
                .onSuccess { dialog.value = null }
                .onFailure { error ->
                    // Validation errors stay in the dialog so the user can fix the name.
                    dialog.value = when (current) {
                        is CollectionDialog.Create -> current.copy(error = error.message)
                        is CollectionDialog.Rename -> current.copy(error = error.message)
                    }
                }
        }
    }
}

data class CollectionDetailUiState(
    val name: String = "",
    val isLoading: Boolean = true,
    val items: List<CollectionItem> = emptyList(),
    val exists: Boolean = true,
)

sealed interface CollectionDetailEvent {
    data class Remove(val pokemonId: Int) : CollectionDetailEvent
    data class EditNote(val pokemonId: Int, val note: String) : CollectionDetailEvent
}

@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CollectionRepository,
) : ViewModel() {

    val collectionId: Long =
        checkNotNull(savedStateHandle.get<Long>(Routes.ARG_COLLECTION_ID)) { "collection id is required" }

    val uiState: StateFlow<CollectionDetailUiState> =
        combine(repository.observeCollectionName(collectionId), repository.observeItems(collectionId)) { name, items ->
            CollectionDetailUiState(name = name.orEmpty(), isLoading = false, items = items, exists = name != null)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionDetailUiState())

    fun onEvent(event: CollectionDetailEvent) {
        viewModelScope.launch {
            when (event) {
                is CollectionDetailEvent.Remove -> repository.removeFromCollection(collectionId, event.pokemonId)
                is CollectionDetailEvent.EditNote -> repository.updateNote(collectionId, event.pokemonId, event.note)
            }
        }
    }
}
