package com.kovtun.pokevault.ui.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kovtun.pokevault.data.settings.SettingsRepository
import com.kovtun.pokevault.data.settings.ThemeMode
import com.kovtun.pokevault.domain.SessionRepository
import com.kovtun.pokevault.domain.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class RootState(
    val session: SessionState = SessionState.Loading,
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

/** Session + the active profile's theme: switching profile re-themes the whole app. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RootViewModel @Inject constructor(
    sessionRepository: SessionRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<RootState> = sessionRepository.session
        .flatMapLatest { session ->
            if (session is SessionState.Active) {
                settings.profileSettings(session.profile.id).map { RootState(session, it.theme) }
            } else {
                flowOf(RootState(session))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RootState())
}
