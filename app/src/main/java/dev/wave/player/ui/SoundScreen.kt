package dev.wave.player.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wave.player.playback.AudioFx
import dev.wave.player.playback.FxParams

@Composable
fun SoundScreen(
    fx: FxParams,
    now: NowPlaying,
    onBack: () -> Unit,
    onChange: (FxParams) -> Unit,
    onPreset: (String) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val live = rememberLiveSpectrum(now.isPlaying)
    val progress = if (now.durationMs > 0) now.positionMs.toFloat() / now.durationMs else 0f
    // Any manual change turns the preset into "Custom".
    fun edit(p: FxParams) = onChange(p.copy(preset = "Custom"))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            NeuIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Text(
                "SOUND", color = Neu.Muted, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(44.dp))
        }

        // Big bass knob
        Box(Modifier.padding(top = 18.dp).size(260.dp), contentAlignment = Alignment.Center) {
            Knob(
                value = fx.bassDb,
                range = AudioFx.BASS_MIN..AudioFx.BASS_MAX,
                onChange = { edit(fx.copy(bassDb = it)) },
                bipolar = true,
                glow = live.bass,
                modifier = Modifier.fillMaxSize(),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%+.1f".format(fx.bassDb), color = Neu.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text("dB BASS", color = Neu.Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            }
        }
        Row(Modifier.width(220.dp)) {
            Text("−", color = Neu.Muted, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text("+", color = Neu.Muted, fontSize = 18.sp)
        }

        // Presets
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 18.dp),
        ) {
            items(AudioFx.presets) { p ->
                val selected = p.name == fx.preset
                Text(
                    p.name,
                    color = if (selected) Neu.Amber else Neu.Text,
                    fontSize = 13.sp,
                    modifier = Modifier.neu(RoundedCornerShape(50), 5.dp, pressed = selected)
                        .clickable { onPreset(p.name) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }

        // Sliders
        Row(
            Modifier.fillMaxWidth().padding(top = 26.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            VSlider(
                value = fx.freqHz, range = AudioFx.FREQ_MIN..AudioFx.FREQ_MAX,
                onChange = { edit(fx.copy(freqHz = it)) },
                top = "${AudioFx.FREQ_MAX.toInt()} Hz", bottom = "${AudioFx.FREQ_MIN.toInt()} Hz",
                label = "FREQ", valueText = "${fx.freqHz.toInt()} Hz",
            )
            VSlider(
                value = fx.punch, range = 0f..1f,
                onChange = { edit(fx.copy(punch = it)) },
                top = "MAX", bottom = "OFF",
                label = "PUNCH", valueText = "${(fx.punch * 100).toInt()}%",
            )
            VSlider(
                value = fx.preampDb, range = AudioFx.PREAMP_MIN..AudioFx.PREAMP_MAX,
                onChange = { edit(fx.copy(preampDb = it)) },
                top = "+${AudioFx.PREAMP_MAX.toInt()} dB", bottom = "${AudioFx.PREAMP_MIN.toInt()} dB",
                label = "PREAMP", valueText = "%+.1f dB".format(fx.preampDb),
            )
        }

        Text(
            "Freq sets where the bass starts. Punch adds harmonics so bass is felt even on phone speakers. " +
                "Lower Preamp if loud songs pump.",
            color = Neu.Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp, start = 8.dp, end = 8.dp),
        )

        // Live spectrum
        SpectrumBars(live, Modifier.padding(top = 22.dp).fillMaxWidth().height(70.dp))

        // Transport
        Column(Modifier.padding(top = 22.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EmberPlayButton(now.isPlaying, onTogglePlay, size = 66.dp, progress = progress)
            Text(formatTime(now.positionMs), color = Neu.Text, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            Text(now.title, color = Neu.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(
                Modifier.padding(top = 12.dp).neu(RoundedCornerShape(50), 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clickable(onClick = onPrevious).padding(horizontal = 22.dp, vertical = 10.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", tint = Neu.Amber)
                }
                Box(Modifier.width(1.dp).height(22.dp).neuInset(RoundedCornerShape(1.dp), 1.dp))
                Box(Modifier.clickable(onClick = onNext).padding(horizontal = 22.dp, vertical = 10.dp)) {
                    Icon(Icons.Filled.SkipNext, "Next", tint = Neu.Amber)
                }
            }
        }
    }
}
