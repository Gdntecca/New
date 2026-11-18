// Super Downloader — Created by Tez
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.superdownloader.app.ui

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superdownloader.app.BuildConfig
import com.superdownloader.app.R
import com.superdownloader.app.data.Status
import com.superdownloader.app.engine.FileTypes
import com.superdownloader.app.engine.Fmt

@Composable
private fun SimpleTop(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { SimpleTop("About", onBack) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(112.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF0B0B0B)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(112.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Super Downloader", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Text("Created by Tez", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            Text(
                "A free, open-source download manager for everyone. Multi-connection downloads, a built-in browser, " +
                    "scheduling, categories and more. Released under the MIT License.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StatsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val all by vm.downloads.collectAsStateWithLifecycle()
    val done = all.filter { it.status == Status.COMPLETED }
    val totalBytes = done.sumOf { it.totalBytes.coerceAtLeast(0) }
    val totalMs = done.sumOf { it.elapsedMs }
    val avg = if (totalMs > 0) totalBytes * 1000 / totalMs else 0L
    val peak = all.maxOfOrNull { it.peakSpeed } ?: 0L
    val byCat = done.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.totalBytes.coerceAtLeast(0) } }
    val maxCat = (byCat.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { SimpleTop("Statistics", onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    listOf(
                        "Total downloaded" to Fmt.bytes(totalBytes),
                        "Completed files" to done.size.toString(),
                        "In list" to all.size.toString(),
                        "Average speed" to Fmt.speed(avg),
                        "Peak speed" to Fmt.speed(peak),
                        "Time downloading" to Fmt.duration(totalMs / 1000),
                    ).forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("By category", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            FileTypes.categories.forEach { c ->
                val b = byCat[c] ?: 0L
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(categoryIcon(c), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row { Text(c, Modifier.weight(1f)); Text(Fmt.bytes(b), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(b.toFloat() / maxCat).fillMaxHeight().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PrivateScreen(vm: MainViewModel, onBack: () -> Unit, push: (Screen) -> Unit) {
    val all by vm.downloads.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val items = all.filter { it.isPrivate }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { SimpleTop("Private folder", onBack) }) { pad ->
        if (items.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Nothing here yet")
                Text("Use “Move to private folder” on a finished download. Private files are kept inside the app and hidden from other apps.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(Modifier.padding(pad)) {
                items(items, key = { it.id }) { e -> DownloadRow(vm, e, live[e.id], onOpen = { push(Screen.Detail(e.id)) }, push = push) }
            }
        }
    }
}

/** Built-in player for finished files and for sequential downloads still in progress. */
@Composable
fun PlayerScreen(vm: MainViewModel, id: Long, onBack: () -> Unit) {
    val flow = remember(id) { vm.observe(id) }
    val e by flow.collectAsState(initial = null)
    Scaffold(containerColor = Color.Black, topBar = { SimpleTop(e?.fileName ?: "Player", onBack) }) { pad ->
        val raw = e?.outputUri
        if (raw == null) {
            Box(Modifier.padding(pad).fillMaxSize())
        } else {
            val uri: Uri = remember(raw) { vm.engine.output.shareUri(Uri.parse(raw)) }
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val mc = MediaController(ctx)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        setVideoURI(uri)
                        setOnPreparedListener { start() }
                        setOnErrorListener { _, _, _ -> vm.toast.value = "Can't play this file (yet)"; true }
                    }
                },
                modifier = Modifier.padding(pad).fillMaxSize(),
                onRelease = { it.stopPlayback() },
            )
        }
    }
}
