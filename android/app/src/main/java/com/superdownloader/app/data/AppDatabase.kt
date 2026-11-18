package com.superdownloader.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    abstract fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    abstract fun observe(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    abstract suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads")
    abstract suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN (0, 5) ORDER BY priority DESC, queueOrder ASC")
    abstract suspend fun queued(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN (0, 1, 5)")
    abstract suspend fun pendingCount(): Int

    @Query("SELECT * FROM downloads WHERE url = :url LIMIT 1")
    abstract suspend fun findByUrl(url: String): DownloadEntity?

    @Insert
    abstract suspend fun insert(e: DownloadEntity): Long

    @Update
    abstract suspend fun update(e: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: Int, error: String?)

    @Query("UPDATE downloads SET downloadedBytes = :bytes, elapsedMs = :elapsed, peakSpeed = :peak WHERE id = :id")
    abstract suspend fun setProgress(id: Long, bytes: Long, elapsed: Long, peak: Long)

    @Query("UPDATE downloads SET priority = :p WHERE id = :id")
    abstract suspend fun setPriority(id: Long, p: Int)

    @Query("UPDATE downloads SET queueOrder = :order WHERE id = :id")
    abstract suspend fun setOrder(id: Long, order: Long)

    @Query("UPDATE downloads SET log = :log WHERE id = :id")
    abstract suspend fun setLog(id: Long, log: String)

    @Query("UPDATE downloads SET status = 0 WHERE status = 1")
    abstract suspend fun resetActive()

    @Query("DELETE FROM downloads WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT * FROM segments WHERE downloadId = :id ORDER BY start")
    abstract suspend fun segments(id: Long): List<SegmentEntity>

    @Query("DELETE FROM segments WHERE downloadId = :id")
    abstract suspend fun clearSegments(id: Long)

    @Insert
    abstract suspend fun insertSegments(list: List<SegmentEntity>)

    @Transaction
    open suspend fun replaceSegments(id: Long, list: List<SegmentEntity>) {
        clearSegments(id)
        if (list.isNotEmpty()) insertSegments(list)
    }
}

@Database(entities = [DownloadEntity::class, SegmentEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): DownloadDao

    companion object {
        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "superdownloader.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
