package com.superdownloader.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.core.app.ServiceCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.superdownloader.app.R
import com.superdownloader.app.SuperApp
import com.superdownloader.app.data.SettingsState
import com.superdownloader.app.data.Status
import com.superdownloader.app.engine.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Foreground service (dataSync) that keeps every connection running while the app is in the
 * background or swiped away. Holds a partial wake lock and a Wi‑Fi lock only while active.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val shown = HashSet<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        acquireLocks()
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_STICKY
    }

    private fun goForeground() {
        val app = application as SuperApp
        val n = app.notifier.summary(app.engine.activeIds.value.size, 0, "Preparing downloads…")
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, Notifier.SUMMARY_ID, n, type)
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SuperDownloader:downloads").apply {
                setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "SuperDownloader:wifi").apply { setReferenceCounted(false); acquire() }
        }
    }

    private suspend fun loop() {
        val app = application as SuperApp
        val engine = app.engine
        var idleTicks = 0
        var widgetTick = 0
        while (true) {
            delay(1000)
            val ids = engine.activeIds.value
            val live = engine.live.value
            var speed = 0L
            var done = 0L
            var total = 0L
            for (id in ids) {
                val e = engine.dao.get(id) ?: continue
                val ls = live[id]
                speed += ls?.speed ?: 0
                done += ls?.downloaded ?: e.downloadedBytes
                total += (ls?.total ?: e.totalBytes).coerceAtLeast(0)
                app.notifier.progress(e, ls)
                shown.add(id)
            }
            shown.filter { it !in ids }.forEach { shown.remove(it) }
            val text = engine.blockedReason.value ?: if (total > 0) "${Fmt.bytes(done)} of ${Fmt.bytes(total)}" else "${Fmt.bytes(done)} downloaded"
            app.notifier.showSummary(app.notifier.summary(ids.size, speed, text))
            if (++widgetTick % 3 == 0) {
                WidgetUpdater.push(this, ids.size, speed, if (total > 0) (done * 100 / total).toInt() else 0)
                DownloadTileService.requestUpdate(this)
            }
            if (ids.isEmpty()) {
                if (++idleTicks >= 2) {
                    val pending = engine.dao.pendingCount()
                    if (pending > 0) RestoreWorker.enqueue(this, app.settings.value.wifiOnly)
                    WidgetUpdater.push(this, 0, 0, 0)
                    DownloadTileService.requestUpdate(this)
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return
                }
            } else idleTicks = 0
        }
    }

    override fun onDestroy() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        scope.cancel()
        super.onDestroy()
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val PAUSE = "com.superdownloader.app.PAUSE"
        const val RESUME = "com.superdownloader.app.RESUME"
        const val CANCEL = "com.superdownloader.app.CANCEL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SuperApp
        val id = intent.getLongExtra("id", -1)
        if (id < 0) return
        when (intent.action) {
            PAUSE -> app.engine.pause(id)
            RESUME -> app.engine.resume(id)
            CANCEL -> app.engine.cancel(id)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SuperApp
        RestoreWorker.enqueue(context, app.settings.value.wifiOnly)
        Scheduler.reschedule(context, app.settings.value)
    }
}

/** Restores the queue after reboot, app kill or network return. */
class RestoreWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SuperApp
        val pending = app.db.dao().pendingCount()
        if (pending == 0) return Result.success()
        app.engine.kick()
        if (!app.engine.ensureService()) app.notifier.showResumePrompt(pending)
        return Result.success()
    }

    companion object {
        fun enqueue(ctx: Context, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<RestoreWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sd_restore", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

object Scheduler {
    fun inWindow(s: SettingsState, now: Calendar): Boolean {
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val a = s.scheduleStart
        val b = s.scheduleStop
        return if (a == b) true else if (a < b) m in a until b else m >= a || m < b
    }

    private fun nextAt(minuteOfDay: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        c.set(Calendar.MINUTE, minuteOfDay % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun reschedule(ctx: Context, s: SettingsState) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, ScheduleReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (!s.scheduleEnabled) return
        val t = minOf(nextAt(s.scheduleStart), nextAt(s.scheduleStop))
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi) }
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SuperApp
        val s = app.settings.value
        app.engine.kick()
        if (s.scheduleEnabled && Scheduler.inWindow(s, Calendar.getInstance())) RestoreWorker.enqueue(context, s.wifiOnly)
        Scheduler.reschedule(context, s)
    }
}

class DownloadTileService : TileService() {
    companion object {
        fun requestUpdate(ctx: Context) {
            runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, DownloadTileService::class.java)) }
        }
    }

    override fun onStartListening() { refresh() }

    override fun onClick() {
        val engine = (application as SuperApp).engine
        if (engine.activeIds.value.isNotEmpty()) engine.pauseAll() else engine.resumeAll()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val n = (application as SuperApp).engine.activeIds.value.size
        tile.state = if (n > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Super Downloader"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (n > 0) "$n active · tap to pause" else "Tap to resume all"
        tile.updateTile()
    }
}

class DownloadsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val engine = (context.applicationContext as SuperApp).engine
        WidgetUpdater.push(context, engine.activeIds.value.size, engine.live.value.values.sumOf { it.speed }, 0)
    }
}

object WidgetUpdater {
    fun push(ctx: Context, active: Int, speed: Long, progress: Int) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, DownloadsWidget::class.java))
        if (ids.isEmpty()) return
        val app = ctx.applicationContext as SuperApp
        val rv = RemoteViews(ctx.packageName, R.layout.widget_downloads)
        rv.setTextViewText(R.id.w_title, if (active > 0) "$active active download${if (active == 1) "" else "s"}" else "Super Downloader")
        rv.setTextViewText(R.id.w_sub, if (active > 0) "${Fmt.speed(speed)} · $progress%" else (app.engine.blockedReason.value ?: "Idle"))
        rv.setProgressBar(R.id.w_progress, 100, progress, false)
        rv.setOnClickPendingIntent(R.id.w_root, app.notifier.openAppIntent())
        mgr.updateAppWidget(ids, rv)
    }
}
