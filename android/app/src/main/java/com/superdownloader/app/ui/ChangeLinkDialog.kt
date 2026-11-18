package com.superdownloader.app.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.superdownloader.app.engine.Fmt
import com.superdownloader.app.engine.LinkMatch
import com.superdownloader.app.engine.RemoteInfo
import com.superdownloader.app.engine.extractUrls

/**
 * Change link: paste a fresh URL for an expired download. A matching file resumes from the
 * saved pieces; a different file only restarts after the user agrees.
 */
@Composable
fun ChangeLinkDialog(vm: MainViewModel, id: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val flow = remember(id) { vm.observe(id) }
    val e by flow.collectAsState(initial = null)
    val entity = e ?: return
    val clip = remember {
        runCatching {
            ctx.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { extractUrls(it).firstOrNull() }
        }.getOrNull()?.takeIf { it != entity.url }
    }
    var url by remember { mutableStateOf(clip ?: "") }
    var headers by remember { mutableStateOf(entity.headers) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf<Pair<LinkMatch, RemoteInfo?>?>(null) }

    val m = mismatch
    if (m != null) {
        AlertDialog(
            onDismissRequest = { mismatch = null },
            title = { Text("Different file?") },
            text = {
                Text(
                    when (m.first) {
                        LinkMatch.DIFFERENT_SIZE -> "This link looks like a different file (${Fmt.bytes(m.second?.total ?: -1)} instead of ${Fmt.bytes(entity.totalBytes)}). Restart from zero?"
                        LinkMatch.UNKNOWN_SIZE -> "The server doesn't report a size, so this can't be confirmed as the same file. Restart from zero?"
                        else -> "This link looks like a different file. Restart from zero?"
                    },
                )
            },
            confirmButton = {
                TextButton({ vm.applyLink(id, url.trim(), headers, m.second, restart = true); mismatch = null; onDismiss() }) { Text("Restart") }
            },
            dismissButton = { TextButton({ mismatch = null }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change link") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(entity.fileName.ifBlank { entity.url }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entity.downloadedBytes > 0) {
                    Text("${Fmt.bytes(entity.downloadedBytes)} already downloaded will be kept if the file matches.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = url, onValueChange = { url = it; error = null },
                    label = { Text("New link") }, isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = headers, onValueChange = { headers = it },
                    label = { Text("Headers (Name: value per line)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Cookies and referer are kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !checking, onClick = {
                val u = extractUrls(url).firstOrNull()
                if (u == null) { error = "Enter an http(s) link"; return@TextButton }
                url = u
                checking = true
                vm.checkLink(entity, u, headers) { match, info, err ->
                    checking = false
                    when {
                        err != null -> error = err
                        match == LinkMatch.SAME -> { vm.applyLink(id, u, headers, info, restart = false); onDismiss() }
                        match != null -> mismatch = match to info
                    }
                }
            }) { Text("Check & use") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
