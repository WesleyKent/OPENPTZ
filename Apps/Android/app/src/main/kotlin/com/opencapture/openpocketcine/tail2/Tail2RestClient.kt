package com.opencapture.openpocketcine.tail2

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Official OBSBOT Tail 2 REST v1 client. Media management is intentionally absent. */
class Tail2RestClient(host: String) {
    val host: String = normalizeHost(host)
    private val baseUrl = "http://${this.host}:80/camera/sdk"
    private val http =
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    suspend fun get(path: String): JSONObject = execute("GET", path, null)

    suspend fun put(path: String, body: JSONObject): JSONObject = execute("PUT", path, body)

    suspend fun post(path: String, body: JSONObject? = null): JSONObject = execute("POST", path, body)

    suspend fun setPreviewMode(mode: Tail2PreviewMode) {
        if (mode == Tail2PreviewMode.UVC) {
            put("/usb/mode", JSONObject().put("mode", "uvc"))
        } else {
            put("/ndi-rtsp-srt/control", JSONObject().put("control", mode.apiValue))
        }
    }

    suspend fun setRecording(enabled: Boolean): JSONObject {
        val expected = if (enabled) "on" else "off"
        put("/record/control", JSONObject().put("recording", expected))
        // Starting an SD-card recording can take materially longer than a
        // settings PUT. Do not report a false failure after only 400 ms.
        return try {
            awaitState(
                "/record/control",
                "recording",
                setOf(expected),
                "recording",
                attempts = 12,
                intervalMs = 250,
            )
        } catch (error: IOException) {
            if (enabled) {
                throw IOException(
                    "Tail 2 kept reporting recording=off after 3 s; check that a writable " +
                        "U3 microSD card is inserted, formatted, and has free space",
                    error,
                )
            }
            throw error
        }
    }

    suspend fun setAiWorkMode(request: JSONObject): JSONObject {
        val requestedMode = request.getString("mode")
        put("/ai/workmode", request)
        return awaitState(
            "/ai/workmode",
            "mode",
            Tail2AiProtocol.acceptedModesFor(requestedMode),
            "AI tracking",
            attempts = 8,
            intervalMs = 125,
        )
    }

    /**
     * OPENPTZ is a monitor, not the recorder. Prefer Tail 2's sub stream so a
     * phone does not have to decode the 4K High@L5.1 recording stream and copy
     * it through an ImageReader/Vulkan pipeline. Camera recording settings are
     * untouched. Prefer the URL whose host matches the REST connection because
     * the response can contain both wired and wireless interfaces.
     */
    suspend fun monitorRtspUrl(): String {
        val payload = get("/ndi-rtsp-srt/rtspurl")
        return selectMonitorRtspUrl(payload, host)
    }

    private suspend fun execute(method: String, path: String, json: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val body = json?.toString()?.toRequestBody(JSON)
            val request =
                Request.Builder()
                    .url(baseUrl + path)
                    .method(method, if (method == "GET") null else body ?: EMPTY_BODY)
                    .header("Accept", "application/json")
                    .build()
            http.newCall(request).execute().use { response ->
                val raw = response.body.string()
                if (!response.isSuccessful) {
                    val reason = raw.take(180).ifBlank { response.message }
                    val sent = json?.toString()?.take(220)?.let { " sent=$it" }.orEmpty()
                    throw IOException("Tail 2 REST $method $path: HTTP ${response.code} $reason$sent")
                }
                if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            }
        }

    private suspend fun awaitState(
        path: String,
        field: String,
        accepted: Set<String>,
        label: String,
        attempts: Int,
        intervalMs: Long,
    ): JSONObject {
        var observed = JSONObject()
        repeat(attempts) {
            delay(intervalMs)
            observed = get(path)
            if (observed.optString(field) in accepted) return observed
        }
        val value = observed.optString(field).ifBlank { "missing $field" }
        throw IOException("Tail 2 $label command was not applied (camera reports $value)")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(JSON)

        fun normalizeHost(raw: String): String =
            raw.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .substringBefore(':')
                .ifBlank { "192.168.88.10" }

        fun selectMonitorRtspUrl(payload: JSONObject, host: String): String {
            val networks =
                listOfNotNull(
                    payload.optJSONObject("wiredNetwork"),
                    payload.optJSONObject("wirelessNetwork"),
                )
            val sub = networks.map { it.optString("subStreamUrl").trim() }.filter(String::isNotEmpty)
            val main = networks.map { it.optString("mainStreamUrl").trim() }.filter(String::isNotEmpty)
            return sub.firstOrNull { rtspHost(it).equals(host, ignoreCase = true) }
                ?: sub.firstOrNull()
                ?: main.firstOrNull { rtspHost(it).equals(host, ignoreCase = true) }
                ?: main.firstOrNull()
                ?: "rtsp://$host/stream2"
        }

        private fun rtspHost(url: String): String =
            url.substringAfter("://", "")
                .substringBefore('/')
                .substringBefore(':')
    }
}

enum class Tail2PreviewMode(val apiValue: String, val label: String) {
    NDI("ndi", "NDI HX3"),
    RTSP("rtsp", "RTSP (recommended)"),
    UVC("uvc", "USB UVC"),
}

object Tail2PreviewPolicy {
    fun effectiveMode(requested: Tail2PreviewMode, canDecodeHx: Boolean): Tail2PreviewMode =
        if (requested == Tail2PreviewMode.NDI && !canDecodeHx) Tail2PreviewMode.RTSP else requested
}
