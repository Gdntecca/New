import Foundation
import UserNotifications
import CryptoKit
import Compression
import SDCore
#if canImport(ActivityKit)
import ActivityKit
#endif
#if canImport(WidgetKit)
import WidgetKit
#endif

// MARK: - Local notifications

enum Notifier {
    static let changeLinkAction = "CHANGE_LINK"
    static let failedCategory = "FAILED_LINK"

    static func registerCategories() {
        let change = UNNotificationAction(identifier: changeLinkAction, title: "Change link", options: [.foreground])
        let cat = UNNotificationCategory(identifier: failedCategory, actions: [change], intentIdentifiers: [], options: [])
        UNUserNotificationCenter.current().setNotificationCategories([cat])
    }

    /// Asked only when the first download is added.
    static func requestPermissionIfNeeded() {
        UNUserNotificationCenter.current().getNotificationSettings { s in
            guard s.authorizationStatus == .notDetermined else { return }
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        }
    }

    static func completed(_ it: DownloadItem, sound: Bool) {
        let c = UNMutableNotificationContent()
        c.title = "Download complete"
        c.body = "\(it.fileName) · \(Fmt.bytes(it.totalBytes))" + (it.checksumResult.map { " · \($0)" } ?? "")
        if sound { c.sound = .default }
        c.userInfo = ["id": it.id.uuidString, "open": true]
        post(c, id: "done-\(it.id.uuidString)")
    }

    static func failed(_ it: DownloadItem) {
        let c = UNMutableNotificationContent()
        c.title = "Download failed"
        c.body = "\(it.displayName): \(it.error ?? "error")"
        c.sound = .default
        c.userInfo = ["id": it.id.uuidString]
        if LinkMatcher.looksExpired(it.error) { c.categoryIdentifier = failedCategory }
        post(c, id: "fail-\(it.id.uuidString)")
    }

    private static func post(_ c: UNMutableNotificationContent, id: String) {
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: c, trigger: nil))
    }
}

// MARK: - Shared state for the widget (App Group, when the signing setup provides one)

enum SharedState {
    static let group = "group.com.tzzzzgdnnn.superdownloader"
    private static var lastWrite = Date.distantPast

    static func write(items: [DownloadItem]) {
        guard Date().timeIntervalSince(lastWrite) > 5,
              let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { return }
        lastWrite = Date()
        let active = items.filter { $0.status == .downloading }
        let done = active.reduce(Int64(0)) { $0 + $1.downloadedBytes }
        let total = active.reduce(Int64(0)) { $0 + max($1.totalBytes, 0) }
        let snap = WidgetSnapshot(active: active.count, queued: items.filter { $0.status == .queued || $0.status == .waiting }.count,
                                  progress: total > 0 ? Double(done) / Double(total) : 0,
                                  title: active.first?.displayName ?? "", updated: Date())
        if let d = try? JSONEncoder().encode(snap) {
            try? d.write(to: dir.appendingPathComponent("widget.json"), options: .atomic)
            #if canImport(WidgetKit)
            WidgetCenter.shared.reloadAllTimelines()
            #endif
        }
    }
}

// MARK: - Live Activity (iOS 16.1+)

final class LiveActivityController {
    static let shared = LiveActivityController()
    private var activityRef: Any?
    private var lastUpdate = Date.distantPast

    func refresh(active: Int) {
        DispatchQueue.main.async {
            if active == 0 { self.end() }
        }
    }

    func update(active: Int, speed: Int64, done: Int64, total: Int64, title: String) {
        DispatchQueue.main.async {
            guard Date().timeIntervalSince(self.lastUpdate) > 2 else { return }
            self.lastUpdate = Date()
            #if canImport(ActivityKit)
            if #available(iOS 16.1, *) {
                guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
                let state = DownloadActivityAttributes.ContentState(active: active, speed: speed, done: done, total: total, title: title)
                if let a = self.activityRef as? Activity<DownloadActivityAttributes> {
                    Task { await a.update(using: state) }
                } else if active > 0 {
                    self.activityRef = try? Activity.request(attributes: DownloadActivityAttributes(), contentState: state, pushType: nil)
                }
            }
            #endif
        }
    }

    private func end() {
        #if canImport(ActivityKit)
        if #available(iOS 16.1, *) {
            if let a = activityRef as? Activity<DownloadActivityAttributes> {
                Task { await a.end(dismissalPolicy: .immediate) }
            }
        }
        #endif
        activityRef = nil
    }
}

// MARK: - Checksums

enum Checksums {
    static func compute(url: URL, type: String) -> String? {
        guard let h = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? h.close() }
        if type.uppercased().contains("MD5") {
            var md5 = Insecure.MD5()
            while true {
                let d = h.readData(ofLength: 1024 * 1024)
                if d.isEmpty { break }
                md5.update(data: d)
            }
            return md5.finalize().map { String(format: "%02x", $0) }.joined()
        }
        var sha = SHA256()
        while true {
            let d = h.readData(ofLength: 1024 * 1024)
            if d.isEmpty { break }
            sha.update(data: d)
        }
        return sha.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - ZIP extraction (stored + deflate, streamed through the Compression framework)

enum ZipExtractor {
    struct ZipError: Error {}

