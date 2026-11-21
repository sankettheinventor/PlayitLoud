package dev.wave.player.drive

import android.content.Context
import dev.wave.player.auth.GoogleAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: String? = null,
    val modifiedTime: String? = null,
)

@Serializable
private data class IdOnly(val id: String)

@Serializable
private data class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

class UploadSessionExpired : IOException("Upload session expired")

sealed interface ChunkResult {
    data object Done : ChunkResult
    data class Next(val offset: Long) : ChunkResult
}

/** Minimal Drive v3 REST client: folders, listing, small text files, resumable uploads. */
class DriveApi(context: Context) {
    private val app = context.applicationContext

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = GoogleAuth.tokenBlocking(app)
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
        }
        .authenticator(Authenticator { _, response ->
            // One retry with a fresh token on 401.
            if (response.priorResponse != null) return@Authenticator null
            GoogleAuth.invalidate()
            val token = GoogleAuth.tokenBlocking(app)
            response.request.newBuilder().header("Authorization", "Bearer $token").build()
        })
        .build()

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val ROOT_FOLDER_NAME = "PlayitLoud Music"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON_TYPE = "application/json; charset=UTF-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true }

        fun mediaUrl(fileId: String) = "$API/files/$fileId?alt=media"
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun list(query: String): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("orderBy", "name")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Drive list failed: HTTP ${r.code}")
                val body = json.decodeFromString<FileList>(r.body!!.string())
                out += body.files
                page = body.nextPageToken
            }
        } while (page != null)
        return out
    }

    suspend fun children(folderId: String): List<DriveFile> =
        io { list("'$folderId' in parents and trashed=false") }

    /** Finds or creates "Wave Music" in My Drive. Every subfolder is a playlist. */
    suspend fun ensureRootFolder(): String = io {
        val q = "name='$ROOT_FOLDER_NAME' and mimeType='$FOLDER_MIME' and 'root' in parents and trashed=false"
        list(q).firstOrNull()?.id ?: createFolderBlocking(ROOT_FOLDER_NAME, "root")
    }

    suspend fun createFolder(name: String, parentId: String): String = io { createFolderBlocking(name, parentId) }

    private fun createFolderBlocking(name: String, parentId: String): String {
        val meta = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            put("parents", buildJsonArray { add(parentId) })
        }.toString()
        val req = Request.Builder().url("$API/files?fields=id")
            .post(meta.toRequestBody(JSON_TYPE)).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Create folder failed: HTTP ${r.code}")
            return json.decodeFromString<IdOnly>(r.body!!.string()).id
        }
    }

    /** Small text files only (lyrics, catalogs). */
    suspend fun downloadText(fileId: String): String = io {
        client.newCall(Request.Builder().url(mediaUrl(fileId)).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed: HTTP ${r.code}")
            r.body!!.string()
        }
    }

    // ---------- Resumable upload: works for files of any size ----------

    /** Starts a session and returns its URL. totalBytes may be null if unknown. */
    fun startResumable(name: String, parentId: String, mime: String, totalBytes: Long?): String {
        val meta = buildJsonObject {
            put("name", name)
            put("parents", buildJsonArray { add(parentId) })
        }.toString()
        val req = Request.Builder().url("$UPLOAD?uploadType=resumable&fields=id")
            .header("X-Upload-Content-Type", mime)
            .apply { totalBytes?.let { header("X-Upload-Content-Length", it.toString()) } }
            .post(meta.toRequestBody(JSON_TYPE))
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Upload start failed: HTTP ${r.code}")
            return r.header("Location") ?: throw IOException("No upload session URL")
        }
    }

    /**
     * Sends bytes [start, start+len). Pass total on the final chunk (and whenever known).
     * len == 0 finalises an upload whose size was unknown until EOF.
     */
    fun uploadChunk(session: String, buf: ByteArray, len: Int, start: Long, total: Long?): ChunkResult {
        val range = if (len == 0) "bytes */${total ?: "*"}"
        else "bytes $start-${start + len - 1}/${total ?: "*"}"
        val req = Request.Builder().url(session)
            .header("Content-Range", range)
            .put(buf.toRequestBody(null, 0, len))
            .build()
        return client.newCall(req).execute().use { r -> parseUploadResponse(r.code, r.header("Range")) }
    }

    /** Asks the server how much it already has (after a crash or network drop). */
    fun queryUpload(session: String, total: Long?): ChunkResult {
        val req = Request.Builder().url(session)
            .header("Content-Range", "bytes */${total ?: "*"}")
            .put(ByteArray(0).toRequestBody(null, 0, 0))
            .build()
        return client.newCall(req).execute().use { r -> parseUploadResponse(r.code, r.header("Range")) }
    }

    private fun parseUploadResponse(code: Int, range: String?): ChunkResult = when (code) {
        200, 201 -> ChunkResult.Done
        308 -> ChunkResult.Next(range?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: 0L)
        404, 410 -> throw UploadSessionExpired()
        else -> throw IOException("Upload chunk failed: HTTP $code")
    }
}
