// Super Downloader — Created by Tez
package com.superdownloader.app.engine

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.superdownloader.app.data.AppDatabase
import com.superdownloader.app.data.AppSettings
import com.superdownloader.app.data.DownloadEntity
import com.superdownloader.app.data.Priority
import com.superdownloader.app.data.SettingsState
import com.superdownloader.app.data.Status
import com.superdownloader.app.service.DownloadService
import com.superdownloader.app.service.Notifier
import com.superdownloader.app.service.RestoreWorker
import com.superdownloader.app.service.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One piece of the file as the UI sees it. */
data class SegInfo(val start: Long, val end: Long, val downloaded: Long, val active: Boolean, val speed: Long, val conn: Int)

data class LiveStats(
    val downloaded: Long,
    val total: Long,
    val speed: Long,
    val avgSpeed: Long,
    val peakSpeed: Long,
    val elapsedMs: Long,
    val etaSec: Long,
    val connections: Int,
    val segments: List<SegInfo>,
    val history: List<Float>,
)

data class AddRequest(
    val url: String,
    val fileName: String = "",
    val headers: String = "",
    val cookies: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val connections: Int = 0,
    val sequential: Boolean = false,
    val priority: Int = Priority.NORMAL,
    val checksumType: String? = null,
    val expectedChecksum: String? = null,
    val startPaused: Boolean = false,
)

