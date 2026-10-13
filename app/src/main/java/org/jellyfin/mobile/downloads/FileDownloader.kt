package org.jellyfin.mobile.downloads

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import java.io.IOException
import kotlin.coroutines.resumeWithException

class FileDownloader(
    private val okHttpClient: OkHttpClient,
) {
    fun interface ProgressCallback {
        suspend fun onProgress(downloaded: Long, total: Long)

        companion object Empty : ProgressCallback {
            override suspend fun onProgress(downloaded: Long, total: Long) = Unit
        }
    }

    private suspend fun download(
        api: ApiClient,
        from: Uri,
        rangeStart: Long? = null,
    ): Response {
        val authorizationHeader = AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = api.accessToken,
        )

        val request = Request.Builder().apply {
            url(from.toString())

            header("Authorization", authorizationHeader)
            rangeStart?.let { header("Range", "bytes=$rangeStart-") }
        }.build()

        val response = okHttpClient.newCall(request).await()

        // 416 (Requested Range Not Satisfiable) can happen when we've already fully downloaded the file
        if (response.code == 416 && rangeStart != null && rangeStart >= response.getContentRange().total) return response

        // The server refuses (no permission, the file cannot be made): trying again does not help
        val refused = response.code in 400..499 && response.code !in TRANSIENT_CODES
        // The server or its proxy is down for a moment (502-504): tried again later, as without a connection
        val serverError = response.code >= 500 && response.code !in UNAVAILABLE_CODES
        if (refused || (rangeStart == null && serverError)) {
            val message = response.body?.string()?.take(MAX_ERROR_LENGTH).orEmpty()
            response.close()
            throw DownloadRefusedException("Server refused the download (${response.code}): $message")
        }

        // Throw for other unsuccessful responses
        if (!response.isSuccessful) throw IOException("Unexpected response $response")

        return response
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { cause, response, _ ->
                        response.close()
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            },
        )

        continuation.invokeOnCancellation {
            cancel()
        }
    }

    private fun Response.getContentRange() = when (code) {
        // A file made while downloading has no length
        200 -> header("Content-Length")?.let(ContentRange::fromContentLengthHeader) ?: ContentRange(0, -1, -1)
        206, 416 -> requireNotNull(header("Content-Range")).let(ContentRange::fromContentRangeHeader)
        else -> error("Invalid response code $code")
    }

    private suspend fun save(
        response: Response,
        to: ParcelFileDescriptor,
        progressCallback: ProgressCallback,
        estimatedSize: Long?,
    ) = withContext(Dispatchers.IO) {
        val contentRange = response.getContentRange()
        // Unknown length: the estimate, never below what already came
        fun total(read: Long) = if (contentRange.total > 0) contentRange.total else estimatedSize?.coerceAtLeast(read) ?: -1L

        val output = ParcelFileDescriptor.AutoCloseOutputStream(to)
        output.channel.position(contentRange.start)

        val inputStream = response.body?.byteStream() ?: error("Response does not contain a body")
        inputStream.use { inputStream ->
            output.use { outputFile ->
                val buffer = ByteArray(10240)
                var totalRead = contentRange.start
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    coroutineContext.ensureActive()

                    outputFile.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    progressCallback.onProgress(totalRead, total(totalRead))
                }
            }
        }
    }

    suspend fun downloadAndSave(
        api: ApiClient,
        from: Uri,
        to: ParcelFileDescriptor,
        progressCallback: ProgressCallback = ProgressCallback.Empty,
        resumable: Boolean = true,
        estimatedSize: Long? = null,
    ) {
        // What cannot be continued starts over
        if (!resumable) Os.ftruncate(to.fileDescriptor, 0)
        val rangeStart = if (resumable) to.statSize else null
        val response = download(api, from, rangeStart)
        save(response, to, progressCallback, estimatedSize)
    }
}

/** The server answered, but will not give the file: the download fails instead of trying again. */
class DownloadRefusedException(message: String) : Exception(message)

private const val MAX_ERROR_LENGTH = 500

/** Request timeout, range not satisfiable (handled as before), too many requests. */
private val TRANSIENT_CODES = setOf(408, 416, 429)

/** Bad gateway, unavailable, gateway timeout. */
private val UNAVAILABLE_CODES = setOf(502, 503, 504)
