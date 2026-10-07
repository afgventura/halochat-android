package id.co.haloai.halochat.ui

import id.co.haloai.halochat.HaloChatException
import java.util.Locale

/** Why an attachment cannot be sent. Shared by client-side validation and server rejections. */
internal enum class AttachmentProblem { TOO_LARGE, UNSUPPORTED_TYPE }

/**
 * The server's attachment rules (`docs/integrations/halochat-in-app-api.md`), checked before
 * uploading so the user gets an immediate, specific message. Pure Kotlin: unit-tested on the JVM.
 */
internal object AttachmentRules {
    const val MAX_BYTES: Long = 25L * 1024 * 1024

    val ALLOWED_MIME_TYPES: Set<String> = setOf(
        "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic",
        "video/mp4", "video/quicktime",
        "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/ogg",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "text/plain",
    )

    /** The MIME types offered to the document picker. */
    val PICKER_MIME_TYPES: Array<String> = ALLOWED_MIME_TYPES.toTypedArray()

    /** Extension → MIME for every allowed type, so a missing/generic reported type still resolves. */
    private val BY_EXTENSION: Map<String, String> = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg",
        "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif",
        "heic" to "image/heic", "heif" to "image/heic",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "qt" to "video/quicktime",
        "mp3" to "audio/mpeg", "m4a" to "audio/x-m4a", "aac" to "audio/aac", "ogg" to "audio/ogg", "oga" to "audio/ogg",
        "pdf" to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "txt" to "text/plain",
    )

    /** Aliases some pickers report for an allowed type. */
    private val ALIASES: Map<String, String> = mapOf(
        "image/jpg" to "image/jpeg",
        "image/pjpeg" to "image/jpeg",
        "image/heif" to "image/heic",
        "audio/mp3" to "audio/mpeg",
        "audio/m4a" to "audio/x-m4a",
        "audio/x-aac" to "audio/aac",
        "application/ogg" to "audio/ogg",
    )

    /** Reported types that say nothing about the content; fall back to the extension. */
    private val GENERIC: Set<String> = setOf("application/octet-stream", "binary/octet-stream", "*/*")

    fun extensionOf(filename: String?): String? =
        filename?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    /**
     * Resolves the MIME type to upload: the picker / content-resolver type when it is specific,
     * otherwise the file extension (built-in table first, then [extensionLookup], e.g. MimeTypeMap).
     * Returns null when nothing is known.
     */
    fun resolveMime(reported: String?, filename: String?, extensionLookup: (String) -> String? = { null }): String? {
        val normalized = reported?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val canonical = normalized?.let { ALIASES[it] ?: it }
        if (canonical != null && canonical !in GENERIC) return canonical
        val ext = extensionOf(filename) ?: return canonical
        val byExt = BY_EXTENSION[ext] ?: extensionLookup(ext)?.lowercase(Locale.ROOT)?.let { ALIASES[it] ?: it }
        return byExt ?: canonical
    }

    /** Null when the file may be sent. [sizeBytes] < 0 means unknown (checked again after reading). */
    fun validate(sizeBytes: Long, mimeType: String?): AttachmentProblem? = when {
        sizeBytes > MAX_BYTES -> AttachmentProblem.TOO_LARGE
        mimeType == null || mimeType !in ALLOWED_MIME_TYPES -> AttachmentProblem.UNSUPPORTED_TYPE
        else -> null
    }

    /** Maps the server's attachment error codes onto the same problems. */
    fun fromServerCode(code: String?): AttachmentProblem? = when (code) {
        "upload_too_large" -> AttachmentProblem.TOO_LARGE
        "upload_type" -> AttachmentProblem.UNSUPPORTED_TYPE
        else -> null
    }

    /** A filename safe to write into the cache directory. */
    fun safeFilename(raw: String?, fallback: String): String {
        val cleaned = raw.orEmpty().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.')
        return cleaned.take(120).ifEmpty { fallback }
    }
}

/** Why a send failed, for the failed-bubble message. */
internal enum class SendFailure { NETWORK, TOO_LARGE, UNSUPPORTED_TYPE, RATE_LIMITED, SESSION_EXPIRED, OTHER }

internal object SendFailures {
    fun classify(error: Throwable): SendFailure = when (error) {
        is HaloChatException.Unauthorized -> SendFailure.SESSION_EXPIRED
        is HaloChatException.RateLimited -> SendFailure.RATE_LIMITED
        is HaloChatException.Rejected -> when (AttachmentRules.fromServerCode(error.code)) {
            AttachmentProblem.TOO_LARGE -> SendFailure.TOO_LARGE
            AttachmentProblem.UNSUPPORTED_TYPE -> SendFailure.UNSUPPORTED_TYPE
            null -> SendFailure.OTHER
        }
        is java.io.IOException -> SendFailure.NETWORK
        else -> SendFailure.OTHER
    }
}
