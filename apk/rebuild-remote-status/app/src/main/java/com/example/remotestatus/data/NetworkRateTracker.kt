package com.example.remotestatus.data

class NetworkRateTracker {
    private data class Sample(
        val timestampMillis: Long,
        val rxBytes: Long,
        val txBytes: Long
    )

    private val previous = mutableMapOf<String, Sample>()

    fun rate(deviceId: String, snapshot: Snapshot, nowMillis: Long): NetworkRate {
        val rx = snapshot.network.sumOf { it.rxBytes }
        val tx = snapshot.network.sumOf { it.txBytes }
        val prior = previous[deviceId]
        previous[deviceId] = Sample(nowMillis, rx, tx)

        if (prior == null || nowMillis <= prior.timestampMillis) {
            return NetworkRate(0.0, 0.0)
        }

        val elapsedSeconds = (nowMillis - prior.timestampMillis) / 1_000.0
        if (elapsedSeconds <= 0.0) return NetworkRate(0.0, 0.0)

        val rxDelta = (rx - prior.rxBytes).coerceAtLeast(0).toDouble()
        val txDelta = (tx - prior.txBytes).coerceAtLeast(0).toDouble()
        return NetworkRate(rxDelta / elapsedSeconds, txDelta / elapsedSeconds)
    }

    fun clear() = previous.clear()
}
