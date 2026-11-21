package dev.wave.player.playback

import android.content.Context
import java.io.File
import java.nio.ByteBuffer

/**
 * Each song's waveform is learnt while it plays (loudness per slice of the song) and saved
 * on the phone, a few hundred bytes per song. Replays show the full shape instantly.
 */
class WaveformStore(context: Context) {
    companion object { const val BINS = 72 }

    private val dir = File(context.filesDir, "waves").apply { mkdirs() }

    fun load(songId: String): FloatArray {
        val f = File(dir, "$songId.bin")
        if (!f.exists()) return FloatArray(BINS)
        return runCatching {
            val buf = ByteBuffer.wrap(f.readBytes())
            FloatArray(BINS) { if (buf.remaining() >= 4) buf.float else 0f }
        }.getOrElse { FloatArray(BINS) }
    }

    fun save(songId: String, levels: FloatArray) {
        runCatching {
            val buf = ByteBuffer.allocate(BINS * 4)
            levels.forEach { buf.putFloat(it) }
            File(dir, "$songId.bin").writeBytes(buf.array())
        }
    }
}
