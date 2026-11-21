package dev.wave.player.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.wave.player.auth.NeedsSignInException
import dev.wave.player.drive.ChunkResult
import dev.wave.player.drive.DriveApi
import dev.wave.player.drive.UploadSessionExpired
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Uploads one audio file of any size into a playlist folder using Drive's resumable
 * upload: 8 MB chunks streamed from disk (never loaded whole into memory), resumes from
 * the exact byte the server has after a network drop, app kill or reboot.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val TAG = "upload"
        const val KEY_URI = "uri"
        const val KEY_FOLDER = "folder"
        const val KEY_NAME = "name"
        const val KEY_MIME = "mime"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val CHUNK = 8 * 1024 * 1024 // must be a multiple of 256 KB
        private const val CHANNEL = "uploads"
        private const val PREFS = "wave_uploads"

        fun enqueue(context: Context, uri: Uri, folderId: String) {
            val cr = context.contentResolver
            runCatching {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "song-${System.currentTimeMillis()}"
            val mime = cr.getType(uri) ?: "application/octet-stream"

            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_URI to uri.toString(), KEY_FOLDER to folderId, KEY_NAME to name, KEY_MIME to mime))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("upload:$uri", ExistingWorkPolicy.KEEP, request)
        }
    }

    private val drive = DriveApi(ctx)
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val name get() = inputData.getString(KEY_NAME) ?: "song"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = Uri.parse(inputData.getString(KEY_URI) ?: return@withContext Result.failure())
        val folder = inputData.getString(KEY_FOLDER) ?: return@withContext Result.failure()
        val mime = inputData.getString(KEY_MIME) ?: "application/octet-stream"
        val sessionKey = "session:$uri"

        runCatching { setForeground(foregroundInfo(0)) }

        try {
            val total = sizeOf(uri)
            val saved = prefs.getString(sessionKey, null)
            var offset = 0L
            val session: String

            if (saved != null) {
                session = saved
                when (val r = drive.queryUpload(session, total)) {
                    ChunkResult.Done -> { prefs.edit().remove(sessionKey).apply(); return@withContext done() }
                    is ChunkResult.Next -> offset = r.offset
                }
            } else {
                session = drive.startResumable(name, folder, mime, total)
                prefs.edit().putString(sessionKey, session).apply()
            }

            applicationContext.contentResolver.openInputStream(uri).use { input ->
                input ?: throw IOException("Cannot open file")
                skipFully(input, offset)
                val buf = ByteArray(CHUNK)
                while (true) {
                    val n = readFully(input, buf)
                    val isLast = n < CHUNK || (total != null && offset + n >= total)
                    val result = drive.uploadChunk(session, buf, n, offset, if (isLast) offset + n else total)
                    when (result) {
                        ChunkResult.Done -> break
                        is ChunkResult.Next -> {
                            // Server kept fewer bytes than sent: restart from its offset on the next attempt.
                            if (result.offset != offset + n) throw IOException("Partial chunk, resuming")
                            offset = result.offset
                        }
                    }
                    val pct = if (total != null && total > 0) (offset * 100 / total).toInt() else -1
                    setProgress(workDataOf(KEY_PROGRESS to pct, KEY_NAME to name))
                    runCatching { setForeground(foregroundInfo(pct)) }
                    if (isLast) break
                }
            }
            prefs.edit().remove(sessionKey).apply()
            done()
        } catch (e: NeedsSignInException) {
            Result.failure(workDataOf(KEY_ERROR to "Open PlayitLoud and sign in again", KEY_NAME to name))
        } catch (e: UploadSessionExpired) {
            prefs.edit().remove(sessionKey).apply()
            Result.retry()
        } catch (e: IOException) {
            if (runAttemptCount < 10) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Network error"), KEY_NAME to name))
        } catch (e: SecurityException) {
            Result.failure(workDataOf(KEY_ERROR to "Lost access to the file", KEY_NAME to name))
        }
    }

    private fun done() = Result.success(workDataOf(KEY_PROGRESS to 100, KEY_NAME to name))

    private fun sizeOf(uri: Uri): Long? =
        applicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { s -> s > 0 } else null
        }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var left = bytes
        val scratch = ByteArray(64 * 1024)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) { left -= skipped; continue }
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n < 0) throw IOException("File shorter than expected")
            left -= n
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0)

    private fun foregroundInfo(pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading $name")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .build()
        val notificationId = id.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, n)
        }
    }
}
