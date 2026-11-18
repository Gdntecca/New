import Foundation
import SDCore

enum DLStatus: String, Codable {
    case queued, downloading, paused, completed, failed, waiting

    var label: String {
        switch self {
        case .queued: return "Queued"
        case .downloading: return "Downloading"
        case .paused: return "Paused"
        case .completed: return "Completed"
        case .failed: return "Error"
        case .waiting: return "Waiting"
        }
    }
}

struct Segment: Codable, Equatable {
    var start: Int64
    var end: Int64          // inclusive, -1 when unknown
    var downloaded: Int64

    var pos: Int64 { SegmentPlanner.resumeFrom(start: start, downloaded: downloaded) }
    var remaining: Int64 { SegmentPlanner.remaining(start: start, downloaded: downloaded, end: end) }
}

struct DownloadItem: Codable, Identifiable, Equatable {
    var id: UUID = UUID()
    var url: String
    var fileName: String = ""
    var mimeType: String?
    var category: String = "Other"
    var totalBytes: Int64 = -1
    var downloadedBytes: Int64 = 0
    var status: DLStatus = .queued
    var supportsRange = false
    var maxConnections = 8
    var sequential = false
    var queueOrder: Double = Date().timeIntervalSince1970
    var headers: String = ""
    var userAgent: String?
    var referer: String?
    var cookies: String?
    var relativePath: String?          // path inside Documents (or Library/Private)
    var error: String?
    var warning: String?
    var checksumType: String?
    var expectedChecksum: String?
    var checksumResult: String?
    var isPrivate = false
    var createdAt = Date()
    var completedAt: Date?
    var elapsed: TimeInterval = 0
    var peakSpeed: Int64 = 0
    var etag: String?
    var lastModified: String?
    var serverName: String?
    var segments: [Segment] = []
    var log: String = ""

    var progress: Double { totalBytes > 0 ? min(1, Double(downloadedBytes) / Double(totalBytes)) : 0 }
    var displayName: String { fileName.isEmpty ? (FileTypes.name(fromURL: URL(string: url)) ?? url) : fileName }

    mutating func appendLog(_ msg: String) {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        log += "[\(f.string(from: Date()))] \(msg)\n"
        if log.count > 8000 { log = String(log.suffix(8000)) }
    }
}

/// Live numbers for one running download (kept out of the persisted model).
struct LiveStats: Equatable {
    struct Piece: Equatable {
        var start: Int64
        var end: Int64
        var downloaded: Int64
        var active: Bool
        var speed: Int64
        var conn: Int
    }
    var downloaded: Int64 = 0
    var total: Int64 = -1
    var speed: Int64 = 0
    var avgSpeed: Int64 = 0
    var peakSpeed: Int64 = 0
    var elapsed: TimeInterval = 0
    var eta: Int64 = -1
    var connections = 0
    var pieces: [Piece] = []
    var history: [Double] = []
}

struct AddRequest {
    var url: String
    var fileName = ""
    var headers = ""
    var cookies: String?
    var userAgent: String?
    var referer: String?
    var connections = 0
    var sequential = false
    var checksumType: String?
    var expectedChecksum: String?
    var startPaused = false
}
