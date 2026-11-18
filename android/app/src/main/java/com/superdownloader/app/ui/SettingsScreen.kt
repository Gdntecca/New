// Super Downloader — Created by Tez
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.superdownloader.app.ui

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superdownloader.app.data.ConflictPolicy
import com.superdownloader.app.data.TrafficMode
import com.superdownloader.app.engine.Fmt

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun SliderRow(title: String, value: Int, range: IntRange, suffix: String = "", onChange: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("$title: $value$suffix", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o -> FilterChip(selected == i, { onSelect(i) }, label = { Text(o) }) }
    }
}

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val low = vm.engine.isLowRam
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            vm.updateSettings { it.copy(customFolderUri = uri.toString()) }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportList) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importList) }

    fun pickTime(initial: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> onPicked(h * 60 + m) }, initial / 60, initial % 60, true).show()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section("Appearance") {
                Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                Chips(listOf("System", "Light", "Dark"), s.theme) { v -> vm.updateSettings { it.copy(theme = v) } }
            }
            Section("Traffic") {
                Text("Traffic mode", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                Chips(TrafficMode.labels, s.trafficMode) { v -> vm.updateSettings { it.copy(trafficMode = v) } }
                Text(
                    when (s.trafficMode) {
                        TrafficMode.UNLIMITED -> "No speed limit"
                        else -> "Limit: ${Fmt.speed(s.limitBytesPerSec())} for all downloads"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.trafficMode == TrafficMode.CUSTOM) {
                    OutlinedTextField(
                        value = s.customLimitKbps.toString(),
                        onValueChange = { t -> t.filter { it.isDigit() }.take(7).toIntOrNull()?.let { v -> vm.updateSettings { it.copy(customLimitKbps = v) } } },
                        label = { Text("Custom limit (KB/s)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
            }
            Section("Connections") {
                SliderRow("Connections per download", s.parts, 1..16) { v -> vm.updateSettings { it.copy(parts = v) } }
                SliderRow("Max connections per server", s.maxPerServer, 1..16) { v -> vm.updateSettings { it.copy(maxPerServer = v) } }
                SliderRow("Simultaneous downloads", s.maxSimultaneous, 1..10) { v -> vm.updateSettings { it.copy(maxSimultaneous = v) } }
                SwitchRow("Adaptive connections", "Adds connections while total speed keeps rising", s.adaptive) { v -> vm.updateSettings { it.copy(adaptive = v) } }
                SliderRow("Retries", s.retryCount, 0..20) { v -> vm.updateSettings { it.copy(retryCount = v) } }
                SliderRow("Retry delay", s.retryDelaySec, 1..60, " s") { v -> vm.updateSettings { it.copy(retryDelaySec = v) } }
                if (low) Text("Low-memory device: limited to 4 connections and 2 downloads at once.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Section("Network") {
                SwitchRow("Wi‑Fi only", "Pause on mobile data", s.wifiOnly) { v -> vm.updateSettings { it.copy(wifiOnly = v) } }
                SwitchRow("Pause in battery saver", null, s.pauseOnBatterySaver) { v -> vm.updateSettings { it.copy(pauseOnBatterySaver = v) } }
                Text("Proxy", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                Chips(listOf("None", "HTTP", "SOCKS"), s.proxyType) { v -> vm.updateSettings { it.copy(proxyType = v) } }
                if (s.proxyType != 0) {
                    OutlinedTextField(s.proxyHost, { v -> vm.updateSettings { it.copy(proxyHost = v.trim()) } }, label = { Text("Proxy host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        if (s.proxyPort == 0) "" else s.proxyPort.toString(),
                        { t -> vm.updateSettings { it.copy(proxyPort = t.filter { c -> c.isDigit() }.take(5).toIntOrNull() ?: 0) } },
                        label = { Text("Proxy port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }
                OutlinedTextField(s.userAgent, { v -> vm.updateSettings { it.copy(userAgent = v) } }, label = { Text("Custom User-Agent (blank = default)") }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
            Section("Files") {
                Text("Save to", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                Text(
                    if (s.customFolderUri.isBlank()) "Download/SuperDownloader/<category>" else Uri.decode(s.customFolderUri).substringAfterLast(':') + "/<category>",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton({ folderPicker.launch(null) }) { Text("Choose folder") }
                    if (s.customFolderUri.isNotBlank()) TextButton({ vm.updateSettings { it.copy(customFolderUri = "") } }) { Text("Use Downloads") }
                    TextButton({ openFolder(ctx) }) { Text("Open folder") }
                }
                Text("When a file already exists", style = MaterialTheme.typography.bodyLarge)
                Chips(ConflictPolicy.labels, s.conflictPolicy) { v -> vm.updateSettings { it.copy(conflictPolicy = v) } }
                SwitchRow("Auto-extract ZIP files", "Unpacks into a folder next to the archive", s.autoExtractZip) { v -> vm.updateSettings { it.copy(autoExtractZip = v) } }
            }
            Section("Scheduler") {
                SwitchRow("Run queue on a schedule", "Downloads only between the start and stop times", s.scheduleEnabled) { v -> vm.updateSettings { it.copy(scheduleEnabled = v) } }
                Row {
                    TextButton({ pickTime(s.scheduleStart) { v -> vm.updateSettings { it.copy(scheduleStart = v) } } }) {
                        Text("Start %02d:%02d".format(s.scheduleStart / 60, s.scheduleStart % 60))
                    }
                    TextButton({ pickTime(s.scheduleStop) { v -> vm.updateSettings { it.copy(scheduleStop = v) } } }) {
                        Text("Stop %02d:%02d".format(s.scheduleStop / 60, s.scheduleStop % 60))
                    }
                }
            }
            Section("Notifications & clipboard") {
                SwitchRow("Sound when a download finishes", null, s.notifySound) { v -> vm.updateSettings { it.copy(notifySound = v) } }
                SwitchRow("Watch clipboard for links", null, s.clipboardMonitor) { v -> vm.updateSettings { it.copy(clipboardMonitor = v) } }
            }
            Section("Background") {
                val pm = ctx.getSystemService(PowerManager::class.java)
                val exempt = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
                Text(
                    if (exempt) "Battery optimisation is off for Super Downloader. Downloads keep full speed in the background."
                    else "Some phones slow down or stop background apps. Allow Super Downloader to run unrestricted.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
                if (!exempt) TextButton({
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }
                }) { Text("Allow background downloads") }
            }
            Section("Data") {
                Row {
                    TextButton({ exporter.launch("superdownloader-list.json") }) { Text("Export list") }
                    TextButton({ importer.launch(arrayOf("application/json", "*/*")) }) { Text("Import list") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
