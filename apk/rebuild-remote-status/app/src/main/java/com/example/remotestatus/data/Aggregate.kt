package com.example.remotestatus.data

object Aggregate {
    fun totals(devices: List<Device>, metrics: Map<String, DeviceMetric>, rates: Map<String, NetworkRate>): Totals {
        val measured = devices.mapNotNull { device -> metrics[device.id] }
        val cpuDevices = measured.filter { it.snapshot.cpu.coreCount > 0 }
        val memoryDevices = measured.filter { it.snapshot.memory.totalBytes > 0 }

        val totalCores = cpuDevices.sumOf { it.snapshot.cpu.coreCount }
        val usedCores = cpuDevices.sumOf {
            it.snapshot.cpu.coreCount * it.snapshot.cpu.usagePercent.coerceIn(0.0, 100.0) / 100.0
        }
        val totalMemory = memoryDevices.sumOf { it.snapshot.memory.totalBytes }
        val usedMemory = memoryDevices.sumOf {
            it.snapshot.memory.totalBytes - it.snapshot.memory.availableBytes
        }.coerceAtLeast(0)

        return Totals(
            onlineDevices = devices.count { it.summary.online },
            totalDevices = devices.size,
            totalCores = totalCores,
            usedCores = usedCores,
            cpuPercent = if (totalCores > 0) usedCores / totalCores * 100.0 else 0.0,
            totalMemoryBytes = totalMemory,
            usedMemoryBytes = usedMemory,
            memoryPercent = if (totalMemory > 0) usedMemory.toDouble() / totalMemory * 100.0 else 0.0,
            downloadBytesPerSecond = rates.values.sumOf { it.rxBytesPerSecond },
            uploadBytesPerSecond = rates.values.sumOf { it.txBytesPerSecond },
            cumulativeBytes = measured.sumOf { metric ->
                metric.snapshot.network.sumOf { it.rxBytes + it.txBytes }
            }
        )
    }
}
