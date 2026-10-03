package com.example.remotestatus.data

enum class RelayId(val displayName: String) {
    V4("V4"),
    V6("V6")
}

data class Relay(
    val id: RelayId,
    val host: String,
    val port: Int,
    val scheme: String = "http"
)

data class DeviceSummary(
    val deviceId: String,
    val hostname: String,
    val name: String,
    val address: String?,
    val port: Int?,
    val collectedAt: String?,
    val online: Boolean,
    val cpuPercent: Double,
    val memoryPercent: Double
)

data class NetworkCounters(
    val rxBytes: Long,
    val txBytes: Long
)

data class CpuSpec(
    val usagePercent: Double,
    val coreCount: Int
)

data class MemorySpec(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedPercent: Double,
    val swapTotalBytes: Long,
    val swapFreeBytes: Long
)

data class FilesystemSpec(
    val mount: String,
    val filesystem: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val usedPercent: Double
)

data class Snapshot(
    val deviceId: String,
    val hostname: String,
    val collectedAt: String,
    val uptimeSeconds: Double,
    val cpu: CpuSpec,
    val memory: MemorySpec,
    val filesystems: List<FilesystemSpec>,
    val network: List<NetworkCounters>
)

data class Device(
    val relay: Relay,
    val summary: DeviceSummary
) {
    val id: String get() = "${relay.id.name}:${summary.deviceId}"
}

data class DeviceMetric(
    val device: Device,
    val snapshot: Snapshot
)

data class NetworkRate(
    val rxBytesPerSecond: Double,
    val txBytesPerSecond: Double
)

data class Totals(
    val onlineDevices: Int,
    val totalDevices: Int,
    val totalCores: Int,
    val usedCores: Double,
    val cpuPercent: Double,
    val totalMemoryBytes: Long,
    val usedMemoryBytes: Long,
    val memoryPercent: Double,
    val downloadBytesPerSecond: Double,
    val uploadBytesPerSecond: Double,
    val cumulativeBytes: Long
) {
    val bandwidthBytesPerSecond: Double
        get() = downloadBytesPerSecond + uploadBytesPerSecond
}

sealed class RelayResult {
    data class Success(val relay: Relay, val devices: List<DeviceSummary>) : RelayResult()
    data class Failure(val relay: Relay, val message: String) : RelayResult()
}

data class DashboardState(
    val devices: List<Device>,
    val totals: Totals,
    val relayStates: Map<RelayId, String>,
    val updatedAtMillis: Long
)
