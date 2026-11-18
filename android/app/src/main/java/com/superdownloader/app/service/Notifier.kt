package com.superdownloader.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.superdownloader.app.MainActivity
import com.superdownloader.app.R
import com.superdownloader.app.SuperApp
import com.superdownloader.app.data.DownloadEntity
import com.superdownloader.app.engine.Fmt
import com.superdownloader.app.engine.LiveStats

class Notifier(private val ctx: Context) {
    private val nm = NotificationManagerCompat.from(ctx)

    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        const val CH_DONE_SILENT = "done_silent"
        const val GROUP = "sd_downloads"
        const val SUMMARY_ID = 1
        const val RESUME_PROMPT_ID = 2
        const val RED = 0xFFE53935.toInt()
        fun progressId(id: Long) = 1000 + (id % 100000).toInt()
        fun doneId(id: Long) = 200000 + (id % 100000).toInt()
    }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val m = ctx.getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            m.createNotificationChannel(NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
            m.createNotificationChannel(NotificationChannel(CH_DONE_SILENT, "Finished downloads (silent)", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        }
    }

    private fun allowed(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notify(id: Int, n: Notification) {
        if (!allowed()) return
        try { nm.notify(id, n) } catch (_: SecurityException) { }
    }

    fun openAppIntent(downloadId: Long = -1): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (downloadId >= 0) putExtra("open_detail", downloadId)
        }
        return PendingIntent.getActivity(ctx, (downloadId % 100000).toInt() + 10, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(action: String, id: Long): PendingIntent {
        val i = Intent(ctx, NotificationActionReceiver::class.java).setAction(action).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, (id % 100000).toInt() * 4 + action.hashCode() % 4, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun summary(active: Int, speed: Long, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(RED)
            .setContentTitle(if (active > 0) "Downloading $active file${if (active == 1) "" else "s"} · ${Fmt.speed(speed)}" else "Super Downloader")
            .setContentText(text)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .build()

    fun showSummary(n: Notification) = notify(SUMMARY_ID, n)

    fun progress(e: DownloadEntity, ls: LiveStats?) {
        val total = ls?.total ?: e.totalBytes
        val done = ls?.downloaded ?: e.downloadedBytes
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val text = buildString {
            if (total > 0) append("$pct% · ${Fmt.bytes(done)} / ${Fmt.bytes(total)}") else append(Fmt.bytes(done))
            if (ls != null) {
                append(" · ${Fmt.speed(ls.speed)}")
                if (ls.etaSec >= 0) append(" · ${Fmt.duration(ls.etaSec)} left")
            }
        }
        val n = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(RED)
            .setContentTitle(e.fileName.ifBlank { e.url })
            .setContentText(text)
            .setProgress(100, pct, total <= 0)
            .setGroup(GROUP)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(e.id))
            .addAction(0, "Pause", action(NotificationActionReceiver.PAUSE, e.id))
            .addAction(0, "Cancel", action(NotificationActionReceiver.CANCEL, e.id))
            .build()
        notify(progressId(e.id), n)
    }

    fun paused(e: DownloadEntity) {
        val pct = if (e.totalBytes > 0) (e.downloadedBytes * 100 / e.totalBytes).toInt() else 0
        val n = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(RED)
            .setContentTitle(e.fileName.ifBlank { e.url })
            .setContentText("Paused · $pct%")
            .setProgress(100, pct, false)
            .setGroup(GROUP)
            .setSilent(true)
            .setContentIntent(openAppIntent(e.id))
            .addAction(0, "Resume", action(NotificationActionReceiver.RESUME, e.id))
            .addAction(0, "Cancel", action(NotificationActionReceiver.CANCEL, e.id))
            .build()
        notify(progressId(e.id), n)
    }

    fun cancelProgress(id: Long) = nm.cancel(progressId(id))
    fun cancelDone(id: Long) = nm.cancel(doneId(id))

    private fun openFileIntent(e: DownloadEntity): PendingIntent {
        val app = ctx.applicationContext as SuperApp
        val uri = e.outputUri?.let { runCatching { app.engine.output.shareUri(Uri.parse(it)) }.getOrNull() }
        if (uri == null) return openAppIntent(e.id)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return PendingIntent.getActivity(ctx, doneId(e.id), chooser, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun completed(e: DownloadEntity) {
        val sound = (ctx.applicationContext as SuperApp).settings.value.notifySound
        val n = NotificationCompat.Builder(ctx, if (sound) CH_DONE else CH_DONE_SILENT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(RED)
            .setContentTitle("Download complete")
            .setContentText("${e.fileName} · ${Fmt.bytes(e.totalBytes)}" + (e.checksumResult?.let { " · $it" } ?: ""))
            .setAutoCancel(true)
            .setContentIntent(openFileIntent(e))
            .build()
        notify(doneId(e.id), n)
    }

    fun failed(e: DownloadEntity) {
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(RED)
            .setContentTitle("Download failed")
            .setContentText("${e.fileName.ifBlank { e.url }}: ${e.error ?: "error"}")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(e.id))
            .addAction(0, "Retry", action(NotificationActionReceiver.RESUME, e.id))
            .build()
        notify(doneId(e.id), n)
    }

    fun showResumePrompt(count: Int) {
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(RED)
            .setContentTitle("$count download${if (count == 1) "" else "s"} ready to continue")
            .setContentText("Tap to resume in Super Downloader")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notify(RESUME_PROMPT_ID, n)
    }
}
