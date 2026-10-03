package com.example.remotestatus.data

import com.example.remotestatus.core.Hosts
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class RelayClient(
    private val relays: List<Relay>,
    private val appToken: String,
    private val client: OkHttpClient = defaultClient()
) {
    init {
        require(relays.isNotEmpty()) { "at least one relay is required" }
        require(appToken.isNotBlank()) { "app bearer token is required" }
    }

    suspend fun loadDevicesParallel(): List<RelayResult> = coroutineScope {
        relays.map { relay ->
            async(Dispatchers.IO) { loadDevices(relay) }
        }.awaitAll()
    }

    suspend fun loadDevices(relay: Relay): RelayResult = withContext(Dispatchers.IO) {
        try {
            val payload = get(relay, "/v1/devices")
            RelayResult.Success(relay, JsonParser.parseDeviceSummaries(payload))
        } catch (error: Exception) {
            RelayResult.Failure(relay, error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun loadMetrics(device: Device): Snapshot = withContext(Dispatchers.IO) {
        JsonParser.parseSnapshot(get(device.relay, metricsPath(device.summary.deviceId)))
    }

    /** Saves a new display name for this device on its own relay. */
    suspend fun rename(device: Device, name: String): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint(device.relay) + namePath(device.summary.deviceId))
            .header("Authorization", "Bearer $appToken")
            .header("Content-Type", "application/json")
            .patch(buildRenamePayload(name).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("${device.relay.id.displayName} 改名失败 HTTP ${response.code}: ${body.take(160)}")
            }
        }
    }

    fun endpoint(relay: Relay): String = Hosts.endpoint(relay.scheme, relay.host, relay.port)

    private fun metricsPath(deviceId: String) = "/v1/devices/${escape(deviceId)}/metrics"

    private fun namePath(deviceId: String) = "/v1/devices/${escape(deviceId)}/name"

    private fun escape(deviceId: String): String =
        deviceId.split("/").joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    private fun get(relay: Relay, path: String): String {
        val request = Request.Builder()
            .url(endpoint(relay) + path)
            .header("Authorization", "Bearer $appToken")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("${relay.id.displayName} HTTP ${response.code}: ${body.take(160)}")
            }
            return body
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Small HTTP client tuned for a 1 s polling loop against two relays:
         * at most two idle sockets kept alive (one per relay), no transparent
         * retries, and a hard call timeout so a stalled relay never piles up.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
            .dispatcher(Dispatcher().apply {
                maxRequests = 8
                maxRequestsPerHost = 4
            })
            .build()
    }
}