    /// Extracts every entry into `dest`. Skips entries that try to leave the folder. Returns the entry count.
    static func extract(zip: URL, to dest: URL) throws -> Int {
        let fm = FileManager.default
        guard let h = try? FileHandle(forReadingFrom: zip) else { throw ZipError() }
        defer { try? h.close() }
        let size = (try fm.attributesOfItem(atPath: zip.path)[.size] as? NSNumber)?.uint64Value ?? 0
        // Find the end-of-central-directory record in the last 64 KB.
        let tailLen = min(size, 65_557)
        try h.seek(toOffset: size - tailLen)
        let tail = [UInt8](h.readData(ofLength: Int(tailLen)))
        guard tail.count >= 22, let eocd = stride(from: tail.count - 22, through: 0, by: -1).first(where: {
            tail[$0] == 0x50 && tail[$0 + 1] == 0x4B && tail[$0 + 2] == 0x05 && tail[$0 + 3] == 0x06
        }) else { throw ZipError() }
        func u16(_ b: [UInt8], _ o: Int) -> Int { Int(b[o]) | Int(b[o + 1]) << 8 }
        func u32(_ b: [UInt8], _ o: Int) -> UInt64 { UInt64(b[o]) | UInt64(b[o + 1]) << 8 | UInt64(b[o + 2]) << 16 | UInt64(b[o + 3]) << 24 }
        let count = u16(tail, eocd + 10)
        let cdOffset = u32(tail, eocd + 16)
        try h.seek(toOffset: cdOffset)
        var cd = [UInt8](h.readData(ofLength: Int(min(size - cdOffset, 64 * 1024 * 1024))))
        var p = 0
        var extracted = 0
        try fm.createDirectory(at: dest, withIntermediateDirectories: true)
        for _ in 0..<count {
            guard p + 46 <= cd.count, u32(cd, p) == 0x02014B50 else { break }
            let method = u16(cd, p + 10)
            let compSize = u32(cd, p + 20)
            let nameLen = u16(cd, p + 28), extraLen = u16(cd, p + 30), commentLen = u16(cd, p + 32)
            let localOffset = u32(cd, p + 42)
            let name = String(bytes: cd[(p + 46)..<(p + 46 + nameLen)], encoding: .utf8) ?? ""
            p += 46 + nameLen + extraLen + commentLen
            let parts = name.split(separator: "/").map(String.init)
            if name.hasSuffix("/") || parts.contains("..") || parts.isEmpty { continue }
            let target = parts.reduce(dest) { $0.appendingPathComponent(FileTypes.sanitize($1)) }
            try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try h.seek(toOffset: localOffset)
            let lh = [UInt8](h.readData(ofLength: 30))
            guard lh.count == 30, u32(lh, 0) == 0x04034B50 else { continue }
            let dataStart = localOffset + 30 + UInt64(u16(lh, 26)) + UInt64(u16(lh, 28))
            try h.seek(toOffset: dataStart)
            fm.createFile(atPath: target.path, contents: nil)
            guard let out = try? FileHandle(forWritingTo: target) else { continue }
            defer { try? out.close() }
            if method == 0 {
                var left = compSize
                while left > 0 {
                    let d = h.readData(ofLength: Int(min(left, 1024 * 1024)))
                    if d.isEmpty { break }
                    try out.write(contentsOf: d)
                    left -= UInt64(d.count)
                }
            } else if method == 8 {
                try inflate(from: h, count: compSize, to: out)
            } else {
                continue
            }
            extracted += 1
        }
        cd.removeAll()
        return extracted
    }

    /// Raw-deflate stream decode (COMPRESSION_ZLIB is raw deflate) with bounded memory.
    private static func inflate(from h: FileHandle, count: UInt64, to out: FileHandle) throws {
        let dstSize = 256 * 1024
        let dst = UnsafeMutablePointer<UInt8>.allocate(capacity: dstSize)
        defer { dst.deallocate() }
        let streamPtr = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { streamPtr.deallocate() }
        guard compression_stream_init(streamPtr, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else { throw ZipError() }
        defer { compression_stream_destroy(streamPtr) }
        var left = count
        var finished = false
        while !finished {
            let chunk = left > 0 ? h.readData(ofLength: Int(min(left, 256 * 1024))) : Data()
            left -= UInt64(chunk.count)
            let flags: Int32 = left == 0 ? Int32(COMPRESSION_STREAM_FINALIZE.rawValue) : 0
            try chunk.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                let base = raw.bindMemory(to: UInt8.self).baseAddress
                streamPtr.pointee.src_ptr = base ?? UnsafePointer(dst)
                streamPtr.pointee.src_size = chunk.count
                repeat {
                    streamPtr.pointee.dst_ptr = dst
                    streamPtr.pointee.dst_size = dstSize
                    let status = compression_stream_process(streamPtr, flags)
                    let produced = dstSize - streamPtr.pointee.dst_size
                    if produced > 0 { try out.write(contentsOf: Data(bytes: dst, count: produced)) }
                    if status == COMPRESSION_STATUS_END { finished = true; return }
                    if status == COMPRESSION_STATUS_ERROR { throw ZipError() }
                } while streamPtr.pointee.src_size > 0 || streamPtr.pointee.dst_size == 0
            }
            if left == 0 && chunk.isEmpty && !finished { break }
        }
    }
}
