package com.kovtun.pokevault.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

sealed interface SessionState {
    data object Loading : SessionState
    data object NoProfile : SessionState
    data class Active(val profile: Profile) : SessionState
}

/** Who is the current local user. Every piece of personal data is scoped by this profile. */
interface SessionRepository {
    val session: Flow<SessionState>
    suspend fun switchTo(profileId: Long)
    suspend fun signOut()
}

/** Emits the active profile id; switching profile re-emits, "no profile" pauses emissions. */
val SessionRepository.activeProfileId: Flow<Long>
    get() = session
        .filterIsInstance<SessionState.Active>()
        .map { it.profile.id }
        .distinctUntilChanged()
