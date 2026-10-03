package com.example.remotestatus.data

import com.example.remotestatus.core.Bytes
import com.example.remotestatus.core.Hosts
import com.example.remotestatus.core.Time
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject

class Repository(
    private val client: RelayClient,
    private val relays: List<Relay>,
    private val rateTracker: NetworkRateTracker = NetworkRateTracker()
) {
    companion object {
        const val REFRESH_MILLIS = 1_000L
        private const val METRIC_TIMEOUT_MILLIS = 3_500L

        /** The relay itself accepts any non-empty name; this only caps abusive input. */
        const val MAX_NAME_LENGTH = 200
    }

    fun dashboard(): Flow<DashboardState> = flow {
        while (true) {
            emit(loadDashboard())
            delay(REFRESH_MILLIS)
        }
    }.flowOn(Dispatchers.Default)

    suspend fun loadDashboard(): DashboardState {
        val results = client.loadDevicesParallel()
        val devices = results
            .filterIsInstance<RelayResult.Success>()
            .flatMap { result -> result.devices.map { summary -> Device(result.relay, summary) } }
            .sortedWith(compareBy({ it.relay.id.ordinal }, { displayName(it) }))

        val now = System.currentTimeMillis()
        val metrics = loadMetrics(devices)
        val rates = devices.associate { device ->
            val metric = metrics[device.id]
            device.id to if (metric == null) NetworkRate(0.0, 0.0) else rateTracker.rate(device.id, metric.snapshot, now)
        }

        val relayStates = results.associate { result ->
            val relay = when (result) {
                is RelayResult.Success -> result.relay
                is RelayResult.Failure -> result.relay
            }
            relay.id to when (result) {
                is RelayResult.Success -> "已连接 · ${result.devices.size} 台"
                is RelayResult.Failure -> "连接失败 · ${result.message}"
            }
        }

        return DashboardState(
            devices = devices,
            totals = Aggregate.totals(devices, metrics, rates),
            relayStates = relayStates,
            updatedAtMillis = now
        )
    }

    /**
     * Renames a device on the relay that owns it; the name is persisted by that relay.
     * The relay imposes no length limit, so only an empty name and an abusive
     * payload size are rejected here.
     */
    suspend fun rename(device: Device, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "名称不能为空" }
        require(trimmed.length <= MAX_NAME_LENGTH) { "名称最长 $MAX_NAME_LENGTH 个字符" }
        client.rename(device, trimmed)
    }

    suspend fun loadMetric(device: Device): DeviceMetric {
        val snapshot = client.loadMetrics(device)
        return DeviceMetric(device, snapshot)
    }

    fun endpoint(relay: Relay): String = Hosts.endpoint(relay.scheme, relay.host, relay.port)

    private suspend fun loadMetrics(devices: List<Device>): Map<String, DeviceMetric> = coroutineScope {
        devices.map { device ->
            async(Dispatchers.IO) {
                try {
                    device.id to DeviceMetric(device, client.loadMetrics(device))
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }
}

fun buildAddTargetPayload(
    relay: Relay,
    deviceId: String,
    host: String,
    port: Int,
    name: String
): String {
    val address = Hosts.normalize(host)
    return JSONObject()
        .put("device_id", deviceId.trim())
        .put("address", address)
        .put("port", port)
        .put("name", name.trim())
        .toString()
}

fun buildRenamePayload(name: String): String = JSONObject().put("name", name.trim()).toString()

fun networkSpeed(snapshot: Snapshot, rate: NetworkRate?): String {
    val rx = snapshot.network.sumOf { it.rxBytes }
    val tx = snapshot.network.sumOf { it.txBytes }
    val live = if (rate == null || (rate.rxBytesPerSecond == 0.0 && rate.txBytesPerSecond == 0.0)) {
        "实时测速中"
    } else {
        "下行 ${Bytes.rate(rate.rxBytesPerSecond)} · 上行 ${Bytes.rate(rate.txBytesPerSecond)}"
    }
    return "$live\n累计下行 ${formatBytes(rx)} · 累计上行 ${formatBytes(tx)}"
}

fun displayName(device: Device): String {
    val summary = device.summary
    return summary.name.ifBlank { summary.hostname }.ifBlank { summary.deviceId }
}

fun basicConfigurationLines(device: Device, snapshot: Snapshot): List<Pair<String, String>> {
    val memory = snapshot.memory
    val root = snapshot.filesystems
        .filter { it.mount == "/" }
        .maxByOrNull { it.totalBytes }
    val summary = device.summary
    val address = summary.address
    return buildList {
        add("服务器" to displayName(device))
        add("所属中继" to "${device.relay.id.displayName} · ${Hosts.endpoint(device.relay.scheme, device.relay.host, device.relay.port)}")
        if (!address.isNullOrBlank()) {
            add("地址" to if (summary.port != null) "${Hosts.normalize(address)}:${summary.port}" else Hosts.normalize(address))
        }
        add("设备 ID" to snapshot.deviceId)
        add("主机名" to snapshot.hostname)
        add("操作系统" to "Linux")
        add("CPU 核心" to snapshot.cpu.coreCount.toString())
        add("内存容量" to formatBytes(memory.totalBytes))
        add("磁盘" to (root?.let { "${it.mount} · ${formatBytes(it.totalBytes)} · 已用 ${formatBytes(it.usedBytes)}" } ?: "—"))
        add("运行时长" to formatUptime(snapshot.uptimeSeconds))
        add("采样时间" to Time.local(snapshot.collectedAt))
    }
}

fun formatBytes(value: Long): String {
    val bytes = value.coerceAtLeast(0)
    val mb = bytes / 1_048_576.0
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1.0) String.format(java.util.Locale.US, "%,.0f MB (%.2f GB)", mb, gb)
    else String.format(java.util.Locale.US, "%,.1f MB", mb)
}

fun formatUptime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val days = TimeUnit.SECONDS.toDays(total)
    val hours = TimeUnit.SECONDS.toHours(total) % 24
    val minutes = TimeUnit.SECONDS.toMinutes(total) % 60
    return buildString {
        if (days > 0) append("${days}天 ")
        if (hours > 0 || days > 0) append("${hours}小时 ")
        append("${minutes}分钟")
    }
}
