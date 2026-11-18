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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilledTonalButton
import com.superdownloader.app.data.TrafficMode
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
import androidx.compose.material3.SnackbarDuration
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
    val event by vm.event.collectAsStateWithLifecycle()
    LaunchedEffect(toast) {
        toast?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Short); vm.toast.value = null }
    }
    LaunchedEffect(event) {
        val ev = event ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(ev.message, actionLabel = ev.actionLabel, duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) ev.onAction?.invoke() else ev.onDismiss?.invoke()
        vm.event.value = null
    }
    val changeFor by vm.changeLinkFor.collectAsStateWithLifecycle()
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
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
        }
    }
    prefill?.let { AddDialog(vm, it, onDismiss = { vm.prefill.value = null }) }
    changeFor?.let { id -> ChangeLinkDialog(vm, id, onDismiss = { vm.changeLinkFor.value = null }) }
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


fun whenLabel(ms: Long): String {
    if (ms <= 0) return ""
    val now = java.util.Calendar.getInstance()
    val t = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))
    val sameDay = now.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == t.get(java.util.Calendar.DAY_OF_YEAR)
    now.add(java.util.Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == t.get(java.util.Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> "Today $time"
        yesterday -> "Yesterday $time"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(ms)) + " $time"
    }
}

private val sortLabels = listOf("Newest", "Oldest", "Name", "Size", "Progress", "Queue")
private val filterLabels = listOf("All files", "Active", "Completed", "Failed") + FileTypes.categories

