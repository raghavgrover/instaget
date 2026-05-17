package com.example.fetchin_instagrammediadownloader2026.worker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.fetchin_instagrammediadownloader2026.data.db.AppDatabase
import com.example.fetchin_instagrammediadownloader2026.data.db.MediaItem
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_MEDIA_URL = "mediaUrl"
        const val KEY_FILENAME = "filename"
        const val KEY_PROGRESS = "progress"
        const val KEY_SAVED_URI = "savedUri"
        const val KEY_SHORTCODE = "shortcode"
        const val KEY_ORIGINAL_URL = "originalUrl"
        const val KEY_MEDIA_TYPE = "mediaType"
        const val KEY_THUMBNAIL_URL = "thumbnailUrl"
        private const val TAG = "DownloadWorker"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("Referer", "https://www.instagram.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build()
            chain.proceed(req)
        }
        .build()

    override suspend fun doWork(): Result {
        val mediaUrl = inputData.getString(KEY_MEDIA_URL)
            ?: return Result.failure(workDataOf("error" to "No URL provided"))
        val filename = inputData.getString(KEY_FILENAME) ?: "InstaGet_${System.currentTimeMillis()}"

        return try {
            setProgress(workDataOf(KEY_PROGRESS to 0))

            val request = Request.Builder().url(mediaUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: HTTP ${response.code}")
                return Result.failure(workDataOf("error" to "HTTP ${response.code}"))
            }

            val body = response.body ?: return Result.failure(workDataOf("error" to "Empty body"))
            val contentLength = body.contentLength()
            val isVideo = filename.endsWith(".mp4", ignoreCase = true)

            val savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(body.byteStream(), filename, isVideo, contentLength)
            } else {
                saveToFile(body.byteStream(), filename, isVideo, contentLength)
            } ?: return Result.failure(workDataOf("error" to "Failed to save file"))

            Log.d(TAG, "Saved to: $savedUri")
            setProgress(workDataOf(KEY_PROGRESS to 100))

            val shortcode = inputData.getString(KEY_SHORTCODE) ?: ""
            val originalUrl = inputData.getString(KEY_ORIGINAL_URL) ?: ""
            val mediaType = inputData.getString(KEY_MEDIA_TYPE) ?: if (isVideo) "VIDEO" else "IMAGE"
            val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL) ?: ""
            val uriString = savedUri.toString()

            AppDatabase.getInstance(applicationContext).mediaDao().insert(
                MediaItem(
                    shortcode = shortcode,
                    originalUrl = originalUrl,
                    localPath = uriString,
                    mediaType = mediaType.uppercase(),
                    thumbnailPath = uriString,
                    fileName = filename,
                    downloadedAt = System.currentTimeMillis()
                )
            )

            Result.success(workDataOf(KEY_SAVED_URI to uriString))
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
        }
    }

    private suspend fun saveViaMediaStore(
        inputStream: java.io.InputStream,
        filename: String,
        isVideo: Boolean,
        contentLength: Long
    ): Uri? {
        val mimeType = if (isVideo) "video/mp4" else "image/jpeg"
        val relativePath = "DCIM/InstaGet"
        val collection = if (isVideo)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(collection, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var lastReported = -1
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    downloaded += read
                    if (contentLength > 0) {
                        val pct = (downloaded * 100 / contentLength).toInt()
                        if (pct != lastReported) {
                            lastReported = pct
                            setProgress(workDataOf(KEY_PROGRESS to pct))
                        }
                    }
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private suspend fun saveToFile(
        inputStream: java.io.InputStream,
        filename: String,
        isVideo: Boolean,
        contentLength: Long
    ): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "InstaGet"
        )
        dir.mkdirs()
        val file = File(dir, filename)

        FileOutputStream(file).use { out ->
            val buffer = ByteArray(8192)
            var downloaded = 0L
            var lastReported = -1
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
                downloaded += read
                if (contentLength > 0) {
                    val pct = (downloaded * 100 / contentLength).toInt()
                    if (pct != lastReported) {
                        lastReported = pct
                        setProgress(workDataOf(KEY_PROGRESS to pct))
                    }
                }
            }
        }

        // Notify MediaStore
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(
                MediaStore.MediaColumns.MIME_TYPE,
                if (isVideo) "video/mp4" else "image/jpeg"
            )
        }
        val collection = if (isVideo)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return applicationContext.contentResolver.insert(collection, values)
            ?: Uri.fromFile(file)
    }
}
