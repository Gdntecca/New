package com.superdownloader.app.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.superdownloader.app.data.Priority
import com.superdownloader.app.engine.AddRequest
import com.superdownloader.app.engine.extractUrls

@Composable
fun AddDialog(vm: MainViewModel, prefill: AddPrefill, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s = vm.settings.value
    var urls by remember { mutableStateOf(prefill.urls) }
    var fileName by remember { mutableStateOf(prefill.fileName) }
    var advanced by remember { mutableStateOf(false) }
    var conns by remember { mutableFloatStateOf(vm.engine.effectiveParts(s).toFloat()) }
    var sequential by remember { mutableStateOf(false) }
    var priority by remember { mutableIntStateOf(Priority.NORMAL) }
    var headers by remember { mutableStateOf("") }
    var cookies by remember { mutableStateOf(prefill.cookies ?: "") }
    var referer by remember { mutableStateOf(prefill.referer ?: "") }
    var ua by remember { mutableStateOf(prefill.userAgent ?: "") }
    var checksumType by remember { mutableStateOf("None") }
    var checksum by remember { mutableStateOf("") }
    var startPaused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dupes by remember { mutableStateOf<List<String>>(emptyList()) }

    val parsed = extractUrls(urls)

    fun build(list: List<String>): List<AddRequest> = list.map { u ->
        AddRequest(
            url = u,
            fileName = if (list.size == 1) fileName.trim() else "",
            headers = headers,
            cookies = cookies.ifBlank { null },
            userAgent = ua.ifBlank { null },
            referer = referer.ifBlank { null },
            connections = conns.toInt(),
            sequential = sequential,
            priority = priority,
            checksumType = if (checksumType == "None" || list.size != 1) null else checksumType,
            expectedChecksum = if (checksumType == "None" || list.size != 1) null else checksum,
            startPaused = startPaused,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add download") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = urls, onValueChange = { urls = it; error = null },
                    label = { Text("Link(s) — one per line for batch") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    isError = error != null,
                    supportingText = { Text(error ?: if (parsed.size > 1) "${parsed.size} links" else "") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                        urls = if (urls.isBlank()) t else urls.trimEnd() + "\n" + t
                    }) { Text("Paste") }
                    TextButton({ advanced = !advanced }) { Text(if (advanced) "Hide options" else "More options") }
                }
                if (dupes.isNotEmpty()) {
                    Text("${dupes.size} link(s) are already in your list.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton({ vm.addAll(build(parsed)); onDismiss() }) { Text("Add again") }
                        TextButton({
                            val fresh = parsed.filter { it !in dupes }
                            vm.addAll(build(fresh)); onDismiss()
                        }) { Text("Skip duplicates") }
                    }
                }
                if (parsed.size <= 1) {
                    OutlinedTextField(fileName, { fileName = it }, label = { Text("File name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (advanced) {
                    Spacer(Modifier.width(8.dp))
                    Text("Connections: ${conns.toInt()}", style = MaterialTheme.typography.labelLarge)
                    Slider(conns, { conns = it }, valueRange = 1f..16f, steps = 14)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(sequential, { sequential = it })
                        Text("Sequential (preview media while downloading)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(startPaused, { startPaused = it })
                        Text("Add paused")
                    }
                    Text("Priority", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Priority.all.forEach { p ->
                            FilterChip(priority == p, { priority = p }, label = { Text(Priority.label(p)) })
                        }
                    }
                    OutlinedTextField(referer, { referer = it }, label = { Text("Referer / web page") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cookies, { cookies = it }, label = { Text("Cookies (name=value; …)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(headers, { headers = it }, label = { Text("Extra headers (Name: value per line)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ua, { ua = it }, label = { Text("User-Agent (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (parsed.size <= 1) {
                        Text("Verify checksum", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("None", "MD5", "SHA-256").forEach { t -> FilterChip(checksumType == t, { checksumType = t }, label = { Text(t) }) }
                        }
                        if (checksumType != "None") OutlinedTextField(checksum, { checksum = it }, label = { Text("Expected $checksumType") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                if (parsed.isEmpty()) { error = "Enter at least one http(s) link"; return@TextButton }
                val d = vm.duplicates(parsed)
                if (d.isNotEmpty()) dupes = d else { vm.addAll(build(parsed)); onDismiss() }
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
