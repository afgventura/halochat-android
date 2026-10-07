package id.co.haloai.halochat.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream

/** Outcome of reading a picked file. */
internal sealed class ReadResult {
    class Ok(val attachment: PendingAttachment) : ReadResult()
    class Rejected(val problem: AttachmentProblem) : ReadResult()
    object Failed : ReadResult()
}

/** Reads a picked / captured file through the content resolver, validating before and after reading. */
internal object AttachmentReader {
    fun read(resolver: ContentResolver, uri: Uri, nameOverride: String?, mimeOverride: String?): ReadResult = try {
        var name: String? = nameOverride
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (name == null && nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val reported = mimeOverride ?: resolver.getType(uri)
        val filenameGuess = name ?: uri.lastPathSegment
        val mime = AttachmentRules.resolveMime(reported, filenameGuess) { ext ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
        val early = AttachmentRules.validate(size, mime)
        if (early != null || mime == null) {
            ReadResult.Rejected(early ?: AttachmentProblem.UNSUPPORTED_TYPE)
        } else {
            val bytes = readCapped(resolver, uri)
            if (bytes == null) {
                ReadResult.Rejected(AttachmentProblem.TOO_LARGE)
            } else {
                val filename = AttachmentRules.safeFilename(filenameGuess, "attachment" + defaultExtension(mime))
                ReadResult.Ok(PendingAttachment(bytes, withExtension(filename, mime), mime))
            }
        }
    } catch (_: Exception) {
        ReadResult.Failed
    }

    /** Null when the stream is larger than the limit (the reported size can be missing or wrong). */
    private fun readCapped(resolver: ContentResolver, uri: Uri): ByteArray? {
        val input = resolver.openInputStream(uri) ?: throw java.io.IOException("cannot open $uri")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > AttachmentRules.MAX_BYTES) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun defaultExtension(mime: String): String =
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { ".$it" } ?: ""

    /** The server and the agent's viewer key off the filename too; make sure it has an extension. */
    private fun withExtension(filename: String, mime: String): String =
        if (AttachmentRules.extensionOf(filename) != null) filename else filename + defaultExtension(mime)
}
