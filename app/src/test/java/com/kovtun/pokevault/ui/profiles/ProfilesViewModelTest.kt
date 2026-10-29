package com.kovtun.pokevault.ui.profiles

import app.cash.turbine.test
import com.kovtun.pokevault.domain.SessionState
import com.kovtun.pokevault.testutil.FakeProfileRepository
import com.kovtun.pokevault.testutil.FakeSessionRepository
import com.kovtun.pokevault.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val profiles = FakeProfileRepository()
    private val session = FakeSessionRepository(SessionState.NoProfile)

    @Test
    fun `rejected name stays in the field with an error, typing clears the error`() = runTest {
        profiles.createProfile("Ash")
        val vm = ProfilesViewModel(profiles, session)
        backgroundScope.launch { vm.uiState.collect { } }

        vm.onEvent(ProfilesEvent.NameChanged(" ash "))
        vm.onEvent(ProfilesEvent.Create)
        advanceUntilIdle()

        assertEquals(" ash ", vm.uiState.value.nameInput)
        assertEquals("Такое название уже есть", vm.uiState.value.createError)
        assertEquals(SessionState.NoProfile, session.state.value)

        vm.onEvent(ProfilesEvent.NameChanged(" ash ketchum"))
        advanceUntilIdle()
        assertNull(vm.uiState.value.createError)
    }

    @Test
    fun `successful creation clears the field, activates the profile and fires navigation once`() = runTest {
        val vm = ProfilesViewModel(profiles, session)
        backgroundScope.launch { vm.uiState.collect { } }

        vm.chosen.test {
            vm.onEvent(ProfilesEvent.NameChanged("Misty"))
            vm.onEvent(ProfilesEvent.Create)
            assertEquals(1L, awaitItem())
            expectNoEvents()
        }
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.nameInput)
        assertEquals(1L, (session.state.value as SessionState.Active).profile.id)
    }
}
