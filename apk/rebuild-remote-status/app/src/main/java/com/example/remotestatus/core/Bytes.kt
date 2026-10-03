package com.example.remotestatus.core

import java.util.Locale

object Bytes {
    fun toMegabytes(value: Long): Double = value / 1_048_576.0

    fun toGigabytes(value: Long): Double = value / 1_073_741_824.0

    fun memory(value: Long): String {
        val mb = toMegabytes(value)
        val gb = toGigabytes(value)
        return String.format(Locale.US, "%,.0f MB (%.2f GB)", mb, gb)
    }

    fun rate(value: Double): String {
        return when {
            value >= 1_073_741_824.0 -> String.format(Locale.US, "%.2f GB/s", value / 1_073_741_824.0)
            value >= 1_048_576.0 -> String.format(Locale.US, "%.2f MB/s", value / 1_048_576.0)
            value >= 1_024.0 -> String.format(Locale.US, "%.1f KB/s", value / 1_024.0)
            else -> String.format(Locale.US, "%.0f B/s", value)
        }
    }

    fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value.coerceIn(0.0, 100.0))

    fun compactNumber(value: Double): String = String.format(Locale.US, "%.1f", value)
}
