package com.example.remotestatus.data

import org.json.JSONArray
import org.json.JSONObject

object JsonParser {
    fun parseDeviceSummaries(payload: String): List<DeviceSummary> {
        val root = JSONObject(payload)
        val devices = root.optJSONArray("devices") ?: JSONArray()
        return buildList {
            for (index in 0 until devices.length()) {
                val item = devices.optJSONObject(index) ?: continue
                val deviceId = item.optString("device_id").trim()
                if (deviceId.isEmpty()) continue
                add(
                    DeviceSummary(
                        deviceId = deviceId,
                        hostname = item.optString("hostname").trim(),
                        name = item.optString("name").trim().ifEmpty { item.optString("hostname").trim() },
                        address = item.optString("address").trim().ifEmpty { null },
                        port = if (item.has("port") && !item.isNull("port")) item.optInt("port") else null,
                        collectedAt = item.optString("collected_at").trim().ifEmpty { null },
                        online = item.optBoolean("online", false),
                        cpuPercent = finite(item.optDouble("cpu_usage_percent", Double.NaN)),
                        memoryPercent = finite(item.optDouble("memory_used_percent", Double.NaN))
                    )
                )
            }
        }
    }

    fun parseSnapshot(payload: String): Snapshot {
        val root = JSONObject(payload)
        val item = root.optJSONObject("snapshot") ?: root
        val cpu = item.optJSONObject("cpu") ?: JSONObject()
        val memory = item.optJSONObject("memory") ?: JSONObject()
        val filesystems = item.optJSONArray("filesystems") ?: JSONArray()
        val network = item.optJSONArray("network") ?: JSONArray()

        return Snapshot(
            deviceId = item.optString("device_id").trim(),
            hostname = item.optString("hostname").trim(),
            collectedAt = item.optString("collected_at").trim(),
            uptimeSeconds = finite(item.optDouble("uptime_seconds", 0.0)),
            cpu = CpuSpec(
                usagePercent = finite(cpu.optDouble("usage_percent", Double.NaN)),
                coreCount = cpu.optInt("core_count", 0)
            ),
            memory = MemorySpec(
                totalBytes = nonNegativeLong(memory.optLong("total_bytes", 0)),
                availableBytes = nonNegativeLong(memory.optLong("available_bytes", 0)),
                usedPercent = finite(memory.optDouble("used_percent", Double.NaN)),
                swapTotalBytes = nonNegativeLong(memory.optLong("swap_total_bytes", 0)),
                swapFreeBytes = nonNegativeLong(memory.optLong("swap_free_bytes", 0))
            ),
            filesystems = buildList {
                for (index in 0 until filesystems.length()) {
                    val itemFilesystem = filesystems.optJSONObject(index) ?: continue
                    add(
                        FilesystemSpec(
                            mount = itemFilesystem.optString("mount").trim(),
                            filesystem = itemFilesystem.optString("filesystem").trim(),
                            totalBytes = nonNegativeLong(itemFilesystem.optLong("total_bytes", 0)),
                            usedBytes = nonNegativeLong(itemFilesystem.optLong("used_bytes", 0)),
                            freeBytes = nonNegativeLong(itemFilesystem.optLong("free_bytes", 0)),
                            usedPercent = finite(itemFilesystem.optDouble("used_percent", Double.NaN))
                        )
                    )
                }
            },
            network = buildList {
                for (index in 0 until network.length()) {
                    val itemNetwork = network.optJSONObject(index) ?: continue
                    add(
                        NetworkCounters(
                            rxBytes = nonNegativeLong(itemNetwork.optLong("rx_bytes", 0)),
                            txBytes = nonNegativeLong(itemNetwork.optLong("tx_bytes", 0))
                        )
                    )
                }
            }
        )
    }

    private fun finite(value: Double): Double = if (value.isFinite()) value else 0.0

    private fun nonNegativeLong(value: Long): Long = if (value >= 0) value else 0
}
