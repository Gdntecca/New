// Super Downloader — Created by Tez
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.superdownloader.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superdownloader.app.data.DownloadEntity
import com.superdownloader.app.data.Priority
import com.superdownloader.app.data.Status
import com.superdownloader.app.engine.Fmt
import com.superdownloader.app.engine.LiveStats
import com.superdownloader.app.engine.SegInfo
import java.text.DateFormat
import java.util.Date

@Composable
fun DetailScreen(vm: MainViewModel, id: Long, onBack: () -> Unit, push: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val flow = remember(id) { vm.observe(id) }
    val entity by flow.collectAsState(initial = null)
    val liveMap by vm.live.collectAsStateWithLifecycle()
    val e = entity
    var tab by rememberSaveable { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var storedSegs by remember { mutableStateOf<List<SegInfo>>(emptyList()) }
    LaunchedEffect(id, e?.status) {
        storedSegs = vm.segments(id).mapIndexed { i, s -> SegInfo(s.start, s.end, s.downloaded, false, 0, i + 1) }
    }
    if (e == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val ls = liveMap[id]
    val segs = ls?.segments ?: storedSegs

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(Status.label(e.status)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    when (e.status) {
                        Status.DOWNLOADING, Status.QUEUED, Status.WAITING -> IconButton({ vm.pause(id) }) { Icon(Icons.Filled.Pause, "Pause") }
                        Status.PAUSED -> IconButton({ vm.resume(id) }) { Icon(Icons.Filled.PlayArrow, "Resume") }
                        Status.FAILED -> IconButton({ vm.resume(id) }) { Icon(Icons.Filled.Refresh, "Retry") }
                    }
                    if (e.status == Status.COMPLETED) IconButton({ shareFile(ctx, vm, e) }) { Icon(Icons.Filled.Share, "Share") }
                    IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.navigationBarsPadding(),
            ) {
                listOf("INFO", "CONNECTIONS", "LOG").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontWeight = FontWeight.SemiBold) })
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(categoryIcon(e.category), null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(14.dp))
                Text(e.fileName.ifBlank { e.url }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            when (tab) {
                0 -> InfoTab(vm, e, ls, segs, push)
                1 -> ConnectionsTab(e, ls, segs)
                else -> Text(
                    e.log.ifBlank { "No log entries yet." },
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete) DeleteDialog(e, { confirmDelete = false }) { withFile -> vm.delete(id, withFile); onBack() }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        trailing?.invoke()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun InfoTab(vm: MainViewModel, e: DownloadEntity, ls: LiveStats?, segs: List<SegInfo>, push: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val total = ls?.total ?: e.totalBytes
    val done = if (e.status == Status.COMPLETED) e.totalBytes else ls?.downloaded ?: e.downloadedBytes
    var prioMenu by remember { mutableStateOf(false) }
    var hash by remember { mutableStateOf<String?>(null) }
    val copy = { text: String ->
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", text))
        vm.toast.value = "Copied"
    }
    val openUrl = { url: String ->
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Unit
    }

    InfoRow("Downloaded", if (total > 0) "${Fmt.bytes(done)} of ${Fmt.bytes(total)} (${done * 100 / total}%)" else Fmt.bytes(done))
    InfoRow("Added at", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.createdAt)))
    InfoRow("Saved in", e.savedIn ?: "Download/SuperDownloader/${e.category}")
    e.referer?.takeIf { it.isNotBlank() }?.let { r -> InfoRow("Web page", r, onClick = { push(Screen.Browser(r)) }) }
    InfoRow("File", e.url, onClick = { openUrl(e.url) }, trailing = {
        IconButton({ copy(e.url) }) { Icon(Icons.Filled.ContentCopy, "Copy link") }
    })
    Box {
        InfoRow("Priority", Priority.label(e.priority), onClick = { prioMenu = true })
        DropdownMenu(prioMenu, { prioMenu = false }) {
            listOf(Priority.HIGH, Priority.NORMAL, Priority.LOW).forEach { p ->
                DropdownMenuItem({ Text(Priority.label(p)) }, { vm.setPriority(e.id, p); prioMenu = false })
            }
        }
    }
    e.warning?.let { InfoRow("Warning", it) }
    e.error?.let { InfoRow("Error", it) }
    e.checksumResult?.let { InfoRow("Checksum", it) }

    Spacer(Modifier.height(14.dp))
    Text("Progress", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    if (total <= 0 && e.status != Status.COMPLETED) {
        SegmentedBar(0f, total, null, indeterminate = e.status == Status.DOWNLOADING)
    } else {
        PieceMap(total, done, segs, e.status == Status.COMPLETED)
    }

    Spacer(Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            val elapsed = (ls?.elapsedMs ?: e.elapsedMs) / 1000
            val avg = ls?.avgSpeed ?: if (e.elapsedMs > 0) e.totalBytes.coerceAtLeast(0) * 1000 / e.elapsedMs else 0L
            StatGrid(
                listOf(
                    "Speed" to (ls?.let { Fmt.speed(it.speed) } ?: "—"),
                    "Average" to Fmt.speed(avg),
                    "Peak" to Fmt.speed(ls?.peakSpeed ?: e.peakSpeed),
                    "Elapsed" to Fmt.duration(elapsed),
                    "ETA" to (ls?.etaSec?.takeIf { it >= 0 }?.let { Fmt.duration(it) } ?: "—"),
                    "Connections" to "${ls?.connections ?: 0} / ${e.maxConnections}",
                ),
            )
            Spacer(Modifier.height(12.dp))
            SpeedGraph(ls?.history ?: emptyList())
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (e.status == Status.COMPLETED) {
            OutlinedButton({ openFile(ctx, vm, e) }) { Text("Open") }
            if (e.category == "Video" || e.category == "Music") OutlinedButton({ push(Screen.Player(e.id)) }) { Text("Play") }
            OutlinedButton({ openFolder(ctx) }) { Text("Folder") }
        } else if (e.sequential && (e.category == "Video" || e.category == "Music")) {
            OutlinedButton({ push(Screen.Player(e.id)) }) { Text("Preview") }
        }
    }
    if (e.status == Status.COMPLETED) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ hash = "Computing MD5…"; vm.verify(e, "MD5") { hash = "MD5: $it" } }) { Text("MD5") }
            OutlinedButton({ hash = "Computing SHA-256…"; vm.verify(e, "SHA-256") { hash = "SHA-256: $it" } }) { Text("SHA-256") }
        }
        hash?.let {
            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp).clickable { copy(it.substringAfter(": ")) })
        }
    }
}

