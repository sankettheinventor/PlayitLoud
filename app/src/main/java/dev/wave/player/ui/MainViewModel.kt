package dev.wave.player.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.wave.player.cache.MediaCache
import dev.wave.player.library.LibraryIndex
import dev.wave.player.library.LibraryRepository
import dev.wave.player.lyrics.LyricLine
import dev.wave.player.lyrics.LyricsRepository
import dev.wave.player.lyrics.Lrc
import dev.wave.player.playback.AudioFx
import dev.wave.player.playback.FxParams
import dev.wave.player.playback.Visual
import dev.wave.player.playback.WaveformStore
import kotlin.math.sqrt
import dev.wave.player.playback.PlaybackService
import dev.wave.player.upload.UploadWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UploadItem(val id: String, val name: String, val progress: Int, val state: WorkInfo.State, val error: String?)

data class UiState(
    val signedIn: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
    val library: LibraryIndex? = null,
    val selectedPlaylistId: String? = null,
    val uploads: List<UploadItem> = emptyList(),
)

data class NowPlaying(
    val songId: String? = null,
    val title: String = "",
    val playlist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val wave: List<Float> = List(WaveformStore.BINS) { 0f },
    val lyrics: List<LyricLine> = emptyList(),
    val lyricIndex: Int = -1,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LibraryRepository(app)
    private val lyricsRepo = LyricsRepository(app)

    private val _ui = MutableStateFlow(
        repo.cached().let { lib ->
            UiState(library = lib, selectedPlaylistId = lib?.playlists?.firstOrNull()?.id)
        }
    )
    val ui = _ui.asStateFlow()

    private val _now = MutableStateFlow(NowPlaying())
    val now = _now.asStateFlow()

    private val _fx = MutableStateFlow(AudioFx.params)
    val fx = _fx.asStateFlow()

    private val waves = WaveformStore(app)
    private var wave = FloatArray(WaveformStore.BINS)
    private var waveSongId: String? = null
    private var waveDirty = 0
    private val vis = Visual()

    private val controllerFuture = MediaController.Builder(
        app, SessionToken(app, ComponentName(app, PlaybackService::class.java))
    ).buildAsync()
    private var controller: MediaController? = null
    private var lyricsJob: Job? = null
    private val seenUploads = mutableSetOf<String>()

    init {
        controllerFuture.addListener({
            controller = runCatching { controllerFuture.get() }.getOrNull()?.also { attach(it) }
        }, ContextCompat.getMainExecutor(app))

        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosByTagFlow(UploadWorker.TAG).collect { infos -> onUploads(infos) }
        }
        viewModelScope.launch {
            while (true) { tick(); delay(100) }
        }
    }

    // ---------- Library ----------

    fun onSignedIn() {
        _ui.update { it.copy(signedIn = true) }
        refresh()
    }

    fun onSignInFailed(reason: String) = _ui.update { it.copy(message = reason) }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, message = null) }
            runCatching { repo.refresh() }
                .onSuccess { lib ->
                    val keep = _ui.value.selectedPlaylistId?.takeIf { id -> lib.playlists.any { it.id == id } }
                    _ui.update { it.copy(loading = false, library = lib, selectedPlaylistId = keep ?: lib.playlists.firstOrNull()?.id) }
                    warmSelected()
                }
                .onFailure { e -> _ui.update { it.copy(loading = false, message = "Couldn't reach Drive: ${e.message}") } }
        }
    }

    fun selectPlaylist(id: String) {
        _ui.update { it.copy(selectedPlaylistId = id) }
        warmSelected()
    }

    /** Opening a playlist prepares its pipeline: intro audio + lyrics on the phone. */
    private fun warmSelected() {
        val pl = selectedPlaylist() ?: return
        MediaCache.precacheIntros(pl.songs)
        viewModelScope.launch { lyricsRepo.prefetch(pl.songs.mapNotNull { it.lyricsId }) }
    }

    private fun selectedPlaylist() = _ui.value.let { s -> s.library?.playlists?.firstOrNull { it.id == s.selectedPlaylistId } }

    fun createPlaylist(name: String) {
        val lib = _ui.value.library ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.createPlaylist(lib.rootId, name.trim()) }
                .onSuccess { refresh() }
                .onFailure { e -> _ui.update { it.copy(message = "Couldn't create playlist: ${e.message}") } }
        }
    }

    // ---------- Uploads ----------

    fun addSongs(uris: List<Uri>) {
        val lib = _ui.value.library
        if (lib == null) {
            _ui.update { it.copy(message = "Sign in first") }
            return
        }
        val folder = _ui.value.selectedPlaylistId ?: lib.rootId
        uris.forEach { UploadWorker.enqueue(getApplication(), it, folder) }
    }

    private fun onUploads(infos: List<WorkInfo>) {
        val items = infos.sortedBy { it.state.isFinished }.map { w ->
            val data = if (w.state.isFinished) w.outputData else w.progress
            UploadItem(
                id = w.id.toString(),
                name = data.getString(UploadWorker.KEY_NAME) ?: "Song",
                progress = data.getInt(UploadWorker.KEY_PROGRESS, if (w.state == WorkInfo.State.SUCCEEDED) 100 else -1),
                state = w.state,
                error = w.outputData.getString(UploadWorker.KEY_ERROR),
            )
        }
        _ui.update { it.copy(uploads = items.take(20)) }
        val newlyDone = infos.filter { it.state == WorkInfo.State.SUCCEEDED && seenUploads.add(it.id.toString()) }
        if (newlyDone.isNotEmpty() && _ui.value.signedIn) refresh()
    }

    fun clearFinishedUploads() {
        WorkManager.getInstance(getApplication()).pruneWork()
    }

    // ---------- Playback ----------

    private fun attach(c: MediaController) {
        c.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onTrackChanged(c)
            override fun onIsPlayingChanged(isPlaying: Boolean) = _now.update { it.copy(isPlaying = isPlaying) }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) = _now.update { it.copy(shuffle = enabled) }
            override fun onRepeatModeChanged(repeatMode: Int) = _now.update { it.copy(repeatMode = repeatMode) }
        })
        onTrackChanged(c)
    }

    private fun onTrackChanged(c: MediaController) {
        val id = c.currentMediaItem?.mediaId
        val lib = _ui.value.library
        val song = id?.let { lib?.song(it) }
        if (id != waveSongId) {
            waveSongId?.let { waves.save(it, wave) }
            wave = id?.let { waves.load(it) } ?: FloatArray(WaveformStore.BINS)
            waveSongId = id
            waveDirty = 0
        }
        _now.update {
            it.copy(
                songId = id,
                title = song?.title ?: c.mediaMetadata.title?.toString().orEmpty(),
                playlist = id?.let { sid -> lib?.playlistOf(sid)?.name }.orEmpty(),
                isPlaying = c.isPlaying,
                shuffle = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
                wave = wave.toList(),
                lyrics = emptyList(),
                lyricIndex = -1,
            )
        }
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val lines = lyricsRepo.load(song?.lyricsId)
            _now.update { if (it.songId == id) it.copy(lyrics = lines) else it }
        }
    }

    private fun tick() {
        val c = controller ?: return
        if (c.currentMediaItem == null) return
        val pos = c.currentPosition
        val dur = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        var waveChanged = false
        if (c.isPlaying && dur > 0 && AudioFx.snapshot(vis, 160)) {
            // Learn the waveform as the song plays.
            val bin = (pos * WaveformStore.BINS / dur).toInt().coerceIn(0, WaveformStore.BINS - 1)
            val lvl = (sqrt(vis.rms) * 1.7f).coerceIn(0.08f, 1f)
            if (lvl > wave[bin] + 0.01f) {
                wave[bin] = lvl
                waveChanged = true
                if (++waveDirty >= 40) { waveSongId?.let { waves.save(it, wave) }; waveDirty = 0 }
            }
        }
        _now.update {
            it.copy(
                positionMs = pos, durationMs = dur, lyricIndex = Lrc.indexAt(it.lyrics, pos),
                wave = if (waveChanged) wave.toList() else it.wave,
            )
        }
    }

    fun play(playlistId: String, index: Int) {
        val c = controller ?: return
        val pl = _ui.value.library?.playlists?.firstOrNull { it.id == playlistId } ?: return
        val items = pl.songs.map { s ->
            MediaItem.Builder()
                .setMediaId(s.id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(pl.name).build())
                .build()
        }
        c.setMediaItems(items, index, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    fun toggleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun setFx(p: FxParams) {
        AudioFx.update(getApplication(), p)
        _fx.value = AudioFx.params
    }

    fun applyPreset(name: String) {
        AudioFx.presets.firstOrNull { it.name == name }?.let { setFx(it.params) }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    override fun onCleared() {
        waveSongId?.let { waves.save(it, wave) }
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }
}
