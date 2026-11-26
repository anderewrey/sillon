package io.github.inobody92.sillon

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.AppSettings
import kotlinx.coroutines.launch
import androidx.glance.appwidget.updateAll
import io.github.inobody92.sillon.widget.SillonWidget
import androidx.lifecycle.lifecycleScope
import io.github.inobody92.sillon.data.DownloadServerStore
import io.github.inobody92.sillon.data.LibraryIndex
import io.github.inobody92.sillon.data.Likes
import io.github.inobody92.sillon.data.OfflineStore
import io.github.inobody92.sillon.data.ThemeMode
import io.github.inobody92.sillon.ui.AuthState
import io.github.inobody92.sillon.ui.LoggedInHost
import io.github.inobody92.sillon.ui.LoginScreen
import io.github.inobody92.sillon.ui.OfflineHost
import io.github.inobody92.sillon.ui.MainViewModel
import io.github.inobody92.sillon.ui.PlayerViewModel
import io.github.inobody92.sillon.ui.SillonTheme
import io.github.inobody92.sillon.ui.UpdatePrompt

class MainActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_OPEN_PLAYER = "open_player"

        /**
         * Intention qui ramène dans l'application, sur l'écran de lecture : utilisée par la notification du lecteur
         * (volet des notifications, écran verrouillé) et par le widget. Réutilise l'activité déjà ouverte.
         */
        fun openPlayerIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_PLAYER, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            intent.removeExtra(EXTRA_OPEN_PLAYER)
            io.github.inobody92.sillon.ui.OpenPlayerRequest.request()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }


    private val viewModel: MainViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)   // pas après une simple rotation
        AppSettings.init(applicationContext)
        OfflineStore.init(applicationContext)
        Likes.init(applicationContext)
        LibraryIndex.init(applicationContext)
        io.github.inobody92.sillon.data.TasteStore.init(applicationContext)
        DownloadServerStore.init(applicationContext)
        io.github.inobody92.sillon.data.DownloadWatch.resume(applicationContext)
        // Le widget se redessine à chaque ouverture : il reflète toujours le dernier état connu.
        lifecycleScope.launch { io.github.inobody92.sillon.widget.Widgets.updateAll(applicationContext) }
        enableEdgeToEdge()
        setContent {
            val themeMode by AppSettings.theme.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Les icônes de la barre d'état et de navigation suivent le thème choisi, pas celui du système.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.argb(0xe6, 0xFF, 0xFF, 0xFF),
                        Color.argb(0x80, 0x1b, 0x1b, 0x1b),
                    ) { dark },
                )
                onDispose {}
            }
            val dynamic by AppSettings.dynamicColors.collectAsStateWithLifecycle()
            val accent by AppSettings.accent.collectAsStateWithLifecycle()
            SillonTheme(dark, dynamicColors = dynamic, accent = accent) {
                UpdatePrompt()
                Surface(Modifier.fillMaxSize()) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    when (val s = state) {
                        AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        is AuthState.LoggedOut -> LoginScreen(
                            state = s,
                            initialUrl = viewModel.lastUrl,
                            initialAccount = viewModel.lastAccount,
                            initialInsecure = viewModel.lastInsecure,
                            savedPassword = viewModel.hasSavedPassword,
                            onLogin = viewModel::login,
                            onConfirmCertificate = viewModel::confirmCertificate,
                            onRejectCertificate = viewModel::rejectCertificate,
                            modifier = Modifier.safeDrawingPadding(),
                        )
                        AuthState.Offline -> OfflineHost(playerViewModel, onRetry = viewModel::retryConnection)
                        is AuthState.LoggedIn -> LoggedInHost(s.client, playerViewModel, onLogout = viewModel::logout)
                    }
                }
            }
        }
    }
}
