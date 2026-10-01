package id.co.haloai.halochat

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
internal data class Envelope<T>(val data: T)

@Serializable
internal data class ErrorEnvelope(val error: String? = null)

internal val haloJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * One token for all concurrent calls, and at most one refresh at a time: N calls that all
 * hit 401 with the same token trigger a single mint (each mint is a server session).
 */
internal class TokenGate(private val provider: HaloChatTokenProvider) {
    private val mutex = Mutex()
    private var current: String? = null

    @Volatile
    internal var onRefreshed: (suspend () -> Unit)? = null

    suspend fun token(): String = mutex.withLock {
        current ?: provider.token(forceRefresh = false).also { current = it }
    }

    /** [rejected] is the token the server refused; if another caller already replaced it, reuse that. */
    suspend fun refresh(rejected: String): String {
        val fresh = mutex.withLock {
            val known = current
            if (known != null && known != rejected) return known
            provider.token(forceRefresh = true).also { current = it }
        }
        onRefreshed?.let { callback -> runCatching { callback() } }
        return fresh
    }

    suspend fun clear() {
        mutex.withLock { current = null }
    }
}

internal val jsonMediaType = "application/json".toMediaType()

/** HTTP plumbing: bearer auth with one refresh on 401, error mapping, JSON coding. */
internal class Transport(
    baseUrl: String,
    internal val http: OkHttpClient,
    tokenProvider: HaloChatTokenProvider,
) {
    internal val tokens: TokenGate = TokenGate(tokenProvider)

    private val apiBase: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = apiBase.newBuilder().addPathSegments("api/client/inApp/v1/$path")
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    suspend fun <T> decode(serializer: KSerializer<T>, request: Request): T {
        val body = perform(request)
        return try {
            haloJson.decodeFromString(Envelope.serializer(serializer), body).data
        } catch (error: Exception) {
            throw HaloChatException.InvalidResponse()
        }
    }

    /** Runs [request] with the current token; on 401 refreshes once and retries. Returns the body. */
    suspend fun perform(request: Request): String {
        val token = tokens.token()
        var response = execute(authorized(request, token))
        if (response.code == 401) {
            response.close()
            response = execute(authorized(request, tokens.refresh(rejected = token)))
        }
        response.use {
            val body = it.body?.string().orEmpty()
            check(it, body)
            return body
        }
    }

    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // Cancelled at the same moment: close it instead of leaking the connection.
                continuation.resume(response) { response.close() }
            }
        })
    }

    fun json(url: HttpUrl, method: String, body: String? = null): Request {
        val requestBody: RequestBody? = body?.toRequestBody(jsonMediaType)
        return Request.Builder().url(url).header("Accept", "application/json").method(method, requestBody).build()
    }

    private fun authorized(request: Request, token: String): Request =
        request.newBuilder().header("Authorization", "Bearer $token").build()

    private fun check(response: Response, body: String) {
        when (response.code) {
            in 200..299 -> return
            401 -> throw HaloChatException.Unauthorized()
            409 -> throw HaloChatException.SendInProgress()
            429 -> throw HaloChatException.RateLimited(
                (response.header("Retry-After")?.toIntOrNull() ?: 1).coerceAtLeast(1),
            )
            in 400..499 -> {
                val code = runCatching { haloJson.decodeFromString(ErrorEnvelope.serializer(), body).error }.getOrNull()
                throw HaloChatException.Rejected(response.code, code)
            }
            else -> throw HaloChatException.Server(response.code)
        }
    }
}
