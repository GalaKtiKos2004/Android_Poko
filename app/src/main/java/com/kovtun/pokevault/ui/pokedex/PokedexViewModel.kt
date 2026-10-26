package com.kovtun.pokevault.ui.pokedex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kovtun.pokevault.core.ConnectivityObserver
import com.kovtun.pokevault.core.toUserMessage
import com.kovtun.pokevault.data.repository.PokemonRepository
import com.kovtun.pokevault.data.repository.PresetRepository
import com.kovtun.pokevault.data.repository.UserPokemonRepository
import com.kovtun.pokevault.data.settings.SettingsRepository
import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.domain.FilterPreset
import com.kovtun.pokevault.domain.PokemonFilter
import com.kovtun.pokevault.domain.PokemonSummary
import com.kovtun.pokevault.domain.SessionRepository
import com.kovtun.pokevault.domain.activeProfileId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Screen state = combination of independent streams:
 *  - search query (debounced) and filter chips  — user input
 *  - active profile                             — session (DataStore + Room)
 *  - list from Room joined with profile statuses — data layer
 *  - remote lookup (flatMapLatest, cancels stale requests)
 *  - refresh/retry actions (SharedFlow) and their network state
 *  - connectivity (auto-retry on reconnect, offline banner)
 *  - saved presets, profile default sort        — Room / DataStore
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class PokedexViewModel @Inject constructor(
    private val pokemonRepository: PokemonRepository,
    private val userPokemonRepository: UserPokemonRepository,
    private val presetRepository: PresetRepository,
    private val settings: SettingsRepository,
    session: SessionRepository,
    connectivity: ConnectivityObserver,
) : ViewModel() {

    private enum class RefreshRequest { INITIAL, USER, RETRY }

    private val queryInput = MutableStateFlow("")
    private val filterInput = MutableStateFlow(PokemonFilter())
    private val refreshRequests = MutableSharedFlow<RefreshRequest>(extraBufferCapacity = 8)
    private val network = MutableStateFlow<NetworkState>(NetworkState.Loading)
    private val loadingMore = MutableStateFlow(false)
    private val canLoadMore = MutableStateFlow(true)

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** One-time messages (snackbar). Kept out of the state on purpose. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val profileId: Flow<Long> =
        session.activeProfileId.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val debouncedQuery: Flow<String> = queryInput
        .debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        .map { it.trim().lowercase() }
        .distinctUntilChanged()

    private val effectiveFilter: Flow<PokemonFilter> =
        combine(debouncedQuery, filterInput) { query, filter -> filter.copy(query = query) }
            .distinctUntilChanged()

    private val items: Flow<List<PokemonSummary>> =
        combine(profileId, effectiveFilter) { id, filter -> id to filter }
            .flatMapLatest { (id, filter) -> pokemonRepository.observeList(id, filter) }

    /** A newer query cancels the previous server lookup (flatMapLatest). */
    private val remoteSearch: Flow<RemoteSearch> = debouncedQuery.flatMapLatest { query ->
        if (query.length < MIN_REMOTE_QUERY) {
            flowOf<RemoteSearch>(RemoteSearch.Idle)
        } else {
            flow<RemoteSearch> {
                if (pokemonRepository.hasLocalMatch(query)) {
                    emit(RemoteSearch.Idle)
                } else {
                    emit(RemoteSearch.Searching)
                    val result = pokemonRepository.lookupRemote(query)
                    emit(
                        result.fold(
                            onSuccess = { found -> if (found == null) RemoteSearch.NotFound else RemoteSearch.Idle },
                            onFailure = { RemoteSearch.Failed },
                        ),
                    )
                }
            }
        }
    }

    private val statuses: Flow<Map<Int, CatchStatus>> =
        profileId.flatMapLatest { userPokemonRepository.observeStatuses(it) }

    private val presets: Flow<List<FilterPreset>> =
        profileId.flatMapLatest { presetRepository.observe(it) }

    private data class ListPart(val content: ListContent, val remote: RemoteSearch)

    private data class Extras(
        val statuses: Map<Int, CatchStatus>,
        val presets: List<FilterPreset>,
        val online: Boolean,
        val loadingMore: Boolean,
        val canLoadMore: Boolean,
    )

    private val listPart: Flow<ListPart> =
        combine(items, network, remoteSearch, effectiveFilter) { list, net, remote, filter ->
            ListPart(PokedexReducer.content(list, net, remote, filtered = !filter.isDefault), remote)
        }

    private val extras: Flow<Extras> =
        combine(statuses, presets, connectivity.isOnline, loadingMore, canLoadMore) { s, p, online, more, canMore ->
            Extras(s, p, online, more, canMore)
        }

    val uiState: StateFlow<PokedexUiState> =
        combine(listPart, extras, queryInput, filterInput, network) { list, extra, query, filter, net ->
            PokedexUiState(
                query = query,
                filter = filter,
                content = list.content,
                statuses = extra.statuses,
                presets = extra.presets,
                isRefreshing = net is NetworkState.Loading && list.content is ListContent.Success,
                refreshError = (net as? NetworkState.Error)?.message
                    ?.takeIf { list.content is ListContent.Success },
                isLoadingMore = extra.loadingMore,
                canLoadMore = extra.canLoadMore,
                isOffline = !extra.online,
                remoteSearch = list.remote,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PokedexUiState())

    init {
        // Stream of refresh actions; a new request cancels the running one.
        viewModelScope.launch {
            refreshRequests
                .onStart { emit(RefreshRequest.INITIAL) }
                .collectLatest { request ->
                    network.value = NetworkState.Loading
                    val result = pokemonRepository.refreshFirstPage(force = request != RefreshRequest.INITIAL)
                    network.value = result.fold(
                        onSuccess = { NetworkState.Idle },
                        onFailure = { NetworkState.Error(it.toUserMessage()) },
                    )
                }
        }
        // Network came back after a failure -> retry automatically.
        viewModelScope.launch {
            connectivity.isOnline
                .distinctUntilChanged()
                .drop(1)
                .filter { it }
                .collect {
                    if (network.value is NetworkState.Error) refreshRequests.emit(RefreshRequest.RETRY)
                }
        }
        // Each profile has its own default sort order.
        viewModelScope.launch {
            profileId
                .flatMapLatest { settings.profileSettings(it) }
                .map { it.defaultSort }
                .distinctUntilChanged()
                .collect { sort -> filterInput.update { it.copy(sort = sort) } }
        }
    }

    fun onEvent(event: PokedexEvent) {
        when (event) {
            is PokedexEvent.QueryChanged -> queryInput.value = event.query
            is PokedexEvent.TypeSelected -> filterInput.update { it.copy(type = event.type) }
            is PokedexEvent.StatusSelected -> filterInput.update { it.copy(status = event.status) }
            is PokedexEvent.SortSelected -> filterInput.update { it.copy(sort = event.sort) }
            PokedexEvent.ClearFilters -> {
                queryInput.value = ""
                filterInput.update { PokemonFilter(sort = it.sort) }
            }
            PokedexEvent.Refresh -> refreshRequests.tryEmit(RefreshRequest.USER)
            PokedexEvent.Retry -> refreshRequests.tryEmit(RefreshRequest.RETRY)
            PokedexEvent.LoadMore -> loadMore()
            is PokedexEvent.ApplyPreset -> {
                queryInput.value = event.preset.filter.query
                filterInput.value = event.preset.filter.copy(query = "")
            }
            is PokedexEvent.SavePreset -> savePreset(event.name)
            is PokedexEvent.DeletePreset -> viewModelScope.launch { presetRepository.delete(event.presetId) }
        }
    }

    private fun loadMore() {
        if (loadingMore.value || !canLoadMore.value) return
        loadingMore.value = true
        viewModelScope.launch {
            pokemonRepository.loadNextPage()
                .onSuccess { hasMore -> canLoadMore.value = hasMore }
                .onFailure { _messages.send(it.toUserMessage()) }
            loadingMore.value = false
        }
    }

    private fun savePreset(name: String) {
        val filter = filterInput.value.copy(query = queryInput.value.trim())
        viewModelScope.launch {
            val id = profileId.first()
            presetRepository.save(id, name, filter)
                .onSuccess { _messages.send("Фильтр «${name.trim()}» сохранён") }
                .onFailure { _messages.send(it.message ?: "Не удалось сохранить") }
        }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 400L
        const val MIN_REMOTE_QUERY = 3
    }
}
