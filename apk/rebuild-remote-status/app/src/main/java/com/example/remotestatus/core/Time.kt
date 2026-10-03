package com.example.remotestatus.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Time {
    private val display = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Parses an RFC3339 timestamp from the relay and renders it in the device time zone. */
    fun local(value: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (value.isNullOrBlank()) return "—"
        return try {
            OffsetDateTime.parse(value).atZoneSameInstant(zone).format(display)
        } catch (_: Exception) {
            try {
                Instant.parse(value).atZone(zone).format(display)
            } catch (_: Exception) {
                value
            }
        }
    }

    fun localFromMillis(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(display)
}
