package dev.wave.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import dev.wave.player.playback.FxParams

@Composable
fun NowPlayingScreen(
    now: NowPlaying,
    fx: FxParams,
    onBack: () -> Unit,
    onOpenSound: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val live = rememberLiveSpectrum(now.isPlaying)
    val progress = if (now.durationMs > 0) now.positionMs.toFloat() / now.durationMs else 0f

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            NeuIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Text(
                "PLAYING NOW", color = Neu.Muted, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            NeuIconButton(Icons.Filled.Tune, "Sound", onOpenSound, active = fx.preset != "Flat")
        }

        Vinyl(
            isPlaying = now.isPlaying,
            seed = now.songId ?: "idle",
            live = live,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(1f),
        )

        Text(
            now.title.ifEmpty { "Nothing playing" }, color = Neu.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(now.playlist, color = Neu.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))

        WaveformSeek(
            levels = now.wave,
            progress = progress,
            onSeek = { f -> onSeek((f * now.durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(formatTime(now.positionMs), color = Neu.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(formatTime(now.durationMs), color = Neu.Muted, fontSize = 12.sp)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallToggle(
                if (now.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                "Repeat", now.repeatMode != Player.REPEAT_MODE_OFF, onRepeat,
            )
            NeuIconButton(Icons.Filled.SkipPrevious, "Previous", onPrevious, size = 58)
            EmberPlayButton(now.isPlaying, onTogglePlay, size = 82.dp)
            NeuIconButton(Icons.Filled.SkipNext, "Next", onNext, size = 58)
            SmallToggle(Icons.Filled.Shuffle, "Shuffle", now.shuffle, onShuffle)
        }

        // Sound shortcut
        Row(
            Modifier.padding(top = 26.dp).fillMaxWidth().neu(RoundedCornerShape(20.dp), 6.dp).clickable(onClick = onOpenSound)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sound · ${fx.preset}", color = Neu.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Bass ${"%+.0f".format(fx.bassDb)} dB at ${fx.freqHz.toInt()} Hz · Punch ${(fx.punch * 100).toInt()}%",
                    color = Neu.Muted, fontSize = 12.sp,
                )
            }
            SpectrumBars(live, Modifier.width(96.dp).height(28.dp))
        }

        LyricsCard(now, Modifier.padding(top = 20.dp, bottom = 32.dp))
    }
}

@Composable
private fun SmallToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (on) Neu.Amber else Neu.Muted, label = "toggle")
    Box(Modifier.size(40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun LyricsCard(now: NowPlaying, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(280.dp).neuInset(RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
        if (now.lyrics.isEmpty()) {
            Text(
                "No lyrics yet.\nAdd a .lrc file with the same name as the song to its Drive folder.",
                color = Neu.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        val state = rememberLazyListState()
        LaunchedEffect(now.lyricIndex) {
            if (now.lyricIndex >= 0) state.animateScrollToItem((now.lyricIndex - 2).coerceAtLeast(0))
        }
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 60.dp)) {
            itemsIndexed(now.lyrics) { i, line ->
                val active = i == now.lyricIndex
                val synced = now.lyricIndex >= 0
                val color by animateColorAsState(
                    when { active -> Neu.Text; !synced -> Neu.Text.copy(alpha = 0.8f); i < now.lyricIndex -> Neu.Muted.copy(alpha = 0.5f); else -> Neu.Muted },
                    label = "lyric",
                )
                val scale by animateFloatAsState(if (active) 1.06f else 1f, label = "lyricScale")
                Text(
                    line.text.ifEmpty { "♪" },
                    color = color,
                    fontSize = 17.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale; scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    },
                )
            }
        }
    }
}

