package dev.wave.player.cache

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dev.wave.player.auth.GoogleAuth
import dev.wave.player.drive.DriveApi
import dev.wave.player.library.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * The three cache tiers on the phone (all inside the app's private cache folder):
 *
 *  - Intro tier:  the first seconds (and, for MP4/M4A, the tail index) of every song in
 *                 playlists you open, so any song starts instantly.
 *  - Prefetch:    the whole next song in the queue, downloaded while the current one plays.
 *  - Warm set:    the last [WARM_SIZE] songs you played, kept in full across sessions.
 *
 * Everything else is dropped by [sweep], which runs when playback ends and on every launch.
 * Hard cap: [MAX_BYTES]; the least-recently-used data goes first when it fills up.
 */
object MediaCache {
    private const val TAG = "MediaCache"
    const val MAX_BYTES = 512L * 1024 * 1024
    const val WARM_SIZE = 10
    private const val HEAD_LOSSY = 256L * 1024        // ~10 s at 192 kbps
    private const val HEAD_LOSSLESS = 1024L * 1024    // ~1-2 s of FLAC/WAV, enough to start
    private const val TAIL_BYTES = 128L * 1024
    private const val PREFS = "wave_cache"
    private const val KEY_WARM = "warm"

    lateinit var cache: SimpleCache
        private set
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var introJob: Job? = null
    private var prefetchJob: Job? = null
    @Volatile private var prefetchWriter: CacheWriter? = null

    @Synchronized
    fun init(context: Context) {
        if (::cache.isInitialized) return
        app = context.applicationContext
        cache = SimpleCache(
            File(app.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(app),
        )
    }

    /** Player data path: cache first, then Drive with a fresh bearer token. */
    fun dataSourceFactory(): CacheDataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val authed = ResolvingDataSource.Factory(http) { spec ->
            spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer ${GoogleAuth.tokenBlocking(app)}"))
        }
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(authed)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun headBytes(song: Song) = if (song.isLossless) HEAD_LOSSLESS else HEAD_LOSSY

    private fun spec(id: String, position: Long, length: Long) = DataSpec.Builder()
        .setUri(Uri.parse(DriveApi.mediaUrl(id)))
        .setKey(id)
        .setPosition(position)
        .setLength(length)
        .build()

    private fun cacheRange(id: String, position: Long, length: Long) {
        if (length <= 0 || cache.isCached(id, position, length)) return
        CacheWriter(dataSourceFactory().createDataSource(), spec(id, position, length), null, null).cache()
    }

    /** Intro tier: called when a playlist is opened. Replaces any previous intro job. */
    fun precacheIntros(songs: List<Song>) {
        introJob?.cancel()
        introJob = scope.launch {
            for (s in songs) {
                if (!isActive) break
                runCatching {
                    val size = s.size.takeIf { it > 0 } ?: Long.MAX_VALUE
                    cacheRange(s.id, 0, minOf(headBytes(s), size))
                    if (s.needsTail && s.size > TAIL_BYTES * 2) cacheRange(s.id, s.size - TAIL_BYTES, TAIL_BYTES)
                }.onFailure { Log.w(TAG, "intro ${s.title}: ${it.message}") }
            }
        }
    }

    /** Prefetch tier: buffer the whole next song while this one plays. */
    fun prefetchFull(songId: String) {
        prefetchWriter?.cancel()
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            runCatching {
                val writer = CacheWriter(
                    dataSourceFactory().createDataSource(),
                    spec(songId, 0, C.LENGTH_UNSET.toLong()),
                    null, null,
                )
                prefetchWriter = writer
                writer.cache()
            }.onFailure { Log.d(TAG, "prefetch stopped: ${it.message}") }
        }
    }

    // ---------- Warm set ----------

    fun markPlayed(songId: String) {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = warmIds().toMutableList().apply { remove(songId); add(0, songId) }.take(WARM_SIZE)
        p.edit().putString(KEY_WARM, list.joinToString(",")).apply()
    }

    fun warmIds(): List<String> =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WARM, "")!!.split(',').filter { it.isNotBlank() }

    fun isFullyCached(songId: String, size: Long) = size > 0 && cache.isCached(songId, 0, size)

    // ---------- Session-end cleanup ----------

    fun sweepAsync(context: Context) {
        init(context)
        scope.launch { sweep() }
    }

    /** Keep: warm songs in full, and intro/tail pieces of everything else. Drop the rest. */
    @Synchronized
    fun sweep() {
        prefetchWriter?.cancel()
        val warm = warmIds().toSet()
        var freed = 0L
        for (key in cache.keys.toList()) {
            if (key in warm) continue
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            for (span in cache.getCachedSpans(key).toList()) {
                val end = span.position + span.length
                val isHead = end <= HEAD_LOSSLESS
                val isTail = length > 0 && span.position >= length - TAIL_BYTES
                if (!isHead && !isTail) {
                    runCatching { cache.removeSpan(span); freed += span.length }
                }
            }
        }
        Log.i(TAG, "sweep freed ${freed / 1024} KB, cache now ${cache.cacheSpace / 1024} KB")
    }
}
