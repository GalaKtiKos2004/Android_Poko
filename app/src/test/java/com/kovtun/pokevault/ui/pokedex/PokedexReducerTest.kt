package com.kovtun.pokevault.ui.pokedex

import com.kovtun.pokevault.testutil.summary
import org.junit.Assert.assertEquals
import org.junit.Test

class PokedexReducerTest {

    @Test
    fun `cached items win over a network error (offline-first)`() {
        val items = listOf(summary(1, "bulbasaur"))
        val content = PokedexReducer.content(items, NetworkState.Error("offline"), RemoteSearch.Idle, filtered = false)
        assertEquals(ListContent.Success(items), content)
    }

    @Test
    fun `empty cache while loading is Loading, after failure is Error`() {
        assertEquals(
            ListContent.Loading,
            PokedexReducer.content(emptyList(), NetworkState.Loading, RemoteSearch.Idle, filtered = false),
        )
        assertEquals(
            ListContent.Error("offline"),
            PokedexReducer.content(emptyList(), NetworkState.Error("offline"), RemoteSearch.Idle, filtered = false),
        )
    }

    @Test
    fun `empty filtered result is Empty, never Success(emptyList) or Error`() {
        assertEquals(
            ListContent.Empty(filtered = true),
            PokedexReducer.content(emptyList(), NetworkState.Error("offline"), RemoteSearch.Failed, filtered = true),
        )
        assertEquals(
            ListContent.Loading,
            PokedexReducer.content(emptyList(), NetworkState.Idle, RemoteSearch.Searching, filtered = true),
        )
    }
}
