package dev.wave.player.lyrics

import android.content.Context
import dev.wave.player.drive.DriveApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** timeMs < 0 means the lyrics are not time-synced. */
data class LyricLine(val timeMs: Long, val text: String)

object Lrc {
    private val TAG = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<LyricLine> {
        val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<LyricLine>()
        text.lineSequence().forEach { raw ->
            val tags = TAG.findAll(raw).toList()
            val words = raw.replace(TAG, "").trim()
            if (tags.isEmpty()) {
                if (words.isNotEmpty() && !raw.trimStart().startsWith("[")) plain += LyricLine(-1, words)
            } else {
                tags.forEach { m ->
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].replace(':', '.').toDouble()
                    val ms = (min * 60_000 + sec * 1000).toLong() - offset
                    timed += LyricLine(ms.coerceAtLeast(0), words)
                }
            }
        }
        return if (timed.isNotEmpty()) timed.sortedBy { it.timeMs } else plain
    }

    /** Index of the line being sung at positionMs, or -1. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty() || lines[0].timeMs < 0) return -1
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/** Lyrics are tiny, so every .lrc ever fetched stays on the phone (tier 1). */
class LyricsRepository(context: Context) {
    private val drive = DriveApi(context)
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }

    suspend fun load(lyricsId: String?): List<LyricLine> {
        if (lyricsId == null) return emptyList()
        val f = File(dir, "$lyricsId.lrc")
        val text = withContext(Dispatchers.IO) { if (f.exists()) f.readText() else null }
            ?: runCatching { drive.downloadText(lyricsId) }.getOrNull()
                ?.also { t -> withContext(Dispatchers.IO) { f.writeText(t) } }
            ?: return emptyList()
        return Lrc.parse(text)
    }

    suspend fun prefetch(lyricsIds: List<String>) {
        lyricsIds.forEach { id ->
            if (!File(dir, "$id.lrc").exists()) runCatching { load(id) }
        }
    }
}
