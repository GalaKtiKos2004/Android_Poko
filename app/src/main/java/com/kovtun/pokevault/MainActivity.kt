package com.kovtun.pokevault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kovtun.pokevault.data.settings.ThemeMode
import com.kovtun.pokevault.domain.SessionState
import com.kovtun.pokevault.ui.navigation.AppNavHost
import com.kovtun.pokevault.ui.root.RootViewModel
import com.kovtun.pokevault.ui.theme.PokeVaultTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val rootViewModel: RootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val root by rootViewModel.state.collectAsStateWithLifecycle()
            val dark = when (root.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            PokeVaultTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val session = root.session) {
                        SessionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        else -> AppNavHost(hasActiveProfile = session is SessionState.Active)
                    }
                }
            }
        }
    }
}
