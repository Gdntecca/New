// Super Downloader — Created by Tez
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.superdownloader.app.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.superdownloader.app.MainActivity
import com.superdownloader.app.browser.BrowserScreen
import com.superdownloader.app.data.DownloadEntity
import com.superdownloader.app.data.Priority
import com.superdownloader.app.data.Status
import com.superdownloader.app.engine.FileTypes
import com.superdownloader.app.engine.Fmt
import com.superdownloader.app.engine.LiveStats
import com.superdownloader.app.engine.extractUrls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Detail(val id: Long) : Screen
    data object Settings : Screen
    data object About : Screen
    data object Stats : Screen
    data class Browser(val url: String?) : Screen
    data class Player(val id: Long) : Screen
    data object Private : Screen
}

@Composable
fun SuperRoot(vm: MainViewModel, activity: MainActivity, openDetail: MutableStateFlow<Long?>) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = stack.last()
    val push: (Screen) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    BackHandler(enabled = stack.size > 1) { pop() }

    val od by openDetail.collectAsStateWithLifecycle()
    LaunchedEffect(od) { od?.let { push(Screen.Detail(it)); openDetail.value = null } }

    val prefill by vm.prefill.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val clip by vm.clipboardOffer.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let { snackbar.showSnackbar(it); vm.toast.value = null }
    }
    LaunchedEffect(clip) {
        val url = clip ?: return@LaunchedEffect
        val r = snackbar.showSnackbar("Link copied: ${url.take(60)}", actionLabel = "Download", withDismissAction = true)
        if (r == SnackbarResult.ActionPerformed) vm.prefill.value = AddPrefill(url)
        vm.clipboardOffer.value = null
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openPrivate = {
        if (vm.privateUnlocked.value) push(Screen.Private)
        else activity.unlockPrivate { vm.privateUnlocked.value = true; push(Screen.Private) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = current == Screen.Home,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(24.dp)) {
                    Text("Super Downloader", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Fast, free downloads", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                val item: @Composable (String, ImageVector, () -> Unit) -> Unit = { label, icon, onClick ->
                    NavigationDrawerItem(
                        label = { Text(label) }, icon = { Icon(icon, null) }, selected = false,
                        onClick = { scope.launch { drawerState.close() }; onClick() },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                item("Downloads", Icons.Filled.Download) { }
                item("Browser", Icons.Filled.Public) { push(Screen.Browser(null)) }
                item("Private folder", Icons.Filled.Lock) { openPrivate() }
                item("Statistics", Icons.Filled.BarChart) { push(Screen.Stats) }
                item("Settings", Icons.Filled.Settings) { push(Screen.Settings) }
                item("About", Icons.Filled.Info) { push(Screen.About) }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (val s = current) {
                Screen.Home -> HomeScreen(vm, onMenu = { scope.launch { drawerState.open() } }, push = push)
                is Screen.Detail -> DetailScreen(vm, s.id, onBack = pop, push = push)
                Screen.Settings -> SettingsScreen(vm, onBack = pop)
                Screen.About -> AboutScreen(onBack = pop)
                Screen.Stats -> StatsScreen(vm, onBack = pop)
                is Screen.Browser -> BrowserScreen(vm, s.url, onBack = pop)
                is Screen.Player -> PlayerScreen(vm, s.id, onBack = pop)
                Screen.Private -> PrivateScreen(vm, onBack = pop, push = push)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
        }
    }
    prefill?.let { AddDialog(vm, it, onDismiss = { vm.prefill.value = null }) }
}

// ---------------------------------------------------------------- helpers

fun openFile(ctx: Context, vm: MainViewModel, e: DownloadEntity) {
    val raw = e.outputUri ?: return
    runCatching {
        val uri = vm.engine.output.shareUri(Uri.parse(raw))
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show() }
}

fun shareFile(ctx: Context, vm: MainViewModel, e: DownloadEntity) {
    val raw = e.outputUri ?: return
    runCatching {
        val uri = vm.engine.output.shareUri(Uri.parse(raw))
        val i = Intent(Intent.ACTION_SEND).setType(e.mimeType ?: "*/*").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { Toast.makeText(ctx, "Could not share", Toast.LENGTH_SHORT).show() }
}

fun openFolder(ctx: Context) {
    runCatching { ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, "Open Files › Download › SuperDownloader", Toast.LENGTH_LONG).show() }
}

fun statusLine(e: DownloadEntity, ls: LiveStats?): String {
    val total = ls?.total ?: e.totalBytes
    val done = ls?.downloaded ?: e.downloadedBytes
    val pct = if (total > 0) "${(done * 100 / total)}%" else null
    return when (e.status) {
        Status.DOWNLOADING -> buildList {
            pct?.let { add(it) }
            add(if (total > 0) "${Fmt.bytes(done)} of ${Fmt.bytes(total)}" else Fmt.bytes(done))
            ls?.let { add(Fmt.speed(it.speed)); if (it.etaSec >= 0) add(Fmt.duration(it.etaSec)) }
        }.joinToString(" · ")
        Status.COMPLETED -> "${Fmt.bytes(e.totalBytes)} · Completed"
        Status.FAILED -> "Error: ${e.error ?: "unknown"}"
        else -> listOfNotNull(Status.label(e.status), pct, if (total > 0) "${Fmt.bytes(done)} of ${Fmt.bytes(total)}" else null).joinToString(" · ")
    }
}

// ---------------------------------------------------------------- home

@Composable
fun HomeScreen(vm: MainViewModel, onMenu: () -> Unit, push: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val all by vm.downloads.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var category by rememberSaveable { mutableStateOf("All") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    val qr = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents ?: return@rememberLauncherForActivityResult
        val urls = extractUrls(text)
        if (urls.isNotEmpty()) vm.prefill.value = AddPrefill(urls.joinToString("\n")) else vm.toast.value = "No link in that QR code"
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportList) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importList) }

    val visible = remember(all, tab, category, query, sort) {
        all.asSequence()
            .filter { !it.isPrivate }
            .filter {
                when (tab) {
                    1 -> it.status != Status.COMPLETED
                    2 -> it.status == Status.COMPLETED
                    else -> true
                }
            }
            .filter { category == "All" || it.category == category }
            .filter { query.isBlank() || it.fileName.contains(query, true) || it.url.contains(query, true) }
            .let { seq ->
                when (sort) {
                    1 -> seq.sortedBy { it.fileName.lowercase() }
                    2 -> seq.sortedByDescending { it.totalBytes }
                    3 -> seq.sortedByDescending { it.progress }
                    4 -> seq.sortedWith(compareByDescending<DownloadEntity> { it.priority }.thenBy { it.queueOrder })
                    else -> seq.sortedByDescending { it.createdAt }
                }
            }.toList()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (searching) {
                TopAppBar(
                    title = {
                        TextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search downloads") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    navigationIcon = { IconButton({ searching = false; query = "" }) { Icon(Icons.Filled.Close, "Close search") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            } else {
                TopAppBar(
                    title = { Text("Super Downloader", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { IconButton(onMenu) { Icon(Icons.Filled.Menu, "Menu") } },
                    actions = {
                        IconButton({ searching = true }) { Icon(Icons.Filled.Search, "Search") }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Resume all") }, { menu = false; vm.resumeAll() })
                                DropdownMenuItem({ Text("Pause all") }, { menu = false; vm.pauseAll() })
                                DropdownMenuItem({ Text("Add several links") }, { menu = false; vm.prefill.value = AddPrefill("") })
                                DropdownMenuItem({ Text("Scan QR code") }, {
                                    menu = false
                                    qr.launch(ScanOptions().setPrompt("Point at a QR code with a link").setBeepEnabled(false).setOrientationLocked(false))
                                })
                                DropdownMenuItem({ Text("Sort by…") }, { menu = false; sortMenu = true })
                                DropdownMenuItem({ Text("Open folder") }, { menu = false; openFolder(ctx) })
                                DropdownMenuItem({ Text("Export list") }, { menu = false; exporter.launch("superdownloader-list.json") })
                                DropdownMenuItem({ Text("Import list") }, { menu = false; importer.launch(arrayOf("application/json", "*/*")) })
                            }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                listOf("Date added", "Name", "Size", "Progress", "Queue order").forEachIndexed { i, label ->
                                    DropdownMenuItem({ Text((if (sort == i) "✓  " else "    ") + label) }, { sort = i; sortMenu = false })
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { vm.prefill.value = AddPrefill("") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
            ) { Icon(Icons.Filled.Add, "Add download") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                listOf("ALL", "ACTIVE", "COMPLETED").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontWeight = FontWeight.SemiBold) })
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(listOf("All") + FileTypes.categories, key = { it }) { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                }
            }
            blocked?.let {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Download, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(if (all.isEmpty()) "No downloads yet" else "Nothing here", style = MaterialTheme.typography.titleMedium)
                        Text("Tap + to paste a link, or share one to Super Downloader", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(visible, key = { it.id }) { e ->
                        DownloadRow(vm, e, live[e.id], onOpen = { push(Screen.Detail(e.id)) }, push = push)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadRow(vm: MainViewModel, e: DownloadEntity, ls: LiveStats?, onOpen: () -> Unit, push: (Screen) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val active = e.status == Status.DOWNLOADING
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(categoryIcon(e.category), null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.fileName.ifBlank { FileTypes.nameFromUrl(e.url) ?: e.url },
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                if (e.status != Status.COMPLETED) {
                    val total = ls?.total ?: e.totalBytes
                    val done = ls?.downloaded ?: e.downloadedBytes
                    SegmentedBar(
                        progress = if (total > 0) done.toFloat() / total else 0f,
                        total = total, segments = ls?.segments,
                        indeterminate = active && total <= 0,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    statusLine(e, ls), style = MaterialTheme.typography.bodySmall,
                    color = if (e.status == Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            when (e.status) {
                Status.DOWNLOADING, Status.QUEUED, Status.WAITING ->
                    IconButton({ vm.pause(e.id) }) { Icon(Icons.Filled.Pause, "Pause", tint = MaterialTheme.colorScheme.primary) }
                Status.PAUSED -> IconButton({ vm.resume(e.id) }) { Icon(Icons.Filled.PlayArrow, "Resume", tint = MaterialTheme.colorScheme.primary) }
                Status.FAILED -> IconButton({ vm.resume(e.id) }) { Icon(Icons.Filled.Refresh, "Retry", tint = MaterialTheme.colorScheme.primary) }
                else -> IconButton({ openFile(ctx, vm, e) }) { Icon(Icons.Filled.CheckCircle, "Open", tint = MaterialTheme.colorScheme.primary) }
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Options") }
                DownloadMenu(vm, e, menu, onDismiss = { menu = false }, onDelete = { confirmDelete = true }, push = push)
            }
        }
    }
    if (confirmDelete) DeleteDialog(e, onDismiss = { confirmDelete = false }) { withFile -> vm.delete(e.id, withFile) }
}

@Composable
fun DownloadMenu(vm: MainViewModel, e: DownloadEntity, expanded: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, push: (Screen) -> Unit) {
    val ctx = LocalContext.current
    DropdownMenu(expanded, onDismiss) {
        val done = e.status == Status.COMPLETED
        if (done) {
            DropdownMenuItem({ Text("Open") }, { onDismiss(); openFile(ctx, vm, e) })
            DropdownMenuItem({ Text("Share") }, { onDismiss(); shareFile(ctx, vm, e) })
            if (e.category == "Video" || e.category == "Music") DropdownMenuItem({ Text("Play") }, { onDismiss(); push(Screen.Player(e.id)) })
            if (!e.isPrivate) DropdownMenuItem({ Text("Move to private folder") }, { onDismiss(); vm.makePrivate(e.id) })
        } else {
            if (e.sequential && (e.category == "Video" || e.category == "Music")) DropdownMenuItem({ Text("Preview") }, { onDismiss(); push(Screen.Player(e.id)) })
            DropdownMenuItem({ Text("Move up in queue") }, { onDismiss(); vm.move(e.id, -1) })
            DropdownMenuItem({ Text("Move down in queue") }, { onDismiss(); vm.move(e.id, 1) })
            listOf(Priority.HIGH, Priority.NORMAL, Priority.LOW).forEach { p ->
                DropdownMenuItem({ Text((if (e.priority == p) "✓ " else "") + "Priority: ${Priority.label(p)}") }, { onDismiss(); vm.setPriority(e.id, p) })
            }
        }
        DropdownMenuItem({ Text("Copy link") }, {
            onDismiss()
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("link", e.url))
        })
        DropdownMenuItem({ Text("Open folder") }, { onDismiss(); openFolder(ctx) })
        DropdownMenuItem({ Text("Delete") }, { onDismiss(); onDelete() })
    }
}

@Composable
fun DeleteDialog(e: DownloadEntity, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var withFile by remember { mutableStateOf(e.status != Status.COMPLETED) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete download?") },
        text = {
            Column {
                Text(e.fileName.ifBlank { e.url }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withFile, { withFile = it })
                    Text("Also delete the file")
                }
            }
        },
        confirmButton = { TextButton({ onDismiss(); onConfirm(withFile) }) { Text("Delete") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
