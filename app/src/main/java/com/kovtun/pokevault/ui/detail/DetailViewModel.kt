package com.kovtun.pokevault.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kovtun.pokevault.core.ConnectivityObserver
import com.kovtun.pokevault.core.TimeProvider
import com.kovtun.pokevault.core.toUserMessage
import com.kovtun.pokevault.data.repository.CollectionRepository
import com.kovtun.pokevault.data.repository.HistoryRepository
import com.kovtun.pokevault.data.repository.PokemonRepository
import com.kovtun.pokevault.data.repository.UserPokemonRepository
import com.kovtun.pokevault.data.settings.SettingsRepository
import com.kovtun.pokevault.domain.CachePolicy
import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.domain.Freshness
import com.kovtun.pokevault.domain.PokemonDetail
import com.kovtun.pokevault.domain.SessionRepository
import com.kovtun.pokevault.domain.UserPokemonState
import com.kovtun.pokevault.domain.activeProfileId
import com.kovtun.pokevault.ui.navigation.Routes
import com.kovtun.pokevault.ui.pokedex.NetworkState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DetailContent {
    data object Loading : DetailContent
    data class Error(val message: String) : DetailContent
    data class Success(val pokemon: PokemonDetail) : DetailContent
}

data class CollectionChoice(val id: Long, val name: String, val selected: Boolean)

data class DetailUiState(
    val pokemonId: Int = 0,
    val content: DetailContent = DetailContent.Loading,
    val freshness: Freshness = Freshness.MISSING,
    val isRefreshing: Boolean = false,
    val refreshError: String? = null,
    val isOffline: Boolean = false,
    val user: UserPokemonState = UserPokemonState(),
    val collections: List<CollectionChoice> = emptyList(),
)

sealed interface DetailEvent {
    data class SetStatus(val status: CatchStatus) : DetailEvent
    data class SaveNote(val note: String) : DetailEvent
    data class ToggleCollection(val collectionId: Long) : DetailEvent
    data class CreateCollectionAndAdd(val name: String) : DetailEvent
    data object Retry : DetailEvent
    data object Refresh : DetailEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val pokemonRepository: PokemonRepository,
    private val userPokemonRepository: UserPokemonRepository,
    private val collectionRepository: CollectionRepository,
    private val historyRepository: HistoryRepository,
    private val settings: SettingsRepository,
    private val clock: TimeProvider,
    session: SessionRepository,
    connectivity: ConnectivityObserver,
) : ViewModel() {

    val pokemonId: Int = checkNotNull(savedStateHandle.get<Int>(Routes.ARG_ID)) { "pokemon id is required" }

    private val refreshRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    private val network = MutableStateFlow<NetworkState>(NetworkState.Loading)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val profileId: Flow<Long> =
        session.activeProfileId.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val ttl: Flow<Long> = settings.appSettings
        .map { CachePolicy.hoursToMillis(it.cacheTtlHours) }
        .distinctUntilChanged()

    private val collections: Flow<List<CollectionChoice>> = profileId.flatMapLatest { id ->
        combine(
            collectionRepository.observeCollections(id),
            collectionRepository.observeMembership(id, pokemonId),
        ) { all, membership -> all.map { CollectionChoice(it.id, it.name, it.id in membership) } }
    }

    private val user: Flow<UserPokemonState> =
        profileId.flatMapLatest { userPokemonRepository.observe(it, pokemonId) }

    private data class ContentPart(
        val content: DetailContent,
        val freshness: Freshness,
        val refreshing: Boolean,
        val refreshError: String?,
    )

    private val contentPart: Flow<ContentPart> =
        combine(pokemonRepository.observeDetail(pokemonId), network, ttl) { detail, net, ttlMs ->
            val content = when {
                detail != null -> DetailContent.Success(detail)
                net is NetworkState.Error -> DetailContent.Error(net.message)
                else -> DetailContent.Loading
            }
            ContentPart(
                content = content,
                freshness = CachePolicy.freshness(detail?.updatedAt, clock.now(), ttlMs),
                refreshing = detail != null && net is NetworkState.Loading,
                refreshError = (net as? NetworkState.Error)?.message?.takeIf { detail != null },
            )
        }

    val uiState: StateFlow<DetailUiState> =
        combine(contentPart, user, collections, connectivity.isOnline) { part, userState, choices, online ->
            DetailUiState(
                pokemonId = pokemonId,
                content = part.content,
                freshness = part.freshness,
                isRefreshing = part.refreshing,
                refreshError = part.refreshError,
                isOffline = !online,
                user = userState,
                collections = choices,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState(pokemonId = pokemonId))

    init {
        // Cached details are shown at once; the network is hit only if missing/stale (TTL) or forced.
        viewModelScope.launch {
            refreshRequests
                .onStart { emit(false) }
                .collectLatest { force ->
                    network.value = NetworkState.Loading
                    network.value = pokemonRepository.refreshDetail(pokemonId, force).fold(
                        onSuccess = { NetworkState.Idle },
                        onFailure = { NetworkState.Error(it.toUserMessage()) },
                    )
                }
        }
        viewModelScope.launch {
            val id = profileId.first()
            if (settings.profileSettings(id).first().historyEnabled) {
                historyRepository.recordView(id, pokemonId)
            }
        }
    }

    fun onEvent(event: DetailEvent) {
        when (event) {
            DetailEvent.Retry -> refreshRequests.tryEmit(false)
            DetailEvent.Refresh -> refreshRequests.tryEmit(true)
            is DetailEvent.SetStatus -> launchForProfile { id ->
                val current = uiState.value.user.status
                // Tapping the selected status again clears it.
                val next = if (current == event.status) CatchStatus.NONE else event.status
                userPokemonRepository.setStatus(id, pokemonId, next)
            }
            is DetailEvent.SaveNote -> launchForProfile { id ->
                userPokemonRepository.setNote(id, pokemonId, event.note)
                _messages.send("Заметка сохранена")
            }
            is DetailEvent.ToggleCollection -> launchForProfile {
                val selected = uiState.value.collections.firstOrNull { it.id == event.collectionId }?.selected == true
                if (selected) {
                    collectionRepository.removeFromCollection(event.collectionId, pokemonId)
                } else {
                    collectionRepository.addToCollection(event.collectionId, pokemonId)
                }
            }
            is DetailEvent.CreateCollectionAndAdd -> launchForProfile { id ->
                collectionRepository.createCollection(id, event.name, description = "")
                    .onSuccess { newId ->
                        collectionRepository.addToCollection(newId, pokemonId)
                        _messages.send("Добавлено в «${event.name.trim()}»")
                    }
                    .onFailure { _messages.send(it.message ?: "Не удалось создать коллекцию") }
            }
        }
    }

    private fun launchForProfile(block: suspend (Long) -> Unit) {
        viewModelScope.launch { block(profileId.first()) }
    }
}
