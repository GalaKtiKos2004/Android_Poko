package com.kovtun.pokevault.ui.navigation

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kovtun.pokevault.ui.collections.CollectionDetailRoute
import com.kovtun.pokevault.ui.collections.CollectionsRoute
import com.kovtun.pokevault.ui.detail.DetailRoute
import com.kovtun.pokevault.ui.history.HistoryRoute
import com.kovtun.pokevault.ui.pokedex.PokedexRoute
import com.kovtun.pokevault.ui.profiles.ProfilesRoute
import com.kovtun.pokevault.ui.settings.SettingsRoute

object Routes {
    const val PROFILES = "profiles"
    const val POKEDEX = "pokedex"
    const val COLLECTIONS = "collections"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    const val ARG_ID = "id"
    const val DETAIL = "detail/{$ARG_ID}"
    fun detail(id: Int) = "detail/$id"

    const val ARG_COLLECTION_ID = "collectionId"
    const val COLLECTION = "collection/{$ARG_COLLECTION_ID}"
    fun collection(id: Long) = "collection/$id"
}

private data class TopLevel(val route: String, val label: String, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(Routes.POKEDEX, "Покедекс", Icons.Filled.Search),
    TopLevel(Routes.COLLECTIONS, "Коллекции", Icons.Filled.CollectionsBookmark),
    TopLevel(Routes.HISTORY, "История", Icons.Filled.History),
    TopLevel(Routes.SETTINGS, "Настройки", Icons.Filled.Settings),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppNavHost(hasActiveProfile: Boolean) {
    val navController = rememberNavController()
    // Start destination is decided once; later session changes navigate explicitly.
    val start = remember { if (hasActiveProfile) Routes.POKEDEX else Routes.PROFILES }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    LaunchedEffect(hasActiveProfile) {
        if (!hasActiveProfile && navController.currentDestination?.route != Routes.PROFILES) {
            navController.navigate(Routes.PROFILES) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (topLevel.any { it.route == currentRoute }) {
                NavigationBar {
                    topLevel.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = { navController.navigateTopLevel(item.route) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.PROFILES) {
                ProfilesRoute(
                    onProfileChosen = {
                        navController.navigate(Routes.POKEDEX) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.POKEDEX) {
                PokedexRoute(onOpenDetail = { navController.navigate(Routes.detail(it)) })
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.IntType }),
            ) {
                DetailRoute(onBack = { navController.popBackStack() })
            }
            composable(Routes.COLLECTIONS) {
                CollectionsRoute(onOpenCollection = { navController.navigate(Routes.collection(it)) })
            }
            composable(
                route = Routes.COLLECTION,
                arguments = listOf(navArgument(Routes.ARG_COLLECTION_ID) { type = NavType.LongType }),
            ) {
                CollectionDetailRoute(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { navController.navigate(Routes.detail(it)) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryRoute(onOpenDetail = { navController.navigate(Routes.detail(it)) })
            }
            composable(Routes.SETTINGS) {
                SettingsRoute(onSwitchProfile = { navController.navigate(Routes.PROFILES) })
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        // Pokedex is always the root of the main section (also after choosing a profile).
        popUpTo(Routes.POKEDEX) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
