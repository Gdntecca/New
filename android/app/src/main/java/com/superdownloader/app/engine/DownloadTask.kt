package com.superdownloader.app.engine

import android.net.Uri
import android.os.SystemClock
import com.superdownloader.app.data.DownloadEntity
import com.superdownloader.app.data.SegmentEntity
import com.superdownloader.app.data.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One running download. Pieces are fetched in parallel with HTTP Range requests and written
 * straight into the final file at their offsets (no temp files, no merge step). When a
 * connection finishes its piece it takes over half of the largest remaining piece, so no
 * connection idles near the end.
 */
class DownloadTask(private val engine: DownloadEngine, val id: Long) {

    companion object {
        const val STOP_NONE = 0
        const val STOP_PAUSE = 1
        const val STOP_WAIT = 2
        const val STOP_DELETE = 3
        const val STOP_CANCEL = 4
        private const val BUF = 64 * 1024
        private const val MIN_SPLIT = 2L * 1024 * 1024
        private const val MIN_PART = 512L * 1024
        private const val SEQ_CHUNK = 2L * 1024 * 1024
    }

    private class Seg(val start: Long, @Volatile var end: Long, @Volatile var downloaded: Long) {
        @Volatile var active = false
        var lastSample = 0L
        var speed = 0L
        var conn = 0
        fun pos() = start + downloaded
        fun remaining(): Long = if (end < 0) Long.MAX_VALUE else end - pos() + 1
    }

    var job: Job? = null
    @Volatile var stopReason = STOP_NONE
        private set
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    private val lock = Any()
    private val segs = ArrayList<Seg>()
    private var supportsRange = false
    private var total = -1L
    private var sequential = false
    private var cursor = 0L
    private var fetchUrl = ""
    private val limiter = SpeedLimiter()
    private lateinit var handle: WriteHandle
    private lateinit var entity: DownloadEntity
    private val activeWorkers = AtomicInteger(0)
    private val connCounter = AtomicInteger(0)
    private var peak = 0L
    private var baseElapsed = 0L
    private var sessionStart = 0L
    private val logBuf = StringBuilder()

    fun stop(reason: Int) {
        if (stopReason == STOP_NONE) stopReason = reason
        calls.forEach { runCatching { it.cancel() } }
        job?.cancel()
    }

    private fun log(msg: String) { synchronized(logBuf) { logBuf.append(DownloadEngine.logLine(msg)) } }

    private suspend fun flushLog() {
        val add = synchronized(logBuf) { val s = logBuf.toString(); logBuf.setLength(0); s }
        if (add.isEmpty()) return
        val cur = engine.dao.get(id)?.log ?: ""
        engine.dao.setLog(id, (cur + add).takeLast(8000))
    }

    private fun downloadedNow(): Long = synchronized(lock) { segs.sumOf { it.downloaded } }

