package com.superdownloader.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object Status {
    const val QUEUED = 0
    const val DOWNLOADING = 1
    const val PAUSED = 2
    const val COMPLETED = 3
    const val FAILED = 4
    const val WAITING = 5

    fun label(s: Int): String = when (s) {
        QUEUED -> "Queued"
        DOWNLOADING -> "Downloading"
        PAUSED -> "Paused"
        COMPLETED -> "Completed"
        FAILED -> "Error"
        WAITING -> "Waiting"
        else -> "?"
    }
}

object Priority {
    const val SNAIL = -1
    const val LOW = 0
    const val NORMAL = 1
    const val HIGH = 2
    val all = listOf(HIGH, NORMAL, LOW, SNAIL)
    fun label(p: Int) = when (p) { SNAIL -> "Snail"; LOW -> "Low"; HIGH -> "High"; else -> "Normal" }
}

@Entity(tableName = "downloads", indices = [Index("url")])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String = "",
    val mimeType: String? = null,
    val category: String = "Other",
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: Int = Status.QUEUED,
    val supportsRange: Boolean = false,
    val maxConnections: Int = 8,
    val sequential: Boolean = false,
    val priority: Int = Priority.NORMAL,
    val queueOrder: Long = System.currentTimeMillis(),
    val headers: String = "",
    val userAgent: String? = null,
    val referer: String? = null,
    val cookies: String? = null,
    val outputUri: String? = null,
    val savedIn: String? = null,
    val error: String? = null,
    val warning: String? = null,
    val checksumType: String? = null,
    val expectedChecksum: String? = null,
    val checksumResult: String? = null,
    val isPrivate: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val elapsedMs: Long = 0,
    val peakSpeed: Long = 0,
    val speedLimitKbps: Int = 0,
    val log: String = "",
    val etag: String? = null,
    val lastModified: String? = null,
    val serverName: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

@Entity(tableName = "segments", indices = [Index("downloadId")])
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val downloadId: Long,
    val start: Long,
    val end: Long,
    val downloaded: Long,
)
