package com.superdownloader.app.engine

import kotlinx.coroutines.delay

/** Token bucket shared by connections. bytesPerSec = 0 means unlimited. */
class SpeedLimiter {
    @Volatile
    var bytesPerSec: Long = 0
    private var available = 0.0
    private var last = System.nanoTime()

    suspend fun acquire(n: Int) {
        val rate = bytesPerSec
        if (rate <= 0) return
        val waitMs: Long
        synchronized(this) {
            val now = System.nanoTime()
            available = minOf(rate.toDouble(), available + (now - last) / 1e9 * rate)
            last = now
            available -= n
            waitMs = if (available < 0) (-available / rate * 1000).toLong() else 0L
        }
        if (waitMs > 0) delay(waitMs)
    }
}