@Composable
private fun StatGrid(items: List<Pair<String, String>>) {
    items.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            row.forEach { (k, v) ->
                Column(Modifier.weight(1f)) {
                    Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ConnectionsTab(e: DownloadEntity, ls: LiveStats?, segs: List<SegInfo>) {
    if (segs.isEmpty()) {
        Text(
            if (e.status == Status.COMPLETED) "All pieces finished." else if (!e.supportsRange && e.outputUri != null) "This server sends the file as one stream (no ranges)." else "No connections yet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }
    val total = ls?.total ?: e.totalBytes
    segs.sortedBy { it.start }.forEach { s ->
        val size = if (s.end >= s.start) s.end - s.start + 1 else 0
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(if (s.active) ConnColors[(s.conn - 1).mod(ConnColors.size)] else MaterialTheme.colorScheme.outline))
                Spacer(Modifier.width(8.dp))
                Text(if (s.active) "Connection #${s.conn}" else "Piece", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(if (s.active) Fmt.speed(s.speed) else if (size > 0 && s.downloaded >= size) "done" else "idle",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("Bytes ${s.start}–${s.end} · ${Fmt.bytes(s.downloaded)} of ${Fmt.bytes(size)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            SegmentedBar(if (size > 0) s.downloaded.toFloat() / size else 0f, total, null, false, height = 4)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
