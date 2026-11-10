package com.example.alsexperiment

internal object CaptureWindow {
    fun contains(timestampNs: Long, startNs: Long, endNs: Long): Boolean =
        timestampNs >= startNs && timestampNs < endNs

    fun <T> snapshot(rows: List<T>, startNs: Long, endNs: Long, timestamp: (T) -> Long): List<T> =
        rows.filter { contains(timestamp(it), startNs, endNs) }
}
