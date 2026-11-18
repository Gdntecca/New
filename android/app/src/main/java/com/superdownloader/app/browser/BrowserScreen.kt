// Super Downloader — Created by Tez
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.superdownloader.app.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.superdownloader.app.engine.AddRequest
import com.superdownloader.app.engine.FileTypes
import com.superdownloader.app.ui.AddPrefill
import com.superdownloader.app.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener

/** JS bridge used to hand blob: downloads (as data URLs) back to the app. */
class BlobBridge(private val onData: (String, String?) -> Unit, private val errorCb: (String) -> Unit) {
    @JavascriptInterface
    fun onBlob(dataUrl: String, name: String?) = onData(dataUrl, name)

    @JavascriptInterface
    fun onError(msg: String) = errorCb(msg)
}

private val binaryExts = setOf(
    "apk", "xapk", "zip", "rar", "7z", "gz", "tar", "iso", "exe", "msi", "dmg", "ipa", "deb",
    "mp4", "mkv", "webm", "avi", "mov", "mp3", "flac", "m4a", "wav", "pdf", "epub", "bin",
)

private data class Capture(val url: String, val name: String, val referer: String?, val cookies: String?, val ua: String)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(vm: MainViewModel, startUrl: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    var address by remember { mutableStateOf(startUrl ?: "https://www.google.com") }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var googleBanner by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<Capture?>(null) }
    var grabbed by remember { mutableStateOf<List<String>?>(null) }
    val webRef = remember { mutableStateOf<WebView?>(null) }

    fun cookiesFor(url: String): String? = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

    fun saveDataUrl(dataUrl: String, name: String?, pageUrl: String?) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val header = dataUrl.substringBefore(',')
                    val payload = dataUrl.substringAfter(',', "")
                    val mime = header.removePrefix("data:").substringBefore(';').ifBlank { null }
                    val bytes = if (header.contains(";base64")) Base64.decode(payload, Base64.DEFAULT)
                    else Uri.decode(payload).toByteArray()
                    vm.engine.saveBytes(bytes, mime, name, pageUrl ?: "blob", pageUrl) != null
                }.getOrDefault(false)
            }
            vm.toast.value = if (ok) "Saved from page" else "Could not save this file"
        }
    }

    val bridge = remember {
        BlobBridge(
            onData = { data, name -> main.post { saveDataUrl(data, name, webRef.value?.url) } },
            errorCb = { msg -> main.post { vm.toast.value = "Page download failed: $msg" } },
        )
    }

    DisposableEffect(Unit) { onDispose { webRef.value?.destroy() } }
    BackHandler {
        val w = webRef.value
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }

    fun go(input: String) {
        val t = input.trim()
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        webRef.value?.loadUrl(url)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = address, onValueChange = { address = it }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { go(address) }),
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.Close, "Close browser") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                if (googleBanner) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Google may block sign-in inside in-app browsers. You can sign in with Chrome instead, but that sign-in stays in Chrome and isn't shared with this browser.",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton({
                                runCatching { CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(webRef.value?.url ?: address)) }
                            }) { Text("Use Chrome") }
                        }
                    }
                }
            }
        },
        bottomBar = {
            BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navigationBarsPadding()) {
                IconButton({ webRef.value?.goBack() }, enabled = canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                IconButton({ webRef.value?.goForward() }, enabled = canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                IconButton({ webRef.value?.reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
                IconButton({
                    webRef.value?.evaluateJavascript(GRAB_JS) { result ->
                        val list = runCatching {
                            val inner = JSONTokener(result).nextValue() as String
                            val arr = JSONArray(inner)
                            (0 until arr.length()).map { arr.getString(it) }
                        }.getOrDefault(emptyList())
                        grabbed = list
                    }
                }) { Icon(Icons.Filled.Link, "Grab links") }
                IconButton({ runCatching { CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(webRef.value?.url ?: address)) } }) {
                    Icon(Icons.Filled.OpenInBrowser, "Open in Chrome")
                }
            }
        },
    ) { pad ->
        AndroidView(
            modifier = Modifier.padding(pad).fillMaxSize().imePadding(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(bridge, "SDBridge")
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            address = url
                            googleBanner = Uri.parse(url).host?.contains("accounts.google.") == true
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            canBack = view.canGoBack(); canForward = view.canGoForward()
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            val scheme = u.scheme ?: return false
                            if (scheme != "http" && scheme != "https") {
                                runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                return true
                            }
                            if (u.host?.contains("accounts.google.") == true) googleBanner = true
                            // Capture obvious file links before the page navigates away.
                            val ext = FileTypes.extOf(u.lastPathSegment)
                            if (request.isForMainFrame && ext in binaryExts) {
                                val s = u.toString()
                                capture = Capture(s, FileTypes.nameFromUrl(s) ?: "", view.url, cookiesFor(s), view.settings.userAgentString)
                                return true
                            }
                            return false
                        }
                    }
                    setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                        when {
                            url.startsWith("blob:") -> {
                                val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
                                evaluateJavascript(blobJs(url, name), null)
                            }
                            url.startsWith("data:") -> saveDataUrl(url, null, this.url)
                            else -> capture = Capture(
                                url, FileTypes.parseContentDisposition(contentDisposition) ?: "",
                                this.url, cookiesFor(url), userAgent,
                            )
                        }
                    }
                    loadUrl(address)
                    webRef.value = this
                }
            },
        )
    }

    capture?.let { c ->
        AlertDialog(
            onDismissRequest = { capture = null },
            title = { Text("Download this file?") },
            text = { Text(c.name.ifBlank { c.url }, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton({
                    vm.addAll(listOf(AddRequest(url = c.url, fileName = c.name, cookies = c.cookies, userAgent = c.ua, referer = c.referer)))
                    capture = null
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton({
                    vm.prefill.value = AddPrefill(c.url, referer = c.referer, cookies = c.cookies, userAgent = c.ua, fileName = c.name)
                    capture = null
                }) { Text("Options…") }
            },
        )
    }

    grabbed?.let { links ->
        val selected = remember(links) { mutableStateOf(links.toSet()) }
        AlertDialog(
            onDismissRequest = { grabbed = null },
            title = { Text("Links on this page (${links.size})") },
            text = {
                if (links.isEmpty()) Text("No links found.")
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(links, key = { it }) { l ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected.value = if (l in selected.value) selected.value - l else selected.value + l
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(l in selected.value, { selected.value = if (it) selected.value + l else selected.value - l })
                            Text(l, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val page = webRef.value?.url
                    val ua = webRef.value?.settings?.userAgentString
                    vm.addAll(selected.value.map { AddRequest(url = it, cookies = cookiesFor(it), userAgent = ua, referer = page) })
                    grabbed = null
                }, enabled = selected.value.isNotEmpty()) { Text("Download ${selected.value.size}") }
            },
            dismissButton = { TextButton({ grabbed = null }) { Text("Close") } },
        )
    }
}

private const val GRAB_JS = """(function(){var s={};var out=[];
document.querySelectorAll('a[href],source[src],video[src],audio[src],img[src]').forEach(function(e){
var u=e.href||e.src; if(u&&/^https?:/i.test(u)&&!s[u]){s[u]=1;out.push(u);}});
return JSON.stringify(out);})()"""

private fun blobJs(url: String, name: String): String {
    val u = url.replace("\\", "\\\\").replace("'", "\\'")
    val n = name.replace("\\", "\\\\").replace("'", "\\'")
    return """(function(){fetch('$u').then(function(r){return r.blob();}).then(function(b){
var fr=new FileReader();fr.onloadend=function(){SDBridge.onBlob(fr.result,'$n');};fr.readAsDataURL(b);
}).catch(function(e){SDBridge.onError(String(e));});})()"""
}
