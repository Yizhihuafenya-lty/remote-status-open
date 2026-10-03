package com.example.remotestatus.core

object Hosts {
    private val IPV6 = Regex("^[0-9A-Fa-f:]+$")

    fun isIpv6(value: String): Boolean {
        val normalized = normalize(value)
        return normalized.contains(':') && IPV6.matches(normalized)
    }

    fun normalize(value: String): String = value.trim().removeSurrounding("[", "]")

    fun authority(host: String, port: Int): String {
        val normalized = normalize(host)
        require(normalized.isNotEmpty()) { "host is required" }
        require(port in 1..65_535) { "port is out of range" }
        return if (isIpv6(normalized)) "[$normalized]:$port" else "$normalized:$port"
    }

    fun endpoint(scheme: String, host: String, port: Int): String {
        val cleanScheme = scheme.trim().lowercase()
        require(cleanScheme == "http" || cleanScheme == "https") { "scheme must be http or https" }
        return "$cleanScheme://${authority(host, port)}"
    }
}
