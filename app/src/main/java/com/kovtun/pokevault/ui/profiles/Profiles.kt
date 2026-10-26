package com.kovtun.pokevault.ui.profiles

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kovtun.pokevault.data.repository.ProfileRepository
import com.kovtun.pokevault.domain.Profile
import com.kovtun.pokevault.domain.SessionRepository
import com.kovtun.pokevault.domain.SessionState
import com.kovtun.pokevault.ui.theme.ProfileColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfilesUiState(
    val isLoading: Boolean = true,
    val profiles: List<Profile> = emptyList(),
    val activeId: Long? = null,
    val createError: String? = null,
)

sealed interface ProfilesEvent {
    data class Create(val name: String) : ProfilesEvent
    data class Select(val profileId: Long) : ProfilesEvent
    data class Delete(val profileId: Long) : ProfilesEvent
}

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val session: SessionRepository,
) : ViewModel() {

    private val createError = MutableStateFlow<String?>(null)
    private val _chosen = Channel<Long>(Channel.BUFFERED)
    /** Fires when a profile became active and the app can open the main section. */
    val chosen: Flow<Long> = _chosen.receiveAsFlow()

    val uiState: StateFlow<ProfilesUiState> =
        combine(profiles.observeProfiles(), session.session, createError) { list, s, error ->
            ProfilesUiState(
                isLoading = false,
                profiles = list,
                activeId = (s as? SessionState.Active)?.profile?.id,
                createError = error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUiState())

    fun onEvent(event: ProfilesEvent) {
        viewModelScope.launch {
            when (event) {
                is ProfilesEvent.Create -> profiles.createProfile(event.name)
                    .onSuccess { id ->
                        createError.value = null
                        session.switchTo(id)
                        _chosen.send(id)
                    }
                    .onFailure { createError.value = it.message }
                is ProfilesEvent.Select -> {
                    session.switchTo(event.profileId)
                    _chosen.send(event.profileId)
                }
                is ProfilesEvent.Delete -> profiles.deleteProfile(event.profileId)
            }
        }
    }
}

@Composable
fun ProfilesRoute(onProfileChosen: () -> Unit, viewModel: ProfilesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.chosen.collect { onProfileChosen() } }
    ProfilesScreen(state, viewModel::onEvent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(state: ProfilesUiState, onEvent: (ProfilesEvent) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Кто тренируется?") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                "Каждый профиль хранит свои коллекции, статусы, заметки, историю, фильтры и настройки.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        active = profile.id == state.activeId,
                        onClick = { onEvent(ProfilesEvent.Select(profile.id)) },
                        onDelete = { confirmDelete = profile.id },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя нового профиля") },
                isError = state.createError != null,
                supportingText = state.createError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    onEvent(ProfilesEvent.Create(name))
                    name = ""
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Создать профиль") }
        }
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить профиль?") },
            text = { Text("Все коллекции, заметки, история и фильтры этого профиля будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    onEvent(ProfilesEvent.Delete(id))
                    confirmDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ProfileCard(profile: Profile, active: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile)
            Spacer(Modifier.width(12.dp))
            Text(profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (active) Icon(Icons.Filled.Check, contentDescription = "Активный")
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Удалить") }
        }
    }
}

@Composable
fun Avatar(profile: Profile, size: Int = 44) {
    Box(
        Modifier
            .size(size.dp)
            .background(ProfileColors[profile.colorIndex % ProfileColors.size], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profile.name.take(1).uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
