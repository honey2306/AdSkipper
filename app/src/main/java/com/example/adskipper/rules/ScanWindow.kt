package com.example.adskipper.rules

object ScanWindow {
    const val DURATION_MILLIS = 12_000L
    const val SCAN_INTERVAL_MILLIS = 220L
    const val MAX_ATTEMPTS = 4

    fun isActive(
        startedAt: Long,
        now: Long,
    ): Boolean = startedAt > 0L && now >= startedAt && now - startedAt <= DURATION_MILLIS
}