class DownloadEngine(
    val ctx: Context,
    val db: AppDatabase,
    val settings: AppSettings,
    val notifier: Notifier,
) {
    val dao = db.dao()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val output = OutputManager(ctx, settings)
    val network = NetworkMonitor(ctx)
    private val tasks = ConcurrentHashMap<Long, DownloadTask>()
    private val _live = MutableStateFlow<Map<Long, LiveStats>>(emptyMap())
    val live: StateFlow<Map<Long, LiveStats>> = _live
    private val kickCh = Channel<Unit>(Channel.CONFLATED)
    val globalLimiter = SpeedLimiter()
    private val hostSems = ConcurrentHashMap<String, Semaphore>()
    @Volatile private var hostSemSize = 16
    val activeIds = MutableStateFlow<Set<Long>>(emptySet())
    val blockedReason = MutableStateFlow<String?>(null)
    val isLowRam: Boolean = ctx.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    private val power = ctx.getSystemService(PowerManager::class.java)

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile var client: OkHttpClient = baseClient
        private set
    private var proxyKey = ""

    fun start() {
        scope.launch { loop() }
        scope.launch {
            settings.state.collect { s ->
                applySettings(s)
                Scheduler.reschedule(ctx, s)
                kick()
            }
        }
        scope.launch {
            network.state.map { it.connected to it.networkId }.distinctUntilChanged().collect {
                // Fast resume on network change: drop stale sockets and restart waiting work now.
                client.connectionPool.evictAll()
                kick()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { kick() }
            }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun applySettings(s: SettingsState) {
        globalLimiter.bytesPerSec = s.limitBytesPerSec()
        val key = "${s.proxyType}|${s.proxyHost}|${s.proxyPort}"
        if (key != proxyKey) {
            proxyKey = key
            client = if (s.proxyType != 0 && s.proxyHost.isNotBlank() && s.proxyPort in 1..65535) {
                val type = if (s.proxyType == 2) Proxy.Type.SOCKS else Proxy.Type.HTTP
                baseClient.newBuilder().proxy(Proxy(type, InetSocketAddress.createUnresolved(s.proxyHost, s.proxyPort))).build()
            } else baseClient.newBuilder().proxy(Proxy.NO_PROXY).build()
        }
        val perServer = effectivePerServer(s)
        if (perServer != hostSemSize) { hostSemSize = perServer; hostSems.clear() }
    }

    fun effectiveParts(s: SettingsState = settings.value): Int = if (isLowRam) s.parts.coerceIn(1, 4) else s.parts.coerceIn(1, 16)
    fun effectiveSimultaneous(s: SettingsState = settings.value): Int = if (isLowRam) s.maxSimultaneous.coerceIn(1, 2) else s.maxSimultaneous.coerceIn(1, 10)
    fun effectivePerServer(s: SettingsState = settings.value): Int = if (isLowRam) s.maxPerServer.coerceIn(1, 4) else s.maxPerServer.coerceIn(1, 16)

    fun hostSemaphore(host: String): Semaphore = hostSems.getOrPut(host) { Semaphore(hostSemSize) }

    fun kick() { kickCh.trySend(Unit) }

    private suspend fun loop() {
        runCatching { dao.resetActive() }
        while (true) {
            try { schedule() } catch (t: Throwable) { Log.w("SD", "schedule", t) }
            withTimeoutOrNull(3000) { kickCh.receive() }
        }
    }

    private fun computeBlock(s: SettingsState): String? {
        val net = network.state.value
        if (!net.connected) return "Waiting for network"
        if (s.wifiOnly && !net.unmetered) return "Waiting for Wi‑Fi"
        if (s.pauseOnBatterySaver && power?.isPowerSaveMode == true) return "Paused: battery saver is on"
        if (s.scheduleEnabled && !Scheduler.inWindow(s, Calendar.getInstance())) {
            return "Scheduled: starts at %02d:%02d".format(s.scheduleStart / 60, s.scheduleStart % 60)
        }
        return null
    }

    private suspend fun schedule() {
        val s = settings.value
        val reason = computeBlock(s)
        blockedReason.value = reason
        if (reason != null) {
            tasks.values.forEach { it.stop(DownloadTask.STOP_WAIT) }
            return
        }
        val max = effectiveSimultaneous(s)
        if (tasks.size >= max) return
        val queued = dao.queued().filter { !tasks.containsKey(it.id) }
        for (e in queued.take(max - tasks.size)) launchTask(e)
    }

    private fun launchTask(e: DownloadEntity) {
        val t = DownloadTask(this, e.id)
        tasks[e.id] = t
        updateActive()
        ensureService()
        t.job = scope.launch {
            try { t.run() } finally {
                tasks.remove(e.id)
                _live.value = _live.value - e.id
                updateActive()
                kick()
            }
        }
    }

    private fun updateActive() { activeIds.value = tasks.keys.toSet() }

    fun publishLive(id: Long, ls: LiveStats) {
        if (tasks.containsKey(id)) _live.value = _live.value + (id to ls)
    }

    /** Starts the foreground service. Returns false when Android refuses a background start. */
    fun ensureService(): Boolean = try {
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        true
    } catch (t: Throwable) {
        Log.w("SD", "FGS start refused", t)
        false
    }

    fun requestBuilder(e: DownloadEntity, url: String): Request.Builder {
        val b = Request.Builder().url(url)
        val ua = e.userAgent?.takeIf { it.isNotBlank() } ?: settings.value.userAgent.ifBlank { DEFAULT_UA }
        b.header("User-Agent", ua)
        b.header("Accept-Encoding", "identity")
        e.referer?.takeIf { it.isNotBlank() }?.let { b.header("Referer", it) }
        e.cookies?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
        parseHeaders(e.headers).forEach { (k, v) -> runCatching { b.header(k, v) } }
        return b
    }

    // ---- public API used by UI, notifications, tile and widget ----

    suspend fun add(r: AddRequest): Long {
        val s = settings.value
        val conns = if (r.connections in 1..16) r.connections else effectiveParts(s)
        val guessName = r.fileName.trim()
        val e = DownloadEntity(
            url = r.url.trim(),
            fileName = guessName,
            category = if (guessName.isNotBlank()) FileTypes.categoryForName(guessName) else FileTypes.categoryForName(FileTypes.nameFromUrl(r.url) ?: ""),
            headers = r.headers,
            cookies = r.cookies,
            userAgent = r.userAgent,
            referer = r.referer,
            maxConnections = if (isLowRam) conns.coerceAtMost(4) else conns,
            sequential = r.sequential,
            priority = r.priority,
            checksumType = r.checksumType,
            expectedChecksum = r.expectedChecksum?.trim()?.takeIf { it.isNotEmpty() },
            status = if (r.startPaused) Status.PAUSED else Status.QUEUED,
            log = logLine("Added"),
        )
        val id = dao.insert(e)
        if (!r.startPaused) { ensureService(); kick() }
        return id
    }

    fun pause(id: Long) {
        scope.launch {
            val t = tasks[id]
            if (t != null) t.stop(DownloadTask.STOP_PAUSE)
            else {
                val e = dao.get(id) ?: return@launch
                if (e.status == Status.QUEUED || e.status == Status.WAITING) dao.setStatus(id, Status.PAUSED, null)
            }
        }
    }

    fun resume(id: Long) {
        scope.launch {
            val e = dao.get(id) ?: return@launch
            if (e.status == Status.COMPLETED || tasks.containsKey(id)) return@launch
            dao.update(e.copy(status = Status.QUEUED, error = null))
            notifier.cancelProgress(id)
            ensureService()
            kick()
        }
    }

    fun cancel(id: Long) {
        scope.launch {
            val t = tasks[id]
            if (t != null) t.stop(DownloadTask.STOP_CANCEL) else dao.setStatus(id, Status.FAILED, "Cancelled")
            notifier.cancelProgress(id)
        }
    }

    fun pauseAll() { tasks.keys.forEach { pause(it) }; scope.launch { dao.queued().forEach { dao.setStatus(it.id, Status.PAUSED, null) } } }

    fun resumeAll() {
        scope.launch {
            dao.all().filter { it.status == Status.PAUSED || it.status == Status.FAILED }.forEach {
                dao.update(it.copy(status = Status.QUEUED, error = null))
            }
            ensureService()
            kick()
        }
    }

    suspend fun delete(id: Long, withFile: Boolean) {
        tasks[id]?.let { it.stop(DownloadTask.STOP_DELETE); it.job?.join() }
        val e = dao.get(id) ?: return
        if (withFile) e.outputUri?.let { output.delete(Uri.parse(it)) }
        else if (e.status != Status.COMPLETED) e.outputUri?.let { output.delete(Uri.parse(it)) }
        dao.clearSegments(id)
        dao.delete(id)
        notifier.cancelProgress(id)
        notifier.cancelDone(id)
    }

    fun setPriority(id: Long, p: Int) { scope.launch { dao.setPriority(id, p); kick() } }

    /** Moves an item up (-1) or down (+1) in the queue order. */
    fun move(id: Long, dir: Int) {
        scope.launch {
            val list = dao.all().filter { it.status != Status.COMPLETED }.sortedWith(compareByDescending<DownloadEntity> { it.priority }.thenBy { it.queueOrder })
            val i = list.indexOfFirst { it.id == id }
            val j = i + dir
            if (i < 0 || j !in list.indices) return@launch
            val a = list[i]; val b = list[j]
            val ao = a.queueOrder; val bo = if (b.queueOrder == ao) ao + dir else b.queueOrder
            dao.setOrder(a.id, bo); dao.setOrder(b.id, ao)
            kick()
        }
    }

    suspend fun makePrivate(id: Long): Boolean {
        val e = dao.get(id) ?: return false
        if (e.status != Status.COMPLETED || e.isPrivate) return false
        val src = e.outputUri ?: return false
        val newUri = output.moveToPrivate(Uri.parse(src), e.fileName, e.category) ?: return false
        dao.update(e.copy(outputUri = newUri.toString(), isPrivate = true, savedIn = output.privateDir().absolutePath + "/" + e.category))
        return true
    }

    /** Saves bytes captured in the browser (blob: or data: URLs) straight to a finished file. */
    suspend fun saveBytes(bytes: ByteArray, mime: String?, suggestedName: String?, sourceUrl: String, referer: String?): Long? {
        val r = FileTypes.resolve(null, mime, sourceUrl, bytes, minOf(bytes.size, 40000), suggestedName)
        val created = output.create(r.name, r.mime, r.category, settings.value.conflictPolicy, false) ?: return null
        output.openOutput(created.uri)?.use { it.write(bytes) } ?: return null
        output.finalize(created.uri)
        val e = DownloadEntity(
            url = sourceUrl.take(2000), fileName = created.name, mimeType = r.mime, category = r.category,
            totalBytes = bytes.size.toLong(), downloadedBytes = bytes.size.toLong(), status = Status.COMPLETED,
            outputUri = created.uri.toString(), savedIn = created.location, referer = referer,
            completedAt = System.currentTimeMillis(), log = logLine("Saved from page data (${Fmt.bytes(bytes.size.toLong())})"),
        )
        val id = dao.insert(e)
        notifier.completed(e.copy(id = id))
        return id
    }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

        fun parseHeaders(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.filter { it.first.isNotEmpty() }

        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        fun logLine(msg: String): String = "[${synchronized(fmt) { fmt.format(Date()) }}] $msg\n"
    }
}
