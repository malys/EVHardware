package com.evsuite.hardware.diag

import com.google.gson.JsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64

/**
 * Posts one diagnostic artifact to an inbox the driver runs at home, instead of a USB stick.
 *
 * The inbox is the driver's own server (a Node-RED flow on the house network, for one): the
 * app holds no account and no key, and nothing is sent unless the driver taps Send and
 * confirms the address. HTTPS only, no redirect followed, no credentials or query in the URL,
 * so a typo cannot send a car's report somewhere else in clear.
 *
 * The body is one JSON object, the same for every app, so one script on the desk unpacks them
 * all: `{"evsuite":1,"app","version","name","createdAtMs","contentBase64"}`. The content is the
 * artifact exactly as it would land on the stick — a zip, or a text report — base64-encoded.
 *
 * No Android dependency: [poster] is injectable, so a test checks the wire without a network.
 */
object DiagnosticInbox {

    const val FORMAT_VERSION = 1

    /** Above what a bundle is allowed to be on the stick, with base64's third on top. */
    const val MAX_CONTENT_BYTES = 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    data class Artifact(
        val app: String,
        val version: String,
        val name: String,
        val createdAtMs: Long,
        val content: ByteArray,
    )

    sealed interface Outcome {
        data class Sent(val status: Int) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** Why [url] is not an inbox address, or null when it is one. */
    fun refuse(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "not a URL"
        if (!uri.isAbsolute) return "not an absolute URL"
        if (!uri.scheme.equals("https", ignoreCase = true)) return "must be https"
        if (uri.host.isNullOrBlank()) return "no host"
        if (uri.userInfo != null) return "must not carry credentials"
        if (uri.query != null || uri.fragment != null) return "must not carry a query"
        return null
    }

    internal fun body(artifact: Artifact): String = JsonObject().apply {
        addProperty("evsuite", FORMAT_VERSION)
        addProperty("app", artifact.app)
        addProperty("version", artifact.version)
        addProperty("name", artifact.name)
        addProperty("createdAtMs", artifact.createdAtMs)
        addProperty("contentBase64", Base64.getEncoder().encodeToString(artifact.content))
    }.toString()

    fun send(
        url: String,
        artifact: Artifact,
        poster: (String, String) -> Int = ::post,
    ): Outcome {
        refuse(url)?.let { return Outcome.Failed(it) }
        if (artifact.content.size > MAX_CONTENT_BYTES) {
            return Outcome.Failed("artifact is ${artifact.content.size} bytes, over $MAX_CONTENT_BYTES")
        }
        return try {
            val status = poster(url.trim(), body(artifact))
            if (status in 200..299) Outcome.Sent(status) else Outcome.Failed("HTTP $status")
        } catch (e: Exception) {
            Outcome.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    private fun post(url: String, body: String): Int {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(bytes.size)
        }
        return try {
            connection.outputStream.use { it.write(bytes) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