@Composable
fun HomeScreen(vm: MainViewModel, onMenu: () -> Unit, push: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val all by vm.downloads.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(0) }
    var sort by rememberSaveable { mutableStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var selectMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(-1L) }
    val selected = remember { mutableStateListOf<Long>() }
    var confirmDeleteFiles by remember { mutableStateOf(false) }

    val qr = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents ?: return@rememberLauncherForActivityResult
        val urls = extractUrls(text)
        if (urls.isNotEmpty()) vm.prefill.value = AddPrefill(urls.joinToString("\n")) else vm.toast.value = "No link in that QR code"
    }

    val visible = remember(all, hidden, filter, query, sort) {
        all.asSequence()
            .filter { !it.isPrivate && it.id !in hidden }
            .filter {
                when (filter) {
                    0 -> true
                    1 -> it.status != Status.COMPLETED && it.status != Status.FAILED
                    2 -> it.status == Status.COMPLETED
                    3 -> it.status == Status.FAILED
                    else -> it.category == filterLabels[filter]
                }
            }
            .filter { query.isBlank() || it.fileName.contains(query, true) || it.url.contains(query, true) }
            .let { seq ->
                when (sort) {
                    1 -> seq.sortedBy { it.createdAt }
                    2 -> seq.sortedBy { it.fileName.lowercase() }
                    3 -> seq.sortedByDescending { it.totalBytes }
                    4 -> seq.sortedByDescending { it.progress }
                    5 -> seq.sortedWith(compareByDescending<DownloadEntity> { it.priority }.thenBy { it.queueOrder })
                    else -> seq.sortedByDescending { it.createdAt }
                }
            }.toList()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                CenterAlignedTopAppBar(
                    title = { Text("Super Downloader", fontWeight = FontWeight.Bold) },
                    actions = {
                        Box {
                            IconButton({ moreMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(moreMenu, { moreMenu = false }) {
                                DropdownMenuItem({ Text("Resume all") }, { moreMenu = false; vm.resumeAll() })
                                DropdownMenuItem({ Text("Pause all") }, { moreMenu = false; vm.pauseAll() })
                                DropdownMenuItem({ Text("Scan QR code") }, {
                                    moreMenu = false
                                    qr.launch(ScanOptions().setPrompt("Point at a QR code with a link").setBeepEnabled(false).setOrientationLocked(false))
                                })
                                DropdownMenuItem({ Text("Browser") }, { moreMenu = false; push(Screen.Browser(null)) })
                                DropdownMenuItem({ Text("Open folder") }, { moreMenu = false; openFolder(ctx) })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (searching) {
                    TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search downloads") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = { IconButton({ searching = false; query = "" }) { Icon(Icons.Filled.Close, "Close search") } },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                // Filter row: select-all, sort chip, filter chip, traffic-mode pill.
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    val allSel = visible.isNotEmpty() && selected.size == visible.size
                    Checkbox(allSel, { on -> selected.clear(); if (on) selected.addAll(visible.map { it.id }) })
                    Box {
                        Icon(Icons.Filled.ArrowDropDown, "Selection actions", Modifier.size(28.dp).clickable { selectMenu = true })
                        DropdownMenu(selectMenu, { selectMenu = false }) {
                            DropdownMenuItem({ Text("Select all") }, { selectMenu = false; selected.clear(); selected.addAll(visible.map { it.id }) })
                            DropdownMenuItem({ Text("Select none") }, { selectMenu = false; selected.clear() })
                            if (selected.isNotEmpty()) {
                                DropdownMenuItem({ Text("Resume selected") }, { selectMenu = false; selected.forEach(vm::resume) })
                                DropdownMenuItem({ Text("Pause selected") }, { selectMenu = false; selected.forEach(vm::pause) })
                                DropdownMenuItem({ Text("Delete selected") }, {
                                    selectMenu = false
                                    all.filter { it.id in selected }.forEach(vm::removeFromList); selected.clear()
                                })
                                DropdownMenuItem({ Text("Delete files of selected…") }, { selectMenu = false; confirmDeleteFiles = true })
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Box {
                        AssistChip(
                            onClick = { sortMenu = true },
                            label = { Text(sortLabels[sort]) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp)) },
                        )
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            sortLabels.forEachIndexed { i, l -> DropdownMenuItem({ Text(l, fontWeight = if (i == sort) FontWeight.Bold else null) }, { sort = i; sortMenu = false }) }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Box {
                        AssistChip(
                            onClick = { filterMenu = true },
                            label = { Text(filterLabels[filter], maxLines = 1) },
                            leadingIcon = { Icon(Icons.Filled.FilterList, null, Modifier.size(18.dp)) },
                        )
                        DropdownMenu(filterMenu, { filterMenu = false }) {
                            filterLabels.forEachIndexed { i, l -> DropdownMenuItem({ Text(l, fontWeight = if (i == filter) FontWeight.Bold else null) }, { filter = i; filterMenu = false }) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TrafficPill(settings.trafficMode) { next -> vm.updateSettings { it.copy(trafficMode = next) } }
                }
                blocked?.let {
                    Text(it, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 16.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        bottomBar = {
            BottomNav(
                onHome = { searching = false; query = ""; filter = 0 },
                onSearch = { searching = !searching },
                onAdd = { vm.prefill.value = AddPrefill("") },
                onSettings = { push(Screen.Settings) },
                onMenu = onMenu,
            )
        },
    ) { pad ->
        if (visible.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Download, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text(if (all.isEmpty()) "No downloads yet" else "Nothing here", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(visible, key = { it.id }) { e ->
                    DownloadRow(
                        vm = vm, e = e,
                        selected = e.id in selected,
                        selectionMode = selected.isNotEmpty(),
                        expanded = expanded == e.id,
                        onClick = {
                            if (selected.isNotEmpty()) { if (e.id in selected) selected.remove(e.id) else selected.add(e.id) }
                            else expanded = if (expanded == e.id) -1L else e.id
                        },
                        onLongClick = { if (e.id !in selected) selected.add(e.id) },
                        push = push,
                    )
                }
            }
        }
    }
    if (confirmDeleteFiles) {
        val targets = all.filter { it.id in selected }
        AlertDialog(
            onDismissRequest = { confirmDeleteFiles = false },
            title = { Text("Delete ${targets.size} file(s) from storage?") },
            text = { Text("The files are removed from your device. This can't be undone.") },
            confirmButton = { TextButton({ targets.forEach { vm.delete(it.id, true) }; selected.clear(); confirmDeleteFiles = false }) { Text("Delete files") } },
            dismissButton = { TextButton({ confirmDeleteFiles = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrafficPill(mode: Int, onChange: (Int) -> Unit) {
    val label = TrafficMode.labels[mode]
    val unlimited = mode == TrafficMode.UNLIMITED
    Surface(
        onClick = {
            val order = listOf(TrafficMode.UNLIMITED, TrafficMode.HIGH, TrafficMode.MEDIUM, TrafficMode.LOW)
            val i = order.indexOf(mode)
            onChange(order[(i + 1).mod(order.size)])
        },
        shape = RoundedCornerShape(50),
        color = if (unlimited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (unlimited) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BottomNav(onHome: () -> Unit, onSearch: () -> Unit, onAdd: () -> Unit, onSettings: () -> Unit, onMenu: () -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().height(60.dp).align(Alignment.BottomCenter)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceAround) {
                IconButton(onHome) { Icon(Icons.Filled.Home, "Home", tint = MaterialTheme.colorScheme.primary) }
                IconButton(onSearch) { Icon(Icons.Filled.Search, "Search") }
                Spacer(Modifier.width(56.dp))
                IconButton(onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                IconButton(onMenu) { Icon(Icons.Filled.Menu, "Menu") }
            }
        }
        FloatingActionButton(
            onClick = onAdd,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.TopCenter).padding(bottom = 30.dp).size(56.dp),
        ) { Icon(Icons.Filled.Add, "Add download") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadRow(
    vm: MainViewModel,
    e: DownloadEntity,
    selected: Boolean,
    selectionMode: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    push: (Screen) -> Unit,
) {
    val ctx = LocalContext.current
    // Each row collects only its own live stats, so progress ticks never recompose the list.
    val liveFlow = remember(e.id) { vm.engine.liveFlow(e.id) }
    val ls by liveFlow.collectAsStateWithLifecycle(initialValue = null)
    var menu by remember { mutableStateOf(false) }
    var confirmDeleteFile by remember { mutableStateOf(false) }
    val active = e.status == Status.DOWNLOADING
    val accent = MaterialTheme.colorScheme.primary
    val prioCap = vm.settings.value.priorityCap(e.priority)

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) Checkbox(selected, { onClick() })
            Icon(
                if (e.status == Status.COMPLETED && e.category == "Archives") Icons.Filled.Folder else categoryIcon(e.category),
                null, Modifier.size(40.dp), tint = accent,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.fileName.ifBlank { FileTypes.nameFromUrl(e.url) ?: e.url },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(3.dp))
                val total = ls?.total ?: e.totalBytes
                val done = ls?.downloaded ?: e.downloadedBytes
                if (e.status != Status.COMPLETED && e.status != Status.FAILED) {
                    SegmentedBar(if (total > 0) done.toFloat() / total else 0f, total, ls?.segments, indeterminate = active && total <= 0, height = 4)
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (status, color) = when (e.status) {
                        Status.COMPLETED -> "Completed" to accent
                        Status.FAILED -> "Error" to MaterialTheme.colorScheme.error
                        Status.DOWNLOADING -> (if (total > 0) "${done * 100 / total}%" else "Downloading") to accent
                        else -> Status.label(e.status) to accent
                    }
                    Text(status, color = color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    val rest = when (e.status) {
                        Status.COMPLETED -> listOf(Fmt.bytes(e.totalBytes), whenLabel(e.completedAt))
                        Status.DOWNLOADING -> listOfNotNull(
                            ls?.let { Fmt.speed(it.speed) },
                            ls?.etaSec?.takeIf { it >= 0 }?.let { Fmt.duration(it) + " left" },
                            if (total > 0) "${Fmt.bytes(done)} / ${Fmt.bytes(total)}" else Fmt.bytes(done),
                        )
                        Status.FAILED -> listOf(e.error ?: "failed")
                        else -> listOfNotNull(if (total > 0) "${Fmt.bytes(done)} / ${Fmt.bytes(total)}" else null, whenLabel(e.createdAt))
                    } + listOfNotNull(if (prioCap > 0) "${Priority.label(e.priority)} ≤ ${Fmt.speed(prioCap)}" else null)
                    Text(
                        rest.filter { it.isNotBlank() }.joinToString(separator = " | ", prefix = " | "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Options") }
                DownloadMenu(vm, e, menu, onDismiss = { menu = false }, onDeleteFile = { confirmDeleteFile = true }, push = push)
            }
        }
        // Inline actions on tap: pause/resume right on the row, no popup.
        if (expanded || (e.status == Status.FAILED && com.superdownloader.app.engine.LinkMatcher.looksExpired(e.error))) {
            Row(Modifier.padding(start = 52.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (e.status) {
                    Status.DOWNLOADING, Status.QUEUED, Status.WAITING ->
                        FilledTonalButton({ vm.pause(e.id) }) { Icon(Icons.Filled.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Pause") }
                    Status.PAUSED ->
                        FilledTonalButton({ vm.resume(e.id) }) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Resume") }
                    Status.FAILED -> {
                        FilledTonalButton({ vm.resume(e.id) }) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Retry") }
                        if (com.superdownloader.app.engine.LinkMatcher.looksExpired(e.error))
                            TextButton({ vm.changeLinkFor.value = e.id }) { Text("Link expired? Change link") }
                    }
                    Status.COMPLETED -> {
                        FilledTonalButton({ openFile(ctx, vm, e) }) { Text("Open") }
                        TextButton({ shareFile(ctx, vm, e) }) { Text("Share") }
                    }
                }
                if (expanded) TextButton({ push(Screen.Detail(e.id)) }) { Text("Details") }
            }
        }
    }
    if (confirmDeleteFile) DeleteFileDialog(e, onDismiss = { confirmDeleteFile = false }) { vm.delete(e.id, true) }
}

@Composable
fun DownloadMenu(vm: MainViewModel, e: DownloadEntity, expanded: Boolean, onDismiss: () -> Unit, onDeleteFile: () -> Unit, push: (Screen) -> Unit) {
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
        }
        DropdownMenuItem({ Text("Change link…") }, { onDismiss(); vm.changeLinkFor.value = e.id })
        HorizontalDivider()
        Priority.all.forEach { p ->
            val cap = vm.settings.value.priorityCap(p)
            DropdownMenuItem(
                { Text((if (e.priority == p) "✓ " else "    ") + Priority.label(p) + if (cap > 0) "  (≤ ${Fmt.speed(cap)})" else "") },
                { onDismiss(); vm.setPriority(e.id, p) },
            )
        }
        HorizontalDivider()
        DropdownMenuItem({ Text("Copy link") }, {
            onDismiss()
            ctx.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("link", e.url))
        })
        DropdownMenuItem({ Text("Details") }, { onDismiss(); push(Screen.Detail(e.id)) })
        DropdownMenuItem({ Text("Delete") }, { onDismiss(); vm.removeFromList(e) })
        DropdownMenuItem({ Text("Delete file…", color = MaterialTheme.colorScheme.error) }, { onDismiss(); onDeleteFile() })
    }
}

/** The one destructive confirmation we keep: removing the file from disk. */
@Composable
fun DeleteFileDialog(e: DownloadEntity, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete file from storage?") },
        text = { Text("“${e.fileName.ifBlank { e.url }}” will be removed from your device. This can't be undone.", maxLines = 4, overflow = TextOverflow.Ellipsis) },
        confirmButton = { TextButton({ onDismiss(); onConfirm() }) { Text("Delete file") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
