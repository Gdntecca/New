package com.superdownloader.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superdownloader.app.engine.extractUrls
import com.superdownloader.app.ui.AddPrefill
import com.superdownloader.app.ui.MainViewModel
import com.superdownloader.app.ui.SuperRoot
import com.superdownloader.app.ui.SuperTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()
    val openDetail = MutableStateFlow<Long?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        Thread { runCatching { (application as SuperApp).engine.output.ensureFolders() } }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Permissions are requested only when first needed (when a download is added).
        lifecycleScope.launch {
            vm.needPermissions.collect { need -> if (need) { requestRuntimePermissions(); vm.needPermissions.value = false } }
        }
        handleIntent(intent)
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            SuperTheme(s.theme) {
                SuperRoot(vm, this, openDetail)
            }
        }
    }

    private var askedPermissions = false

    private fun requestRuntimePermissions() {
        if (askedPermissions) return
        askedPermissions = true
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val change = i.getLongExtra("change_link", -1)
        if (change >= 0) { vm.changeLinkFor.value = change; return }
        val detail = i.getLongExtra("open_detail", -1)
        if (detail >= 0) { openDetail.value = detail; return }
        when (i.action) {
            Intent.ACTION_SEND -> {
                val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val urls = extractUrls(text)
                if (urls.isNotEmpty()) vm.prefill.value = AddPrefill(urls.joinToString("\n"))
            }
            Intent.ACTION_VIEW -> {
                val u = i.data ?: return
                if (u.scheme == "superdl") {
                    val target = u.getQueryParameter("url") ?: u.toString().removePrefix("superdl://")
                    val urls = extractUrls(target)
                    if (urls.isNotEmpty()) vm.prefill.value = AddPrefill(urls.joinToString("\n"))
                } else {
                    vm.prefill.value = AddPrefill(u.toString())
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        runCatching {
            val cm = getSystemService(ClipboardManager::class.java)
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            vm.offerClipboard(text)
        }
    }

    /** Face/fingerprint (or device PIN) gate for the private folder. */
    fun unlockPrivate(onSuccess: () -> Unit) {
        val authenticators = if (Build.VERSION.SDK_INT >= 30)
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK
        val can = BiometricManager.from(this).canAuthenticate(authenticators)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            vm.toast.value = "Set up a fingerprint, face or screen lock to use the private folder"
            return
        }
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock private downloads")
            .setSubtitle("Super Downloader")
            .setAllowedAuthenticators(authenticators)
        if (Build.VERSION.SDK_INT < 30) builder.setNegativeButtonText("Cancel")
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
        })
        prompt.authenticate(builder.build())
    }
}
