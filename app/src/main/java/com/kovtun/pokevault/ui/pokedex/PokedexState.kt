package com.kovtun.pokevault.ui.pokedex

import com.kovtun.pokevault.domain.CatchStatus
import com.kovtun.pokevault.domain.FilterPreset
import com.kovtun.pokevault.domain.PokemonFilter
import com.kovtun.pokevault.domain.PokemonSummary
import com.kovtun.pokevault.domain.SortOrder

sealed interface NetworkState {
    data object Idle : NetworkState
    data object Loading : NetworkState
    data class Error(val message: String) : NetworkState
}

/** Server lookup for a name / number that is not in the local cache yet. */
sealed interface RemoteSearch {
    data object Idle : RemoteSearch
    data object Searching : RemoteSearch
    data object NotFound : RemoteSearch
    data object Failed : RemoteSearch
}

sealed interface ListContent {
    data object Loading : ListContent
    data class Error(val message: String) : ListContent
    data class Empty(val filtered: Boolean) : ListContent
    data class Success(val items: List<PokemonSummary>) : ListContent
}

data class PokedexUiState(
    val query: String = "",
    val filter: PokemonFilter = PokemonFilter(),
    val content: ListContent = ListContent.Loading,
    val statuses: Map<Int, CatchStatus> = emptyMap(),
    val presets: List<FilterPreset> = emptyList(),
    val isRefreshing: Boolean = false,
    val refreshError: String? = null,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val isOffline: Boolean = false,
    val remoteSearch: RemoteSearch = RemoteSearch.Idle,
) {
    /** Paging only makes sense for the unfiltered national dex. */
    val showLoadMore: Boolean
        get() = content is ListContent.Success && canLoadMore && filter.isDefault && query.isBlank()
}

sealed interface PokedexEvent {
    data class QueryChanged(val query: String) : PokedexEvent
    data class TypeSelected(val type: String?) : PokedexEvent
    data class StatusSelected(val status: CatchStatus?) : PokedexEvent
    data class SortSelected(val sort: SortOrder) : PokedexEvent
    data object ClearFilters : PokedexEvent
    data object Refresh : PokedexEvent
    data object Retry : PokedexEvent
    data object LoadMore : PokedexEvent
    data class ApplyPreset(val preset: FilterPreset) : PokedexEvent
    data class SavePreset(val name: String) : PokedexEvent
    data class DeletePreset(val presetId: Long) : PokedexEvent
}

/** Pure decision of what the list area shows. Unit-tested separately. */
object PokedexReducer {
    fun content(
        items: List<PokemonSummary>,
        network: NetworkState,
        remote: RemoteSearch,
        filtered: Boolean,
    ): ListContent = when {
        items.isNotEmpty() -> ListContent.Success(items)
        network is NetworkState.Loading || remote is RemoteSearch.Searching -> ListContent.Loading
        network is NetworkState.Error && !filtered -> ListContent.Error(network.message)
        else -> ListContent.Empty(filtered)
    }
}
