package dev.wave.player.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import dev.wave.player.library.Song

@Composable
fun LibraryScreen(
    ui: UiState,
    now: NowPlaying,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onSelectPlaylist: (String) -> Unit,
    onNewPlaylist: (String) -> Unit,
    onPlay: (playlistId: String, index: Int) -> Unit,
    onAddSongs: () -> Unit,
    onClearUploads: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenPlayer: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var newPlaylist by remember { mutableStateOf(false) }
    val playlist = ui.library?.playlists?.firstOrNull { it.id == ui.selectedPlaylistId }
    val progress = if (now.durationMs > 0) now.positionMs.toFloat() / now.durationMs else 0f

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            // Header: refresh · spinning disc · add songs
            item {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        (playlist?.name ?: "PlayitLoud").uppercase() + (ui.library?.let { "  ·  ${it.songCount} SONGS" } ?: ""),
                        color = Neu.Muted, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (ui.signedIn) NeuIconButton(Icons.Filled.Refresh, "Refresh", onRefresh, size = 48)
                        }
                        Box(
                            Modifier.size(150.dp).neu(CircleShape, 10.dp).clickable(enabled = now.songId != null, onClick = onOpenPlayer)
                                .padding(10.dp),
                        ) { Vinyl(isPlaying = now.isPlaying, seed = now.songId ?: "idle", modifier = Modifier.fillMaxSize()) }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (ui.library != null) NeuIconButton(Icons.Filled.CloudUpload, "Add songs", onAddSongs, size = 48)
                        }
                    }
                    if (ui.loading) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().padding(top = 16.dp).height(2.dp),
                            color = Neu.Amber, trackColor = Neu.Pressed,
                        )
                    }
                    ui.message?.let { msg ->
                        Text(
                            msg, color = Neu.Amber, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onDismissMessage).padding(top = 12.dp),
                        )
                    }
                }
            }

            if (!ui.signedIn) item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 24.dp).neu(RoundedCornerShape(22.dp)).clickable(onClick = onSignIn).padding(20.dp),
                ) {
                    Text("Connect Google Drive", color = Neu.Text, fontWeight = FontWeight.Medium)
                    Text(
                        "Songs stream from the “PlayitLoud Music” folder. Only a small cache stays on your phone.",
                        color = Neu.Muted, fontSize = 13.sp,
                    )
                }
            }

            // Playlists
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
                    items(ui.library?.playlists.orEmpty(), key = { it.id }) { p ->
                        val selected = p.id == ui.selectedPlaylistId
                        Text(
                            "${p.name}  ${p.songs.size}",
                            color = if (selected) Neu.Amber else Neu.Text,
                            fontSize = 14.sp,
                            modifier = Modifier.neu(RoundedCornerShape(50), elevation = 5.dp, pressed = selected)
                                .clickable { onSelectPlaylist(p.id) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    if (ui.library != null) item {
                        Box(Modifier.size(40.dp).neu(CircleShape, 5.dp).clickable { newPlaylist = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, "New playlist", tint = Neu.Text)
                        }
                    }
                }
            }

            if (playlist != null && playlist.songs.isEmpty()) item {
                Text(
                    "No songs here yet. Tap the upload button, or drop audio files into this folder in Google Drive.",
                    color = Neu.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp),
                )
            }

            itemsIndexed(playlist?.songs.orEmpty(), key = { _, s -> s.id }) { i, s ->
                SongRow(
                    song = s,
                    current = s.id == now.songId,
                    playing = s.id == now.songId && now.isPlaying,
                    onClick = { if (s.id == now.songId) onOpenPlayer() else onPlay(playlist!!.id, i) },
                    onButton = { if (s.id == now.songId) onTogglePlay() else onPlay(playlist!!.id, i) },
                )
            }

            if (ui.uploads.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Uploads", color = Neu.Text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearUploads) { Text("Clear finished", color = Neu.Muted, fontSize = 12.sp) }
                }
            }
            items(ui.uploads, key = { it.id }) { u -> UploadRow(u) }
        }

        // Mini player
        if (now.songId != null) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp).neu(RoundedCornerShape(24.dp), 7.dp).clickable(onClick = onOpenPlayer)
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Vinyl(isPlaying = now.isPlaying, seed = now.songId, modifier = Modifier.size(46.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(now.title, color = Neu.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(now.playlist, color = Neu.Muted, fontSize = 12.sp, maxLines = 1)
                }
                EmberPlayButton(now.isPlaying, onTogglePlay, size = 40.dp, progress = progress)
            }
        }
    }

    if (newPlaylist) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newPlaylist = false },
            title = { Text("New playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { onNewPlaylist(name); newPlaylist = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newPlaylist = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SongRow(song: Song, current: Boolean, playing: Boolean, onClick: () -> Unit, onButton: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).animateContentSize()
            .then(if (current) Modifier.neuInset(shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                song.title, color = Neu.Text, fontSize = 15.sp,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${song.extension.uppercase()} · ${formatSize(song.size)}" + if (song.lyricsId != null) " · lyrics" else "",
                color = Neu.Muted, fontSize = 12.sp,
            )
        }
        if (current) {
            EmberPlayButton(playing, onButton, size = 38.dp)
        } else {
            Box(Modifier.size(38.dp).neu(CircleShape, 4.dp).clickable(onClick = onButton), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, "Play", tint = Neu.Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun UploadRow(u: UploadItem) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val status = when (u.state) {
            WorkInfo.State.SUCCEEDED -> "Uploaded"
            WorkInfo.State.FAILED -> u.error ?: "Failed"
            WorkInfo.State.CANCELLED -> "Cancelled"
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Waiting for network"
            WorkInfo.State.RUNNING -> if (u.progress >= 0) "${u.progress}%" else "Uploading…"
        }
        Row {
            Text(u.name, color = Neu.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(status, color = Neu.Muted, fontSize = 12.sp)
        }
        if (u.state == WorkInfo.State.RUNNING) {
            Spacer(Modifier.height(6.dp))
            if (u.progress >= 0) {
                LinearProgressIndicator(progress = { u.progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Neu.Amber, trackColor = Neu.Pressed)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Neu.Amber, trackColor = Neu.Pressed)
            }
        }
    }
}
