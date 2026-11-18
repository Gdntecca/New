package com.superdownloader.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superdownloader.app.SuperApp
import com.superdownloader.app.data.DownloadEntity
import com.superdownloader.app.data.SegmentEntity
import com.superdownloader.app.data.SettingsState
import com.superdownloader.app.data.Status
import com.superdownloader.app.engine.AddRequest
import com.superdownloader.app.engine.Checksums
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AddPrefill(
    val urls: String,
    val referer: String? = null,
    val cookies: String? = null,
    val userAgent: String? = null,
    val fileName: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val sa = app as SuperApp
    val engine = sa.engine
    val downloads: StateFlow<List<DownloadEntity>> =
        sa.db.dao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val live = engine.live
    val settings: StateFlow<SettingsState> = sa.settings.state
    val blocked = engine.blockedReason
    val prefill = MutableStateFlow<AddPrefill?>(null)
    val clipboardOffer = MutableStateFlow<String?>(null)
    val toast = MutableStateFlow<String?>(null)
    val privateUnlocked = MutableStateFlow(false)
    private var lastClipboard: String? = null

    fun observe(id: Long): Flow<DownloadEntity?> = sa.db.dao().observe(id)

    suspend fun segments(id: Long): List<SegmentEntity> = sa.db.dao().segments(id)

    fun updateSettings(f: (SettingsState) -> SettingsState) = sa.settings.update(f)

    fun offerClipboard(text: String?) {
        if (!settings.value.clipboardMonitor || text.isNullOrBlank()) return
        val url = com.superdownloader.app.engine.extractUrls(text).firstOrNull() ?: return
        if (url == lastClipboard) return
        lastClipboard = url
        if (downloads.value.any { it.url == url }) return
        clipboardOffer.value = url
    }

    /** Returns the URLs already in the list so the UI can ask before adding them again. */
    fun duplicates(urls: List<String>): List<String> {
        val existing = downloads.value.map { it.url }.toHashSet()
        return urls.filter { it in existing }
    }

    fun addAll(requests: List<AddRequest>) {
        viewModelScope.launch {
            requests.forEach { engine.add(it) }
            toast.value = if (requests.size == 1) "Download added" else "${requests.size} downloads added"
        }
    }

    fun pause(id: Long) = engine.pause(id)
    fun resume(id: Long) = engine.resume(id)
    fun setPriority(id: Long, p: Int) = engine.setPriority(id, p)
    fun move(id: Long, dir: Int) = engine.move(id, dir)
    fun pauseAll() = engine.pauseAll()
    fun resumeAll() = engine.resumeAll()

    fun delete(id: Long, withFile: Boolean) {
        viewModelScope.launch { engine.delete(id, withFile) }
    }

    fun makePrivate(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            toast.value = if (engine.makePrivate(id)) "Moved to private folder" else "Could not move file"
        }
    }

    fun verify(e: DownloadEntity, type: String, onResult: (String) -> Unit) {
        val uri = e.outputUri ?: return
        viewModelScope.launch {
            val h = withContext(Dispatchers.IO) { Checksums.compute(engine.output, Uri.parse(uri), type) }
            onResult(h ?: "Could not read file")
        }
    }

    fun exportList(target: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val arr = JSONArray()
            sa.db.dao().all().forEach { e ->
                arr.put(JSONObject().apply {
                    put("url", e.url); put("fileName", e.fileName); put("status", Status.label(e.status))
                    put("totalBytes", e.totalBytes); put("headers", e.headers); put("referer", e.referer ?: "")
                    put("userAgent", e.userAgent ?: ""); put("priority", e.priority); put("sequential", e.sequential)
                    put("connections", e.maxConnections); put("createdAt", e.createdAt)
                })
            }
            val ok = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(target, "w")!!.use {
                    it.write(JSONObject().put("app", "Super Downloader").put("version", 1).put("downloads", arr).toString(2).toByteArray())
                }
            }.isSuccess
            toast.value = if (ok) "Exported ${arr.length()} downloads" else "Export failed"
        }
    }

    fun importList(source: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val n = runCatching {
                val text = getApplication<Application>().contentResolver.openInputStream(source)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val arr = JSONObject(text).getJSONArray("downloads")
                val existing = sa.db.dao().all().map { it.url }.toHashSet()
                var added = 0
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val url = o.optString("url")
                    if (url.isBlank() || url in existing) continue
                    engine.add(AddRequest(
                        url = url, fileName = o.optString("fileName"), headers = o.optString("headers"),
                        referer = o.optString("referer").ifBlank { null }, userAgent = o.optString("userAgent").ifBlank { null },
                        connections = o.optInt("connections", 0), sequential = o.optBoolean("sequential"),
                        priority = o.optInt("priority", 1), startPaused = true,
                    ))
                    added++
                }
                added
            }.getOrDefault(-1)
            toast.value = if (n >= 0) "Imported $n downloads (paused)" else "Import failed: not a Super Downloader list"
        }
    }
}
