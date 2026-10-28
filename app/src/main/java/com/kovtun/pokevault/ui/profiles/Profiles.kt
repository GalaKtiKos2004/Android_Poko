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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfilesUiState(
    val isLoading: Boolean = true,
    val profiles: List<Profile> = emptyList(),
    val activeId: Long? = null,
    /** Text of the "new profile" field; kept in the VM so a rejected name is not lost. */
    val nameInput: String = "",
    val createError: String? = null,
    val isCreating: Boolean = false,
)

sealed interface ProfilesEvent {
    data class NameChanged(val name: String) : ProfilesEvent
    data object Create : ProfilesEvent
    data class Select(val profileId: Long) : ProfilesEvent
    data class Delete(val profileId: Long) : ProfilesEvent
}

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val session: SessionRepository,
) : ViewModel() {

    private data class Form(val name: String = "", val error: String? = null, val creating: Boolean = false)

    private val form = MutableStateFlow(Form())
    private val _chosen = Channel<Long>(Channel.BUFFERED)
    /** Fires when a profile became active and the app can open the main section. */
    val chosen: Flow<Long> = _chosen.receiveAsFlow()

    val uiState: StateFlow<ProfilesUiState> =
        combine(profiles.observeProfiles(), session.session, form) { list, s, f ->
            ProfilesUiState(
                isLoading = false,
                profiles = list,
                activeId = (s as? SessionState.Active)?.profile?.id,
                nameInput = f.name,
                createError = f.error,
                isCreating = f.creating,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUiState())

    fun onEvent(event: ProfilesEvent) {
        when (event) {
            is ProfilesEvent.NameChanged -> form.update { it.copy(name = event.name, error = null) }
            ProfilesEvent.Create -> create()
            else -> viewModelScope.launch { handle(event) }
        }
    }

    /** The field is cleared only after the profile was really created. */
    private fun create() {
        val current = form.value
        if (current.creating) return
        form.update { it.copy(creating = true) }
        viewModelScope.launch {
            profiles.createProfile(current.name)
                .onSuccess { id ->
                    form.value = Form()
                    session.switchTo(id)
                    _chosen.send(id)
                }
                .onFailure { error -> form.update { it.copy(error = error.message, creating = false) } }
        }
    }

    private suspend fun handle(event: ProfilesEvent) {
        when (event) {
            is ProfilesEvent.Select -> {
                session.switchTo(event.profileId)
                _chosen.send(event.profileId)
            }
            is ProfilesEvent.Delete -> profiles.deleteProfile(event.profileId)
            is ProfilesEvent.NameChanged, ProfilesEvent.Create -> Unit
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
                value = state.nameInput,
                onValueChange = { onEvent(ProfilesEvent.NameChanged(it)) },
                label = { Text("Имя нового профиля") },
                isError = state.createError != null,
                supportingText = state.createError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onEvent(ProfilesEvent.Create) },
                enabled = state.nameInput.isNotBlank() && !state.isCreating,
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
