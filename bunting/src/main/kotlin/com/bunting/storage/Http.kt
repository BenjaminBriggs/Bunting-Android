package com.bunting.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** A single HTTP response. [headers] keys are lowercased for case-insensitive lookup. */
data class HttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** Minimal HTTP GET over HttpURLConnection, confined to Dispatchers.IO. */
internal object Http {
    suspend fun get(url: String, requestHeaders: Map<String, String> = emptyMap()): HttpResponse =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.useCaches = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                for ((k, v) in requestHeaders) conn.setRequestProperty(k, v)
                val code = conn.responseCode
                // Never touch inputStream on a non-2xx (304 has no body and would throw).
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { it.readBytes() } ?: ByteArray(0)
                val headers = HashMap<String, String>()
                for ((k, v) in conn.headerFields) {
                    if (k != null && v.isNotEmpty()) headers[k.lowercase()] = v.last()
                }
                HttpResponse(code, body, headers)
            } finally {
                conn.disconnect()
            }
        }
}
