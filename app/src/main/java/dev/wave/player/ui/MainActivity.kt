package dev.wave.player.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.wave.player.auth.GoogleAuth
import kotlinx.coroutines.launch

enum class Screen { Library, Player, Sound }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        GoogleAuth.acceptIntent(this, res.data)
        if (GoogleAuth.isSignedIn) vm.onSignedIn() else vm.onSignInFailed("Drive access was not granted")
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addSongs(uris)
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        signIn(silentOnly = true)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by vm.ui.collectAsStateWithLifecycle()
                val now by vm.now.collectAsStateWithLifecycle()
                val fx by vm.fx.collectAsStateWithLifecycle()
                var screen by rememberSaveable { mutableStateOf(Screen.Library) }
                BackHandler(enabled = screen != Screen.Library) {
                    screen = if (screen == Screen.Sound) Screen.Player else Screen.Library
                }

                Box(Modifier.fillMaxSize().background(Neu.Bg).safeDrawingPadding()) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val forward = targetState.ordinal > initialState.ordinal
                            (slideInVertically(tween(380)) { h -> if (forward) h / 6 else -h / 10 } + fadeIn(tween(320)))
                                .togetherWith(fadeOut(tween(200)) + scaleOut(tween(260), targetScale = if (forward) 0.96f else 1.02f))
                        },
                        label = "screens",
                    ) { s ->
                        when (s) {
                            Screen.Library -> LibraryScreen(
                                ui = ui, now = now,
                                onSignIn = { signIn(silentOnly = false) },
                                onRefresh = vm::refresh,
                                onSelectPlaylist = vm::selectPlaylist,
                                onNewPlaylist = vm::createPlaylist,
                                onPlay = { pl, i -> vm.play(pl, i); screen = Screen.Player },
                                onAddSongs = { pickAudio.launch(arrayOf("audio/*", "application/ogg")) },
                                onClearUploads = vm::clearFinishedUploads,
                                onTogglePlay = vm::togglePlay,
                                onOpenPlayer = { screen = Screen.Player },
                                onDismissMessage = vm::dismissMessage,
                            )
                            Screen.Player -> NowPlayingScreen(
                                now = now, fx = fx,
                                onBack = { screen = Screen.Library },
                                onOpenSound = { screen = Screen.Sound },
                                onTogglePlay = vm::togglePlay, onNext = vm::next, onPrevious = vm::previous,
                                onShuffle = vm::toggleShuffle, onRepeat = vm::toggleRepeat, onSeek = vm::seekTo,
                            )
                            Screen.Sound -> SoundScreen(
                                fx = fx, now = now,
                                onBack = { screen = Screen.Player },
                                onChange = vm::setFx, onPreset = vm::applyPreset,
                                onTogglePlay = vm::togglePlay, onNext = vm::next, onPrevious = vm::previous,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun signIn(silentOnly: Boolean) {
        lifecycleScope.launch {
            runCatching { GoogleAuth.authorize(this@MainActivity) }
                .onSuccess { result ->
                    when {
                        !result.hasResolution() -> vm.onSignedIn()
                        !silentOnly -> result.pendingIntent?.let {
                            consent.launch(IntentSenderRequest.Builder(it.intentSender).build())
                        }
                        else -> Unit
                    }
                }
                .onFailure { e -> if (!silentOnly) vm.onSignInFailed("Sign-in failed: ${e.message}") }
        }
    }
}