    suspend fun run() {
        val dao = engine.dao
        var e = dao.get(id) ?: return
        val s = engine.settings.value
        e = e.copy(status = Status.DOWNLOADING, error = null)
        dao.update(e)
        baseElapsed = e.elapsedMs
        peak = e.peakSpeed
        sessionStart = SystemClock.elapsedRealtime()
        log("Started")
        try {
            if (e.outputUri == null) {
                e = prepare(e) ?: return
            }
            entity = e
            supportsRange = e.supportsRange
            total = e.totalBytes
            sequential = e.sequential && supportsRange && total > 0
            fetchUrl = e.url
            limiter.bytesPerSec = e.speedLimitKbps * 1024L
            handle = engine.output.openWrite(Uri.parse(e.outputUri))
            try {
                loadSegments(e)
                if (total > 0 && downloadedNow() == 0L) handle.setLength(total)
                val workers = if (supportsRange && total > 0) e.maxConnections.coerceIn(1, 16) else 1
                log("Using $workers connection(s)" + if (supportsRange) "" else " (server has no range support)")
                coroutineScope {
                    repeat(workers) { spawnWorker(this) }
                    launch { tick(this@coroutineScope) }
                }
            } finally {
                handle.close()
            }
            if (stopReason != STOP_NONE) throw CancellationException("stopped")
            val done = downloadedNow()
            if (total > 0 && done < total) throw IOException("Incomplete download ($done of $total bytes)")
            if (total <= 0) total = done
            finish(e)
        } catch (c: CancellationException) {
            withContext(NonCancellable) { onStopped() }
            throw c
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                if (stopReason != STOP_NONE) onStopped() else onFailed(t)
            }
        }
    }

    /** First run: probe the server, pick the real name/type, create the output and the pieces. */
    private suspend fun prepare(e0: DownloadEntity): DownloadEntity? {
        val dao = engine.dao
        val s = engine.settings.value
        log("Connecting to ${Uri.parse(e0.url).host}")
        val probe = probe(e0)
        val r = FileTypes.resolve(probe.cd, probe.ct, probe.finalUrl, probe.head, probe.headLen, e0.fileName.ifBlank { null })
        log("Server: ${if (probe.range) "supports ranges" else "single stream"}, size ${if (probe.total > 0) Fmt.bytes(probe.total) else "unknown"}, type ${r.mime}")
        val warning = if (probe.total > 0 && engine.output.freeBytes() < probe.total)
            "Low storage: ${Fmt.bytes(engine.output.freeBytes())} free, file is ${Fmt.bytes(probe.total)}" else null
        if (warning != null) log(warning)
        val created = engine.output.create(r.name, r.mime, r.category, s.conflictPolicy, e0.isPrivate)
        if (created == null) {
            log("Skipped: a file with this name already exists")
            dao.update(e0.copy(status = Status.FAILED, error = "Skipped: file already exists", fileName = r.name))
            flushLog()
            return null
        }
        log("Saving as ${created.name}")
        val e = e0.copy(
            fileName = created.name, mimeType = r.mime, category = r.category,
            totalBytes = probe.total, supportsRange = probe.range,
            outputUri = created.uri.toString(), savedIn = created.location, warning = warning,
        )
        dao.update(e)
        val list = ArrayList<SegmentEntity>()
        if (probe.range && probe.total > 0 && !e.sequential) {
            val n = minOf(e.maxConnections.toLong(), maxOf(1L, probe.total / MIN_PART)).toInt().coerceAtLeast(1)
            val size = probe.total / n
            for (i in 0 until n) {
                val st = i * size
                val en = if (i == n - 1) probe.total - 1 else st + size - 1
                list.add(SegmentEntity(downloadId = id, start = st, end = en, downloaded = 0))
            }
        }
        dao.replaceSegments(id, list)
        return e
    }

    private data class Probe(val finalUrl: String, val range: Boolean, val total: Long, val cd: String?, val ct: String?, val head: ByteArray, val headLen: Int)

    private suspend fun probe(e: DownloadEntity): Probe = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = engine.requestBuilder(e, e.url).header("Range", "bytes=0-").build()
        val call = engine.client.newCall(req)
        calls.add(call)
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw HttpStatusException(r.code)
                val body = r.body ?: throw IOException("Empty response")
                var range = false
                var size = -1L
                if (r.code == 206) {
                    range = true
                    size = r.header("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
                } else {
                    size = body.contentLength()
                }
                val head = ByteArray(40000)
                var n = 0
                val src = body.source()
                while (n < head.size) {
                    val k = src.read(head, n, head.size - n)
                    if (k == -1) break
                    n += k
                }
                Probe(r.request.url.toString(), range, size, r.header("Content-Disposition"), r.header("Content-Type"), head, n)
            }
        } finally { calls.remove(call) }
    }

    private suspend fun loadSegments(e: DownloadEntity) {
        val stored = engine.dao.segments(id)
        synchronized(lock) {
            segs.clear()
            if (!supportsRange || total <= 0) {
                // Without ranges a stream can only restart from zero.
                segs.add(Seg(0, if (total > 0) total - 1 else -1, 0))
            } else {
                stored.forEach { segs.add(Seg(it.start, it.end, it.downloaded)) }
                cursor = if (segs.isEmpty()) 0 else segs.maxOf { it.end + 1 }
                if (!sequential && segs.isEmpty()) segs.add(Seg(0, total - 1, 0))
            }
        }
        if (!supportsRange || total <= 0) handle.setLength(0)
    }

    private fun spawnWorker(scope: CoroutineScope) {
        activeWorkers.incrementAndGet()
        scope.launch {
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val seg = nextSegment() ?: break
                    try { downloadWithRetry(seg) } finally { synchronized(lock) { seg.active = false; seg.speed = 0 } }
                }
            } finally { activeWorkers.decrementAndGet() }
        }
    }

    private fun nextSegment(): Seg? {
      synchronized(lock) {
        segs.filter { !it.active && it.remaining() > 0 }.minByOrNull { it.start }?.let {
            it.active = true; it.conn = connCounter.incrementAndGet(); return it
        }
        if (sequential && cursor < total) {
            val end = minOf(cursor + SEQ_CHUNK - 1, total - 1)
            val s = Seg(cursor, end, 0).also { it.active = true; it.conn = connCounter.incrementAndGet() }
            segs.add(s)
            cursor = end + 1
            return s
        }
        if (!supportsRange || total <= 0) return null
        val victim = segs.filter { it.active && it.end >= 0 }.maxByOrNull { it.remaining() } ?: return null
        if (victim.remaining() < MIN_SPLIT) return null
        val mid = victim.pos() + victim.remaining() / 2
        val ns = Seg(mid, victim.end, 0).also { it.active = true; it.conn = connCounter.incrementAndGet() }
        victim.end = mid - 1
        segs.add(ns)
        return ns
      }
    }

    private suspend fun downloadWithRetry(seg: Seg) {
        var attempt = 0
        while (true) {
            if (seg.end >= 0 && seg.remaining() <= 0) return
            try {
                fetch(seg)
                return
            } catch (c: CancellationException) {
                throw c
            } catch (e: IOException) {
                if (stopReason != STOP_NONE) throw CancellationException("stopped")
                if (e is HttpStatusException && !e.retryable) throw e
                attempt++
                val s = engine.settings.value
                if (attempt > s.retryCount) throw e
                val wait = minOf(60_000L, s.retryDelaySec.coerceAtLeast(1) * 1000L * (1L shl (attempt - 1).coerceAtMost(5)))
                log("Connection ${seg.conn}: ${e.message ?: e.javaClass.simpleName}; retry $attempt in ${wait / 1000}s")
                delay(wait)
                while (!engine.network.state.value.connected) delay(1000)
                if (!supportsRange) {
                    synchronized(lock) { seg.downloaded = 0 }
                    handle.setLength(0)
                }
            }
        }
    }

    private suspend fun fetch(seg: Seg) {
        val from = seg.pos()
        val rb = engine.requestBuilder(entity, fetchUrl)
        if (supportsRange) {
            val end = seg.end
            rb.header("Range", if (end >= 0) "bytes=$from-$end" else "bytes=$from-")
        }
        val req = rb.build()
        engine.hostSemaphore(req.url.host).withPermit {
            val call = engine.client.newCall(req)
            calls.add(call)
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                    if (supportsRange && resp.code != 206 && from > 0) {
                        throw HttpStatusException(resp.code, "Server stopped honouring ranges")
                    }
                    val src = (resp.body ?: throw IOException("Empty body")).source()
                    val buf = ByteArray(BUF)
                    val bb = ByteBuffer.wrap(buf)
                    while (true) {
                        if (stopReason != STOP_NONE) throw CancellationException("stopped")
                        val want = synchronized(lock) {
                            if (seg.end >= 0) minOf(BUF.toLong(), seg.end - seg.pos() + 1) else BUF.toLong()
                        }.toInt()
                        if (want <= 0) break
                        val n = src.read(buf, 0, want)
                        if (n == -1) {
                            if (seg.end >= 0) throw IOException("Connection closed early")
                            break
                        }
                        val allowed = synchronized(lock) {
                            if (seg.end >= 0) minOf(n.toLong(), seg.end - seg.pos() + 1).toInt() else n
                        }
                        if (allowed <= 0) break
                        engine.globalLimiter.acquire(allowed)
                        limiter.acquire(allowed)
                        val pos = seg.pos()
                        bb.clear(); bb.limit(allowed)
                        var off = 0
                        while (bb.hasRemaining()) off += handle.channel.write(bb, pos + off)
                        synchronized(lock) { seg.downloaded += allowed }
                        if (allowed < n) break
                    }
                }
            } finally { calls.remove(call) }
        }
    }

    private suspend fun tick(scope: CoroutineScope) {
        var lastBytes = downloadedNow()
        var lastT = SystemClock.elapsedRealtime()
        var lastPersist = lastT
        var lastHistory = lastT
        var lastAdapt = lastT
        var adaptBaseline = 0.0
        var adaptStopped = engine.isLowRam
        var ema = 0.0
        val history = ArrayDeque<Float>()
        synchronized(lock) { segs.forEach { it.lastSample = it.downloaded } }
        while (activeWorkers.get() > 0) {
            delay(250)
            val now = SystemClock.elapsedRealtime()
            val bytes = downloadedNow()
            val dt = ((now - lastT) / 1000.0).coerceAtLeast(0.05)
            val inst = (bytes - lastBytes).coerceAtLeast(0) / dt
            ema = if (ema == 0.0) inst else ema * 0.75 + inst * 0.25
            lastBytes = bytes; lastT = now
            if (ema > peak) peak = ema.toLong()
            val segInfo = synchronized(lock) {
                segs.map { sg ->
                    val d = sg.downloaded - sg.lastSample
                    sg.lastSample = sg.downloaded
                    sg.speed = if (sg.active) ((sg.speed * 0.75) + (d / dt) * 0.25).toLong() else 0
                    SegInfo(sg.start, if (sg.end >= 0) sg.end else maxOf(sg.pos() - 1, sg.start), sg.downloaded, sg.active, sg.speed, sg.conn)
                }
            }
            val elapsed = baseElapsed + (now - sessionStart)
            if (now - lastHistory >= 1000) {
                history.addLast(ema.toFloat()); if (history.size > 90) history.removeFirst(); lastHistory = now
            }
            val eta = if (total > 0 && ema > 1) ((total - bytes) / ema).toLong() else -1L
            val avg = if (elapsed > 0) (bytes * 1000.0 / elapsed).toLong() else 0L
            engine.publishLive(id, LiveStats(bytes, total, ema.toLong(), avg, peak, elapsed, eta,
                segInfo.count { it.active }, segInfo, history.toList()))
            if (now - lastPersist >= 2000) { persist(); flushLog(); lastPersist = now }

            // Adaptive connections: add one while total throughput keeps rising.
            val s = engine.settings.value
            if (s.adaptive && !adaptStopped && supportsRange && total > 0 && now - lastAdapt >= 3000) {
                lastAdapt = now
                val cap = engine.effectivePerServer(s)
                val remaining = total - bytes
                if (activeWorkers.get() < cap && remaining > 4 * MIN_SPLIT) {
                    if (adaptBaseline == 0.0 || ema > adaptBaseline * 1.10) {
                        adaptBaseline = ema
                        spawnWorker(scope)
                        log("Adaptive: added a connection (${activeWorkers.get()} total)")
                    } else adaptStopped = true
                }
            }
        }
    }

    private suspend fun persist() {
        val snap = synchronized(lock) { segs.map { SegmentEntity(downloadId = id, start = it.start, end = it.end, downloaded = it.downloaded) } }
        val elapsed = baseElapsed + (SystemClock.elapsedRealtime() - sessionStart)
        engine.dao.setProgress(id, snap.sumOf { it.downloaded }, elapsed, peak)
        if (supportsRange && total > 0) engine.dao.replaceSegments(id, snap)
    }

    private suspend fun finish(e: DownloadEntity) {
        val dao = engine.dao
        val s = engine.settings.value
        val uri = Uri.parse(e.outputUri)
        engine.output.finalize(uri)
        val elapsed = baseElapsed + (SystemClock.elapsedRealtime() - sessionStart)
        log("Completed: ${Fmt.bytes(total)} in ${Fmt.duration(elapsed / 1000)}")
        var checksum: String? = null
        if (!e.expectedChecksum.isNullOrBlank()) {
            val type = e.checksumType ?: "SHA-256"
            val h = Checksums.compute(engine.output, uri, type)
            checksum = if (h != null && h.equals(e.expectedChecksum.trim(), ignoreCase = true)) "$type OK" else "$type MISMATCH: ${h ?: "unreadable"}"
            log("Checksum: $checksum")
        }
        var done = (dao.get(id) ?: e).copy(
            status = Status.COMPLETED, totalBytes = total, downloadedBytes = total, completedAt = System.currentTimeMillis(),
            elapsedMs = elapsed, peakSpeed = peak, checksumResult = checksum, error = null,
        )
        dao.update(done)
        dao.clearSegments(id)
        if (s.autoExtractZip && FileTypes.extOf(e.fileName) == "zip") {
            val n = runCatching { ZipExtractor.extract(engine.output, uri, e.fileName, e.isPrivate, s.conflictPolicy) }.getOrDefault(-1)
            log(if (n >= 0) "Extracted $n file(s)" else "ZIP extraction failed")
        }
        flushLog()
        done = dao.get(id) ?: done
        engine.notifier.cancelProgress(id)
        engine.notifier.completed(done)
    }

    private suspend fun onStopped() {
        if (stopReason == STOP_DELETE) return
        runCatching { persist() }
        val st = when (stopReason) {
            STOP_WAIT -> Status.WAITING
            STOP_CANCEL -> Status.FAILED
            else -> Status.PAUSED
        }
        log(when (st) { Status.WAITING -> "Waiting (${engine.blockedReason.value ?: "blocked"})"; Status.FAILED -> "Cancelled"; else -> "Paused" })
        engine.dao.setStatus(id, st, if (st == Status.FAILED) "Cancelled" else null)
        flushLog()
        engine.notifier.cancelProgress(id)
        if (st == Status.PAUSED) engine.dao.get(id)?.let { engine.notifier.paused(it) }
    }

    private suspend fun onFailed(t: Throwable) {
        runCatching { persist() }
        val net = engine.network.state.value.connected
        val msg = t.message ?: t.javaClass.simpleName
        if (!net && t is IOException) {
            log("Network lost; will resume automatically")
            engine.dao.setStatus(id, Status.WAITING, null)
        } else {
            log("Failed: $msg")
            engine.dao.setStatus(id, Status.FAILED, msg)
            engine.dao.get(id)?.let { engine.notifier.failed(it) }
        }
        flushLog()
        engine.notifier.cancelProgress(id)
    }
}
