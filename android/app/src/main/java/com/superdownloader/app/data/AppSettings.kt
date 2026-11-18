package com.superdownloader.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TrafficMode {
    const val LOW = 0
    const val MEDIUM = 1
    const val HIGH = 2
    const val UNLIMITED = 3
    const val CUSTOM = 4
    val labels = listOf("Low", "Medium", "High", "Unlimited", "Custom")
}

object ConflictPolicy {
    const val RENAME = 0
    const val OVERWRITE = 1
    const val SKIP = 2
    val labels = listOf("Rename", "Overwrite", "Skip")
}

data class SettingsState(
    val theme: Int = 0, // 0 system, 1 light, 2 dark
    val trafficMode: Int = TrafficMode.UNLIMITED,
    val customLimitKbps: Int = 1024,
    val parts: Int = 8,
    val maxPerServer: Int = 16,
    val maxSimultaneous: Int = 3,
    val retryCount: Int = 5,
    val retryDelaySec: Int = 3,
    val adaptive: Boolean = true,
    val wifiOnly: Boolean = false,
    val pauseOnBatterySaver: Boolean = false,
    val clipboardMonitor: Boolean = true,
    val autoExtractZip: Boolean = false,
    val conflictPolicy: Int = ConflictPolicy.RENAME,
    val userAgent: String = "",
    val proxyType: Int = 0, // 0 none, 1 HTTP, 2 SOCKS
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val customFolderUri: String = "",
    val scheduleEnabled: Boolean = false,
    val scheduleStart: Int = 2 * 60,
    val scheduleStop: Int = 8 * 60,
    val notifySound: Boolean = true,
) {
    /** Bytes per second; 0 = unlimited. */
    fun limitBytesPerSec(): Long = when (trafficMode) {
        TrafficMode.LOW -> 256L * 1024
        TrafficMode.MEDIUM -> 1024L * 1024
        TrafficMode.HIGH -> 4096L * 1024
        TrafficMode.CUSTOM -> customLimitKbps.toLong().coerceAtLeast(0) * 1024
        else -> 0L
    }
}

class AppSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state
    val value: SettingsState get() = _state.value

    fun update(f: (SettingsState) -> SettingsState) {
        val n = f(_state.value)
        _state.value = n
        save(n)
    }

    private fun load(): SettingsState {
        val d = SettingsState()
        return SettingsState(
            theme = p.getInt("theme", d.theme),
            trafficMode = p.getInt("trafficMode", d.trafficMode),
            customLimitKbps = p.getInt("customLimitKbps", d.customLimitKbps),
            parts = p.getInt("parts", d.parts),
            maxPerServer = p.getInt("maxPerServer", d.maxPerServer),
            maxSimultaneous = p.getInt("maxSimultaneous", d.maxSimultaneous),
            retryCount = p.getInt("retryCount", d.retryCount),
            retryDelaySec = p.getInt("retryDelaySec", d.retryDelaySec),
            adaptive = p.getBoolean("adaptive", d.adaptive),
            wifiOnly = p.getBoolean("wifiOnly", d.wifiOnly),
            pauseOnBatterySaver = p.getBoolean("pauseOnBatterySaver", d.pauseOnBatterySaver),
            clipboardMonitor = p.getBoolean("clipboardMonitor", d.clipboardMonitor),
            autoExtractZip = p.getBoolean("autoExtractZip", d.autoExtractZip),
            conflictPolicy = p.getInt("conflictPolicy", d.conflictPolicy),
            userAgent = p.getString("userAgent", d.userAgent) ?: "",
            proxyType = p.getInt("proxyType", d.proxyType),
            proxyHost = p.getString("proxyHost", d.proxyHost) ?: "",
            proxyPort = p.getInt("proxyPort", d.proxyPort),
            customFolderUri = p.getString("customFolderUri", d.customFolderUri) ?: "",
            scheduleEnabled = p.getBoolean("scheduleEnabled", d.scheduleEnabled),
            scheduleStart = p.getInt("scheduleStart", d.scheduleStart),
            scheduleStop = p.getInt("scheduleStop", d.scheduleStop),
            notifySound = p.getBoolean("notifySound", d.notifySound),
        )
    }

    private fun save(s: SettingsState) {
        p.edit()
            .putInt("theme", s.theme)
            .putInt("trafficMode", s.trafficMode)
            .putInt("customLimitKbps", s.customLimitKbps)
            .putInt("parts", s.parts)
            .putInt("maxPerServer", s.maxPerServer)
            .putInt("maxSimultaneous", s.maxSimultaneous)
            .putInt("retryCount", s.retryCount)
            .putInt("retryDelaySec", s.retryDelaySec)
            .putBoolean("adaptive", s.adaptive)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putBoolean("pauseOnBatterySaver", s.pauseOnBatterySaver)
            .putBoolean("clipboardMonitor", s.clipboardMonitor)
            .putBoolean("autoExtractZip", s.autoExtractZip)
            .putInt("conflictPolicy", s.conflictPolicy)
            .putString("userAgent", s.userAgent)
            .putInt("proxyType", s.proxyType)
            .putString("proxyHost", s.proxyHost)
            .putInt("proxyPort", s.proxyPort)
            .putString("customFolderUri", s.customFolderUri)
            .putBoolean("scheduleEnabled", s.scheduleEnabled)
            .putInt("scheduleStart", s.scheduleStart)
            .putInt("scheduleStop", s.scheduleStop)
            .putBoolean("notifySound", s.notifySound)
            .apply()
    }
}
