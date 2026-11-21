package dev.wave.player.library

import android.content.Context
import dev.wave.player.drive.DriveApi
import dev.wave.player.drive.DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Song(
    val id: String,
    val title: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val playlistId: String,
    val lyricsId: String? = null,
) {
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()
    val isLossless: Boolean get() = extension in Formats.LOSSLESS
    /** MP4-family files may keep their index at the end, so we also cache the tail. */
    val needsTail: Boolean get() = extension in Formats.MP4_FAMILY
}

@Serializable
data class Playlist(val id: String, val name: String, val songs: List<Song>)

@Serializable
data class LibraryIndex(val rootId: String, val playlists: List<Playlist>, val updatedAt: Long) {
    val songCount: Int get() = playlists.sumOf { it.songs.size }
    fun song(id: String): Song? = playlists.firstNotNullOfOrNull { p -> p.songs.firstOrNull { it.id == id } }
    fun playlistOf(songId: String): Playlist? = playlists.firstOrNull { p -> p.songs.any { it.id == songId } }
}

object Formats {
    /** Everything Media3 can decode on Android without extra extensions. */
    val AUDIO = setOf(
        "mp3", "m4a", "aac", "mp4", "3gp", "flac", "wav", "wave", "ogg", "oga", "opus",
        "amr", "awb", "mka", "webm", "mid", "midi", "ts", "adts",
    )
    val LOSSLESS = setOf("flac", "wav", "wave")
    val MP4_FAMILY = setOf("m4a", "mp4", "3gp")
    val LYRICS = setOf("lrc")

    fun isAudio(f: DriveFile): Boolean {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO || f.mimeType.startsWith("audio/")
    }
}

/**
 * Tier 1 (warm) index: the whole library structure is saved on the phone as one small
 * JSON file, so playlists open instantly and only refresh from Drive in the background.
 *
 * Layout in Drive:
 *   Wave Music/            -> playlist "Library"
 *     song.mp3, song.lrc
 *     Road Trip/           -> playlist "Road Trip"
 *       other.flac, other.lrc
 */
class LibraryRepository(context: Context) {
    private val drive = DriveApi(context)
    private val file = File(context.filesDir, "library.json")

    fun cached(): LibraryIndex? = runCatching {
        DriveApi.json.decodeFromString<LibraryIndex>(file.readText())
    }.getOrNull()

    suspend fun refresh(): LibraryIndex = coroutineScope {
        val rootId = drive.ensureRootFolder()
        val rootChildren = drive.children(rootId)
        val folders = rootChildren.filter { it.mimeType == DriveApi.FOLDER_MIME }
        val sub = folders.map { f -> async { f to drive.children(f.id) } }.awaitAll()

        val playlists = buildList {
            add(toPlaylist(rootId, "Library", rootChildren))
            sub.forEach { (folder, kids) -> add(toPlaylist(folder.id, folder.name, kids)) }
        }
        val index = LibraryIndex(rootId, playlists, System.currentTimeMillis())
        withContext(Dispatchers.IO) { file.writeText(DriveApi.json.encodeToString(LibraryIndex.serializer(), index)) }
        index
    }

    private fun toPlaylist(id: String, name: String, files: List<DriveFile>): Playlist {
        val lyrics = files
            .filter { it.name.substringAfterLast('.', "").lowercase() in Formats.LYRICS }
            .associateBy { it.name.substringBeforeLast('.').lowercase() }
        val songs = files.filter { it.mimeType != DriveApi.FOLDER_MIME && Formats.isAudio(it) }.map { f ->
            val base = f.name.substringBeforeLast('.')
            Song(
                id = f.id,
                title = base,
                fileName = f.name,
                mimeType = f.mimeType,
                size = f.size?.toLongOrNull() ?: 0L,
                playlistId = id,
                lyricsId = lyrics[base.lowercase()]?.id,
            )
        }
        return Playlist(id, name, songs)
    }

    suspend fun createPlaylist(rootId: String, name: String) = drive.createFolder(name, rootId)
}
