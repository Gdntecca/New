import Foundation
#if canImport(ActivityKit)
import ActivityKit
#endif

/// What the home-screen widget shows (written by the app into the App Group container).
struct WidgetSnapshot: Codable {
    var active: Int
    var queued: Int
    var progress: Double
    var title: String
    var updated: Date
}

#if canImport(ActivityKit)
@available(iOS 16.1, *)
struct DownloadActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var active: Int
        var speed: Int64
        var done: Int64
        var total: Int64
        var title: String
    }
}
#endif

enum SharedFormat {
    static func bytes(_ b: Int64) -> String {
        if b < 1024 { return "\(max(b, 0)) B" }
        let units = ["KB", "MB", "GB", "TB"]
        var v = Double(b) / 1024
        var i = 0
        while v >= 1024 && i < units.count - 1 { v /= 1024; i += 1 }
        return String(format: v >= 100 ? "%.0f %@" : "%.1f %@", v, units[i])
    }
}
