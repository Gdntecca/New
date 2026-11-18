import Foundation
import SwiftUI

enum TrafficMode: Int, CaseIterable, Codable {
    case low, medium, high, unlimited, custom
    var label: String { ["Low", "Medium", "High", "Unlimited", "Custom"][rawValue] }
}

enum ConflictPolicy: Int, CaseIterable, Codable {
    case rename, overwrite, skip
    var label: String { ["Rename", "Overwrite", "Skip"][rawValue] }
}

struct AppSettings: Codable, Equatable {
    var theme = 0                 // 0 system, 1 light, 2 dark
    var trafficMode = TrafficMode.unlimited
    var customLimitKBps = 1024
    var parts = 8
    var maxPerServer = 16
    var maxSimultaneous = 3
    var retryCount = 5
    var retryDelaySec = 3
    var adaptive = true
    var wifiOnly = false
    var pauseInLowPower = false
    var clipboardMonitor = true
    var autoExtractZip = false
    var conflictPolicy = ConflictPolicy.rename
    var userAgent = ""
    var proxyType = 0             // 0 none, 1 HTTP, 2 SOCKS
    var proxyHost = ""
    var proxyPort = 0
    var scheduleEnabled = false
    var scheduleStart = 2 * 60
    var scheduleStop = 8 * 60
    var notifySound = true

    /// Bytes per second; 0 = unlimited.
    var limitBytesPerSec: Int64 {
        switch trafficMode {
        case .low: return 256 * 1024
        case .medium: return 1024 * 1024
        case .high: return 4096 * 1024
        case .custom: return Int64(max(customLimitKBps, 0)) * 1024
        case .unlimited: return 0
        }
    }

    var colorScheme: ColorScheme? { theme == 1 ? .light : theme == 2 ? .dark : nil }
}

/// Settings persisted in UserDefaults as one JSON blob.
final class SettingsStore: ObservableObject {
    static let shared = SettingsStore()
    @Published var value: AppSettings {
        didSet { save() }
    }

    private init() {
        if let d = UserDefaults.standard.data(forKey: "settings"), let s = try? JSONDecoder().decode(AppSettings.self, from: d) {
            value = s
        } else {
            value = AppSettings()
        }
    }

    private func save() {
        if let d = try? JSONEncoder().encode(value) { UserDefaults.standard.set(d, forKey: "settings") }
    }
}
