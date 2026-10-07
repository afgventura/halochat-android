package id.co.haloai.halochat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import id.co.haloai.halochat.HaloChatException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads attachments. Every load asks the server for a fresh media URL (they expire after
 * 15 minutes and are never persisted). Decoded thumbnails live in an in-memory LRU keyed by
 * message id; opened files live in the app's cache dir.
 */
internal class MediaRepository(private val widget: HaloChatWidget) {
    private val thumbnails = object : LruCache<String, Bitmap>(cacheKilobytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val filesDir: File get() = File(widget.appContext.cacheDir, "halochat/files")

    fun cachedThumbnail(messageId: String): Bitmap? = thumbnails.get(messageId)

    /** A downsampled (longest side ≈ [maxPx]) bitmap of an image message; cached by message id. */
    suspend fun thumbnail(messageId: String, maxPx: Int): Bitmap? {
        thumbnails.get(messageId)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { decode(download(messageId), maxPx) } ?: return null
        thumbnails.put(messageId, bitmap)
        return bitmap
    }

    /** A larger, uncached bitmap for the full-screen viewer. */
    suspend fun fullImage(messageId: String, maxPx: Int): Bitmap? =
        withContext(Dispatchers.IO) { decode(download(messageId), maxPx) }

    /** Downloads a file message into the cache dir (reused on the next tap) and returns it. */
    suspend fun file(messageId: String, filename: String?): File = withContext(Dispatchers.IO) {
        val dir = File(filesDir, AttachmentRules.safeFilename(messageId, "file"))
        val target = File(dir, AttachmentRules.safeFilename(filename, "attachment"))
        if (target.isFile && target.length() > 0) return@withContext target
        dir.mkdirs()
        val partial = File(dir, target.name + ".part")
        fetch(messageId).use { response ->
            val body = response.body ?: throw IOException("empty body")
            partial.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
        if (!partial.renameTo(target)) throw IOException("could not store ${target.name}")
        target
    }

    fun clear() {
        thumbnails.evictAll()
        runCatching { filesDir.deleteRecursively() }
    }

    private suspend fun download(messageId: String): ByteArray =
        fetch(messageId).use { response ->
            val body = response.body ?: throw IOException("empty body")
            if (body.contentLength() > AttachmentRules.MAX_BYTES * 2) throw IOException("too large")
            body.bytes()
        }

    private suspend fun fetch(messageId: String): Response {
        val client = widget.client ?: throw HaloChatException.Unauthorized()
        val url = client.mediaUrl(messageId)
        val response = widget.http.newCall(Request.Builder().url(url).build()).await()
        if (!response.isSuccessful) {
            response.close()
            throw HaloChatException.Server(response.code)
        }
        return response
    }

    private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val degrees = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees
        }.getOrDefault(0)
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private companion object {
        fun cacheKilobytes(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024)
    }
}

/** Suspends on an OkHttp call; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
