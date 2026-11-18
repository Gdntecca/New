import Foundation
import Network
import UIKit
import SDCore

/// The download engine. All state lives on one serial queue (`q`); URLSession delegate
/// callbacks are delivered on that same queue, so no locks are needed.
final class Engine: NSObject {
    static let shared = Engine()
    static let backgroundIdentifier = "com.tzzzzgdnnn.superdownloader.background"

    let q = DispatchQueue(label: "sd.engine", qos: .userInitiated)
    private lazy var delegateQueue: OperationQueue = {
        let o = OperationQueue()
        o.maxConcurrentOperationCount = 1
        o.underlyingQueue = q
        return o
    }()

    // MARK: State (only touched on q)
    private(set) var items: [UUID: DownloadItem] = [:]
    private var jobs: [UUID: Job] = [:]
    private var taskMap: [Int: TaskRef] = [:]
    private var bgPending: [UUID: Int] = [:]           // background tasks still running per item
    private var session: URLSession!
    private var sessionKey = ""
    private(set) lazy var bgSession: URLSession = {
        let c = URLSessionConfiguration.background(withIdentifier: Engine.backgroundIdentifier)
        c.isDiscretionary = false
        c.sessionSendsLaunchEvents = true
        c.httpMaximumConnectionsPerHost = 16
        c.allowsCellularAccess = true
        return URLSession(configuration: c, delegate: self, delegateQueue: delegateQueue)
    }()
    private let bucket = TokenBucket()
    private let monitor = NWPathMonitor()
    private var pathOK = true
    private var pathUnmetered = true
    private var timer: DispatchSourceTimer?
    private var scheduleTimer: DispatchSourceTimer?
    private var isForeground = true
    private var persistPending = false
    private var tickCount = 0
    private(set) var blockedReason: String?
    var backgroundCompletion: (() -> Void)?
    let isLowMemory = ProcessInfo.processInfo.physicalMemory < 3 * 1024 * 1024 * 1024

    /// UI hooks (called on the main queue).
    var onItems: (([DownloadItem]) -> Void)?
    var onLive: ((UUID, LiveStats?) -> Void)?
    var onBlocked: ((String?) -> Void)?
    var onEvent: ((String) -> Void)?

    private static let bufSize = 512 * 1024
    private static let minSplit: Int64 = 2 * 1024 * 1024
    private static let minPart: Int64 = 512 * 1024
    private static let seqChunk: Int64 = 2 * 1024 * 1024

    private enum StopReason { case pause, wait, cancel, delete }

    private final class Seg {
        var start: Int64
        var end: Int64
        var downloaded: Int64
        var active = false
        var conn = 0
        var task: URLSessionDataTask?
        var buffer = Data()
        var bufferStart: Int64 = 0
        var lastSample: Int64 = 0
        var speed: Double = 0
        var retries = 0
        var handedOff = false
        var httpError: Int?
        init(start: Int64, end: Int64, downloaded: Int64) { self.start = start; self.end = end; self.downloaded = downloaded }
        var pos: Int64 { start + downloaded }
        var remaining: Int64 { SegmentPlanner.remaining(start: start, downloaded: downloaded, end: end) }
    }

    private final class Job {
        let id: UUID
        var item: DownloadItem
        var segs: [Seg] = []
        var handle: FileHandle?
        var fileURL: URL?
        var stop: StopReason?
        var activeWorkers = 0
        var connCounter = 0
        var cursor: Int64 = 0
        var probeData = Data()
        var probeResponse: HTTPURLResponse?
        var probing = false
        var probeTask: URLSessionDataTask?
        var handingOff = false
        var failMessage: String?
        var ema: Double = 0
        var lastBytes: Int64 = 0
        var lastTick = Date()
        var sessionStart = Date()
        var baseElapsed: TimeInterval = 0
        var history: [Double] = []
        var lastHistory = Date()
        var adaptBaseline: Double = 0
        var adaptStopped = false
        var lastAdapt = Date()
        init(item: DownloadItem) { id = item.id; self.item = item; baseElapsed = item.elapsed }
        var downloaded: Int64 { segs.reduce(0) { $0 + $1.downloaded } }
        var total: Int64 { item.totalBytes }
    }

    private enum TaskRef {
        case probe(Job)
        case segment(Job, Seg)
    }

    // MARK: Lifecycle

    override init() {
        super.init()
        q.sync { load() }
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            self.q.async {
                self.pathOK = path.status == .satisfied
                self.pathUnmetered = !path.isExpensive && !path.isConstrained
                self.session?.flush {}
                self.schedule()
            }
        }
        monitor.start(queue: q)
        NotificationCenter.default.addObserver(forName: ProcessInfo.powerStateDidChangeNotification, object: nil, queue: nil) { [weak self] _ in
            self?.q.async { self?.schedule() }
        }
        q.async {
            self.rebuildSessionIfNeeded(force: true)
            _ = self.bgSession
            self.bgSession.getAllTasks { tasks in
                self.q.async {
                    var counts: [UUID: Int] = [:]
                    for t in tasks {
                        if let d = t.taskDescription, let id = UUID(uuidString: String(d.split(separator: "|").first ?? "")) {
                            counts[id, default: 0] += 1
                        }
                    }
                    self.bgPending = counts
                    for (id, var it) in self.items where it.status == .downloading && counts[id] == nil {
                        it.status = .queued
                        self.items[id] = it
                    }
                    self.publishItems()
                    self.schedule()
                }
            }
        }
    }

    private var storeURL: URL { Paths.support.appendingPathComponent("downloads.json") }

    private func load() {
        guard let d = try? Data(contentsOf: storeURL), let list = try? JSONDecoder().decode([DownloadItem].self, from: d) else { return }
        for it in list { items[it.id] = it }
    }

    private func persistSoon() {
        if persistPending { return }
        persistPending = true
        q.asyncAfter(deadline: .now() + 1.5) { self.persistNow() }
    }

    private func persistNow() {
        persistPending = false
        for (id, job) in jobs { syncSegments(job); items[id] = job.item }
        let list = Array(items.values)
        if let d = try? JSONEncoder().encode(list) { try? d.write(to: storeURL, options: .atomic) }
    }

    private func publishItems() {
        let list = items.values.sorted { $0.createdAt > $1.createdAt }
        DispatchQueue.main.async { self.onItems?(list) }
        SharedState.write(items: list)
    }

    private var cachedSettings = AppSettings()
    func settingsChanged(_ s: AppSettings) {
        q.async {
            self.cachedSettings = s
            self.bucket.bytesPerSecond = s.limitBytesPerSec
            self.rebuildSessionIfNeeded(force: false)
            self.armScheduleTimer()
            self.schedule()
        }
    }

    private func rebuildSessionIfNeeded(force: Bool) {
        let s = cachedSettings
        let key = "\(s.proxyType)|\(s.proxyHost)|\(s.proxyPort)|\(effectivePerServer(s))"
        if !force && (key == sessionKey || !jobs.isEmpty) { return }
        sessionKey = key
        let c = URLSessionConfiguration.default
        c.httpMaximumConnectionsPerHost = effectivePerServer(s)
        c.timeoutIntervalForRequest = 30
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.urlCache = nil
        c.httpShouldUsePipelining = true
        if s.proxyType != 0, !s.proxyHost.isEmpty, (1...65535).contains(s.proxyPort) {
            if s.proxyType == 2 {
                c.connectionProxyDictionary = ["SOCKSEnable": 1, "SOCKSProxy": s.proxyHost, "SOCKSPort": s.proxyPort]
            } else {
                c.connectionProxyDictionary = ["HTTPEnable": 1, "HTTPProxy": s.proxyHost, "HTTPPort": s.proxyPort,
                                               "HTTPSEnable": 1, "HTTPSProxy": s.proxyHost, "HTTPSPort": s.proxyPort]
            }
        }
        session?.finishTasksAndInvalidate()
        session = URLSession(configuration: c, delegate: self, delegateQueue: delegateQueue)
    }

    func effectiveParts(_ s: AppSettings) -> Int { isLowMemory ? min(max(s.parts, 1), 4) : min(max(s.parts, 1), 16) }
    func effectiveSimultaneous(_ s: AppSettings) -> Int { isLowMemory ? min(max(s.maxSimultaneous, 1), 2) : max(s.maxSimultaneous, 1) }
    func effectivePerServer(_ s: AppSettings) -> Int { isLowMemory ? min(max(s.maxPerServer, 1), 4) : min(max(s.maxPerServer, 1), 16) }

    // MARK: Scheduling

    private func inWindow(_ s: AppSettings) -> Bool {
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let m = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        let a = s.scheduleStart, b = s.scheduleStop
        if a == b { return true }
        return a < b ? (m >= a && m < b) : (m >= a || m < b)
    }

    private func computeBlock(_ s: AppSettings) -> String? {
        if !pathOK { return "Waiting for network" }
        if s.wifiOnly && !pathUnmetered { return "Waiting for Wi‑Fi" }
        if s.pauseInLowPower && ProcessInfo.processInfo.isLowPowerModeEnabled { return "Paused: Low Power Mode is on" }
        if s.scheduleEnabled && !inWindow(s) { return String(format: "Scheduled: starts at %02d:%02d", s.scheduleStart / 60, s.scheduleStart % 60) }
        return nil
    }

    /// One-shot timer to the next schedule boundary (no polling).
    private func armScheduleTimer() {
        scheduleTimer?.cancel(); scheduleTimer = nil
        let s = cachedSettings
        guard s.scheduleEnabled else { return }
        let now = Date()
        func next(_ minute: Int) -> Date {
            var c = Calendar.current.dateComponents([.year, .month, .day], from: now)
            c.hour = minute / 60; c.minute = minute % 60; c.second = 0
            var d = Calendar.current.date(from: c) ?? now
            if d <= now { d = d.addingTimeInterval(86400) }
            return d
        }
        let t = min(next(s.scheduleStart), next(s.scheduleStop))
        let timer = DispatchSource.makeTimerSource(queue: q)
        timer.schedule(deadline: .now() + t.timeIntervalSince(now) + 1)
        timer.setEventHandler { [weak self] in self?.schedule(); self?.armScheduleTimer() }
        timer.resume()
        scheduleTimer = timer
    }

    func kick() { q.async { self.schedule() } }

    private func schedule() {
        let s = cachedSettings
        let reason = computeBlock(s)
        if reason != blockedReason {
            blockedReason = reason
            DispatchQueue.main.async { self.onBlocked?(reason) }
        }
        if reason != nil {
            for job in jobs.values { stopJob(job, .wait) }
            return
        }
        let running = jobs.count + bgPending.count
        let max = effectiveSimultaneous(s)
        guard running < max else { return }
        let queued = items.values
            .filter { ($0.status == .queued || $0.status == .waiting) && jobs[$0.id] == nil && bgPending[$0.id] == nil }
            .sorted { $0.queueOrder < $1.queueOrder }
        for it in queued.prefix(max - running) { startJob(it) }
    }

    // MARK: Public API (safe from any thread)

    func add(_ r: AddRequest, completion: ((UUID) -> Void)? = nil) {
        q.async {
            let s = self.cachedSettings
            var it = DownloadItem(url: r.url.trimmingCharacters(in: .whitespacesAndNewlines))
            it.fileName = r.fileName.trimmingCharacters(in: .whitespaces)
            it.category = FileTypes.category(forName: it.fileName.isEmpty ? (FileTypes.name(fromURL: URL(string: it.url)) ?? "") : it.fileName)
            it.headers = r.headers
            it.cookies = r.cookies
            it.userAgent = r.userAgent
            it.referer = r.referer
            let conns = (1...16).contains(r.connections) ? r.connections : self.effectiveParts(s)
            it.maxConnections = self.isLowMemory ? min(conns, 4) : conns
            it.sequential = r.sequential
            it.checksumType = r.checksumType
            it.expectedChecksum = r.expectedChecksum?.trimmingCharacters(in: .whitespaces).nilIfEmpty
            it.status = r.startPaused ? .paused : .queued
            it.appendLog("Added")
            self.items[it.id] = it
            self.publishItems()
            self.persistSoon()
            self.schedule()
            let id = it.id
            DispatchQueue.main.async { completion?(id) }
        }
    }

    func pause(_ id: UUID) {
        q.async {
            if let job = self.jobs[id] { self.stopJob(job, .pause) }
            else if var it = self.items[id], it.status == .queued || it.status == .waiting {
                it.status = .paused; self.items[id] = it; self.publishItems(); self.persistSoon()
            }
        }
    }

    func resume(_ id: UUID) {
        q.async {
            guard var it = self.items[id], it.status != .completed, self.jobs[id] == nil, self.bgPending[id] == nil else { return }
            it.status = .queued; it.error = nil
            self.items[id] = it
            self.publishItems(); self.persistSoon(); self.schedule()
        }
    }

    func pauseAll() { q.async { for id in self.items.keys { self.pause(id) } } }

    func resumeAll() {
        q.async {
            for (id, var it) in self.items where it.status == .paused || it.status == .failed {
                it.status = .queued; it.error = nil; self.items[id] = it
            }
            self.publishItems(); self.persistSoon(); self.schedule()
        }
    }

    /// Removes the entry. With deleteFile the file on disk goes too.
    func remove(_ id: UUID, deleteFile: Bool) {
        q.async {
            if let job = self.jobs[id] { self.stopJob(job, .delete) }
            if self.bgPending[id] != nil {
                self.bgSession.getAllTasks { tasks in
                    tasks.filter { $0.taskDescription?.hasPrefix(id.uuidString) == true }.forEach { $0.cancel() }
                }
                self.bgPending[id] = nil
            }
            if deleteFile, let it = self.items[id], let url = Paths.fileURL(for: it) { try? FileManager.default.removeItem(at: url) }
            self.items[id] = nil
            self.jobs[id] = nil
            DispatchQueue.main.async { self.onLive?(id, nil) }
            self.publishItems(); self.persistSoon(); self.schedule()
        }
    }

    func move(_ id: UUID, by dir: Int) {
        q.async {
            let list = self.items.values.filter { $0.status != .completed }.sorted { $0.queueOrder < $1.queueOrder }
            guard let i = list.firstIndex(where: { $0.id == id }) else { return }
            let j = i + dir
            guard j >= 0, j < list.count else { return }
            let a = list[i], b = list[j]
            self.items[a.id]?.queueOrder = b.queueOrder
            self.items[b.id]?.queueOrder = a.queueOrder == b.queueOrder ? a.queueOrder + Double(dir) : a.queueOrder
            self.publishItems(); self.persistSoon(); self.schedule()
        }
    }

    func makePrivate(_ id: UUID, completion: @escaping (Bool) -> Void) {
        q.async {
            guard var it = self.items[id], it.status == .completed, !it.isPrivate, let src = Paths.fileURL(for: it) else {
                DispatchQueue.main.async { completion(false) }; return
            }
            let dir = Paths.privateRoot.appendingPathComponent(it.category, isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let name = Paths.uniqueName(it.fileName, in: dir)
            do {
                try FileManager.default.moveItem(at: src, to: dir.appendingPathComponent(name))
                it.isPrivate = true
                it.relativePath = "\(it.category)/\(name)"
                self.items[id] = it
                self.publishItems(); self.persistSoon()
                DispatchQueue.main.async { completion(true) }
            } catch {
                DispatchQueue.main.async { completion(false) }
            }
        }
    }

    /// Imports a file the browser already saved (blob:/data: downloads) as a finished item.
    func importFile(at tmp: URL, suggestedName: String?, mime: String?, source: String, referer: String?) {
        q.async {
            let head = (try? FileHandle(forReadingFrom: tmp)).map { h -> Data in defer { try? h.close() }; return h.readData(ofLength: 40000) } ?? Data()
            let r = FileTypes.resolve(contentDisposition: nil, contentType: mime, url: URL(string: source), head: head, override: suggestedName)
            guard let rel = try? Paths.create(name: r.name, category: r.category, policy: self.cachedSettings.conflictPolicy, isPrivate: false) else { return }
            let dest = Paths.documents.appendingPathComponent(rel)
            try? FileManager.default.removeItem(at: dest)
            do { try FileManager.default.moveItem(at: tmp, to: dest) } catch { return }
            let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? NSNumber)?.int64Value ?? 0
            var it = DownloadItem(url: source)
            it.fileName = (rel as NSString).lastPathComponent
            it.mimeType = r.mime; it.category = r.category
            it.totalBytes = size; it.downloadedBytes = size
            it.status = .completed; it.completedAt = Date()
            it.relativePath = rel; it.referer = referer
            it.appendLog("Saved from page data (\(Fmt.bytes(size)))")
            self.items[it.id] = it
            self.publishItems(); self.persistSoon()
            Notifier.completed(it, sound: self.cachedSettings.notifySound)
        }
    }

    func knownInfo(_ it: DownloadItem) -> RemoteInfo {
        RemoteInfo(total: it.totalBytes, etag: it.etag, lastModified: it.lastModified,
                   fileName: it.serverName ?? it.fileName.nilIfEmpty, supportsRange: it.supportsRange)
    }

    func item(_ id: UUID) -> DownloadItem? { q.sync { items[id] } }

    /// Asks a server about a link: HEAD first, then a one-byte range GET.
    func inspect(url: String, like it: DownloadItem, headers: String, completion: @escaping (Result<RemoteInfo, Error>) -> Void) {
        guard let u = URL(string: url) else {
            completion(.failure(NSError(domain: "SD", code: 2, userInfo: [NSLocalizedDescriptionKey: "Not a valid link"]))); return
        }
        var base = it; base.headers = headers
        var head = request(for: base, url: u); head.httpMethod = "HEAD"
        URLSession.shared.dataTask(with: head) { _, resp, _ in
            let h = resp as? HTTPURLResponse
            if let h = h, (200..<300).contains(h.statusCode), h.expectedContentLength > 0 {
                completion(.success(RemoteInfo(total: h.expectedContentLength, etag: h.value(forHTTPHeaderField: "ETag"),
                                               lastModified: h.value(forHTTPHeaderField: "Last-Modified"),
                                               fileName: FileTypes.parseContentDisposition(h.value(forHTTPHeaderField: "Content-Disposition")),
                                               supportsRange: h.value(forHTTPHeaderField: "Accept-Ranges")?.lowercased().contains("bytes") == true,
                                               finalURL: h.url)))
                return
            }
            var get = self.request(for: base, url: u)
            get.setValue("bytes=0-0", forHTTPHeaderField: "Range")
            URLSession.shared.dataTask(with: get) { _, resp2, err in
                guard let r = resp2 as? HTTPURLResponse else {
                    completion(.failure(err ?? NSError(domain: "SD", code: 3, userInfo: [NSLocalizedDescriptionKey: "No response"]))); return
                }
                guard (200..<300).contains(r.statusCode) else {
                    completion(.failure(NSError(domain: "SD", code: r.statusCode, userInfo: [NSLocalizedDescriptionKey: "HTTP \(r.statusCode)"]))); return
                }
                let total = r.statusCode == 206 ? LinkMatcher.totalFromContentRange(r.value(forHTTPHeaderField: "Content-Range")) : r.expectedContentLength
                completion(.success(RemoteInfo(total: total, etag: r.value(forHTTPHeaderField: "ETag") ?? h?.value(forHTTPHeaderField: "ETag"),
                                               lastModified: r.value(forHTTPHeaderField: "Last-Modified") ?? h?.value(forHTTPHeaderField: "Last-Modified"),
                                               fileName: FileTypes.parseContentDisposition(r.value(forHTTPHeaderField: "Content-Disposition")),
                                               supportsRange: r.statusCode == 206, finalURL: r.url)))
            }.resume()
        }.resume()
    }

    /// Swaps a download's link. Keeps finished pieces unless `restart` is true.
    func changeLink(_ id: UUID, url: String, headers: String, info: RemoteInfo?, restart: Bool) {
        q.async {
            if let job = self.jobs[id] { self.stopJob(job, .pause) }
            self.q.async {
                guard var it = self.items[id] else { return }
                it.url = url
                it.headers = headers
                it.status = .queued
                it.error = nil
                if restart {
                    if let f = Paths.fileURL(for: it) { try? FileManager.default.removeItem(at: f) }
                    it.relativePath = nil; it.segments = []; it.downloadedBytes = 0; it.totalBytes = -1
                    it.etag = nil; it.lastModified = nil; it.serverName = nil
                    it.appendLog("Link changed; restarting from zero")
                } else {
                    if let e = info?.etag { it.etag = e }
                    if let l = info?.lastModified { it.lastModified = l }
                    it.appendLog("Link changed; resuming from \(Fmt.bytes(it.downloadedBytes))")
                }
                self.items[id] = it
                self.publishItems(); self.persistSoon(); self.schedule()
            }
        }
    }

    /// A failed or paused download that a freshly found link could resume.
    func findResumable(name: String, info: RemoteInfo) -> DownloadItem? {
        q.sync {
            items.values.first { it in
                (it.status == .failed || it.status == .paused) && it.totalBytes > 0 &&
                    (it.fileName.caseInsensitiveCompare(name) == .orderedSame || it.serverName?.caseInsensitiveCompare(name) == .orderedSame) &&
                    LinkMatcher.match(old: knownInfo(it), new: info) == .same
            }
        }
    }

    func exportJSON() -> Data {
        q.sync {
            let list = items.values.sorted { $0.createdAt < $1.createdAt }.map { it -> [String: Any] in
                ["url": it.url, "fileName": it.fileName, "status": it.status.label, "totalBytes": it.totalBytes,
                 "headers": it.headers, "referer": it.referer ?? "", "userAgent": it.userAgent ?? "",
                 "sequential": it.sequential, "connections": it.maxConnections]
            }
            let root: [String: Any] = ["app": "Super Downloader", "version": 1, "downloads": list]
            return (try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted])) ?? Data()
        }
    }

    func importJSON(_ data: Data) -> Int {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = root["downloads"] as? [[String: Any]] else { return -1 }
        let existing = Set(q.sync { items.values.map { $0.url } })
        var n = 0
        for o in list {
            guard let url = o["url"] as? String, !url.isEmpty, !existing.contains(url) else { continue }
            var r = AddRequest(url: url)
            r.fileName = o["fileName"] as? String ?? ""
            r.headers = o["headers"] as? String ?? ""
            r.referer = (o["referer"] as? String)?.nilIfEmpty
            r.userAgent = (o["userAgent"] as? String)?.nilIfEmpty
            r.sequential = o["sequential"] as? Bool ?? false
            r.connections = o["connections"] as? Int ?? 0
            r.startPaused = true
            add(r)
            n += 1
        }
        return n
    }

    // MARK: Foreground / background

    private final class TaskBox { var id: UIBackgroundTaskIdentifier = .invalid }

    /// Called on the main queue when the app goes to the background.
    func didEnterBackground() {
        let box = TaskBox()
        let end = { DispatchQueue.main.async { if box.id != .invalid { UIApplication.shared.endBackgroundTask(box.id); box.id = .invalid } } }
        box.id = UIApplication.shared.beginBackgroundTask(withName: "downloads") { [weak self] in
            // Time is up: move every remaining piece to the background session.
            self?.q.sync { self?.handOffAll() }
            if box.id != .invalid { UIApplication.shared.endBackgroundTask(box.id); box.id = .invalid }
        }
        q.async {
            self.isForeground = false
            self.stopTimer()
            self.persistNow()
            guard !self.jobs.isEmpty else { end(); return }
            // Keep full multi-connection speed while iOS allows, then hand off.
            self.q.asyncAfter(deadline: .now() + 20) {
                if !self.isForeground { self.handOffAll() }
                end()
            }
        }
    }

    func willEnterForeground() {
        q.async {
            self.isForeground = true
            if !self.jobs.isEmpty { self.startTimer() }
            self.publishItems()
            self.schedule()
        }
    }

    /// Hands every remaining piece of every running job to background URLSession tasks,
    /// one Range request per piece (same connection count), written into place on completion.
    private func handOffAll() {
        for job in Array(jobs.values) where job.stop == nil && !job.probing {
            job.handingOff = true
            flushAll(job)
            if job.item.supportsRange && job.total > 0 {
                if job.item.sequential && job.cursor < job.total {
                    job.segs.append(Seg(start: job.cursor, end: job.total - 1, downloaded: 0))
                    job.cursor = job.total
                }
                for seg in job.segs where seg.remaining > 0 && seg.end >= 0 {
                    seg.handedOff = true
                    seg.task?.cancel()
                    seg.task = nil
                    var req = request(for: job.item, url: URL(string: job.item.url)!)
                    req.setValue("bytes=\(seg.pos)-\(seg.end)", forHTTPHeaderField: "Range")
                    let t = bgSession.downloadTask(with: req)
                    t.taskDescription = "\(job.id.uuidString)|\(seg.start)|\(seg.pos)|\(seg.end)"
                    t.resume()
                    bgPending[job.id, default: 0] += 1
                }
            } else if let seg = job.segs.first {
                seg.handedOff = true
                seg.task?.cancel(); seg.task = nil
                seg.downloaded = 0
                let t = bgSession.downloadTask(with: request(for: job.item, url: URL(string: job.item.url)!))
                t.taskDescription = "\(job.id.uuidString)|0|0|-1"
                t.resume()
                bgPending[job.id, default: 0] += 1
            }
            syncSegments(job)
            job.item.appendLog("Moved \(bgPending[job.id] ?? 0) piece(s) to background transfer")
            items[job.id] = job.item
            try? job.handle?.close()
            job.handle = nil
            jobs[job.id] = nil
        }
        persistNow()
        publishItems()
    }

    // MARK: Jobs

    private func startJob(_ it0: DownloadItem) {
        var it = it0
        it.status = .downloading
        it.error = nil
        it.appendLog("Started")
        items[it.id] = it
        let job = Job(item: it)
        jobs[it.id] = job
        publishItems()
        LiveActivityController.shared.refresh(active: jobs.count)
        if isForeground { startTimer() }
        if it.relativePath == nil { probe(job) } else { run(job) }
    }

    func request(for it: DownloadItem, url: URL) -> URLRequest {
        var r = URLRequest(url: url)
        r.cachePolicy = .reloadIgnoringLocalCacheData
        let ua = it.userAgent?.nilIfEmpty ?? cachedSettings.userAgent.nilIfEmpty ?? Engine.defaultUA
        r.setValue(ua, forHTTPHeaderField: "User-Agent")
        r.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        if let ref = it.referer?.nilIfEmpty { r.setValue(ref, forHTTPHeaderField: "Referer") }
        if let c = it.cookies?.nilIfEmpty { r.setValue(c, forHTTPHeaderField: "Cookie") }
        for line in it.headers.split(whereSeparator: \.isNewline) {
            guard let i = line.firstIndex(of: ":") else { continue }
            let k = line[..<i].trimmingCharacters(in: .whitespaces)
            let v = line[line.index(after: i)...].trimmingCharacters(in: .whitespaces)
            if !k.isEmpty { r.setValue(v, forHTTPHeaderField: k) }
        }
        return r
    }

    static let defaultUA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"

    private func probe(_ job: Job) {
        guard let u = URL(string: job.item.url) else { fail(job, "Not a valid link"); return }
        job.probing = true
        job.item.appendLog("Connecting to \(u.host ?? "server")")
        var req = request(for: job.item, url: u)
        req.setValue("bytes=0-", forHTTPHeaderField: "Range")
        let t = session.dataTask(with: req)
        taskMap[t.taskIdentifier] = .probe(job)
        job.probeTask = t
        t.resume()
    }

    private func probeFinished(_ job: Job, error: Error?) {
        job.probing = false
        if job.stop != nil { stopped(job); return }
        guard let r = job.probeResponse else { fail(job, error?.localizedDescription ?? "No response"); return }
        guard (200..<300).contains(r.statusCode) else { fail(job, "HTTP \(r.statusCode)"); return }
        let range = r.statusCode == 206
        let total: Int64 = range ? LinkMatcher.totalFromContentRange(r.value(forHTTPHeaderField: "Content-Range")) : r.expectedContentLength
        let cd = r.value(forHTTPHeaderField: "Content-Disposition")
        let res = FileTypes.resolve(contentDisposition: cd, contentType: r.value(forHTTPHeaderField: "Content-Type"),
                                    url: r.url, head: job.probeData, override: job.item.fileName.nilIfEmpty)
        job.item.appendLog("Server: \(range ? "supports ranges" : "single stream"), size \(total > 0 ? Fmt.bytes(total) : "unknown"), type \(res.mime)")
        if total > 0 && Paths.freeBytes() < total {
            job.item.warning = "Low storage: \(Fmt.bytes(Paths.freeBytes())) free, file is \(Fmt.bytes(total))"
            job.item.appendLog(job.item.warning!)
        }
        do {
            guard let rel = try Paths.create(name: res.name, category: res.category, policy: cachedSettings.conflictPolicy, isPrivate: job.item.isPrivate) else {
                job.item.fileName = res.name
                fail(job, "Skipped: file already exists")
                return
            }
            job.item.relativePath = rel
            job.item.fileName = (rel as NSString).lastPathComponent
        } catch {
            fail(job, error.localizedDescription); return
        }
        job.item.mimeType = res.mime
        job.item.category = res.category
        job.item.totalBytes = total
        job.item.supportsRange = range
        job.item.etag = r.value(forHTTPHeaderField: "ETag")
        job.item.lastModified = r.value(forHTTPHeaderField: "Last-Modified")
        job.item.serverName = FileTypes.parseContentDisposition(cd)
        job.item.segments = []
        if range && total > 0 && !job.item.sequential {
            job.item.segments = SegmentPlanner.initialSplit(total: total, parts: allowedWorkers(job), minPart: Engine.minPart)
                .map { Segment(start: $0.start, end: $0.end, downloaded: 0) }
        }
        job.item.appendLog("Saving as \(job.item.fileName)")
        items[job.id] = job.item
        publishItems()
        run(job)
    }

    private func allowedWorkers(_ job: Job) -> Int {
        min(max(job.item.maxConnections, 1), effectivePerServer(cachedSettings), 16)
    }

    private func run(_ job: Job) {
        guard let url = Paths.fileURL(for: job.item) else { fail(job, "Missing output file"); return }
        if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil) }
        do { job.handle = try FileHandle(forUpdating: url) } catch { fail(job, "Cannot open file: \(error.localizedDescription)"); return }
        job.fileURL = url
        let range = job.item.supportsRange && job.total > 0
        if range {
            job.segs = job.item.segments.map { Seg(start: $0.start, end: $0.end, downloaded: $0.downloaded) }
            job.cursor = job.segs.map { $0.end + 1 }.max() ?? 0
            if !job.item.sequential && job.segs.isEmpty { job.segs = [Seg(start: 0, end: job.total - 1, downloaded: 0)] }
            if job.downloaded == 0 { try? job.handle?.truncate(atOffset: UInt64(job.total)) }   // preallocate
        } else {
            job.segs = [Seg(start: 0, end: job.total > 0 ? job.total - 1 : -1, downloaded: 0)]
            try? job.handle?.truncate(atOffset: 0)
        }
        job.lastBytes = job.downloaded
        job.sessionStart = Date()
        let workers = range ? allowedWorkers(job) : 1
        job.item.appendLog("Using \(workers) connection(s)")
        for _ in 0..<workers { startWorker(job) }
        if job.activeWorkers == 0 { finish(job) }
    }

    private func startWorker(_ job: Job) {
        guard let seg = nextSegment(job) else { return }
        job.activeWorkers += 1
        launch(job, seg)
    }

    private func nextSegment(_ job: Job) -> Seg? {
        if let s = job.segs.filter({ !$0.active && !$0.handedOff && $0.remaining > 0 }).min(by: { $0.start < $1.start }) {
            job.connCounter += 1; s.active = true; s.conn = job.connCounter
            return s
        }
        let range = job.item.supportsRange && job.total > 0
        if range && job.item.sequential && job.cursor < job.total {
            let end = min(job.cursor + Engine.seqChunk - 1, job.total - 1)
            let s = Seg(start: job.cursor, end: end, downloaded: 0)
            job.connCounter += 1; s.active = true; s.conn = job.connCounter
            job.segs.append(s)
            job.cursor = end + 1
            return s
        }
        guard range else { return nil }
        guard let victim = job.segs.filter({ $0.active && $0.end >= 0 }).max(by: { $0.remaining < $1.remaining }),
              let mid = SegmentPlanner.stealPoint(start: victim.start, downloaded: victim.downloaded, end: victim.end, minSplit: Engine.minSplit)
        else { return nil }
        let s = Seg(start: mid, end: victim.end, downloaded: 0)
        victim.end = mid - 1
        job.connCounter += 1; s.active = true; s.conn = job.connCounter
        job.segs.append(s)
        return s
    }

    private func launch(_ job: Job, _ seg: Seg) {
        guard job.stop == nil, let u = URL(string: job.item.url) else { workerDone(job, seg); return }
        var req = request(for: job.item, url: u)
        if job.item.supportsRange {
            req.setValue(SegmentPlanner.rangeHeader(start: seg.start, downloaded: seg.downloaded, end: seg.end), forHTTPHeaderField: "Range")
        }
        seg.httpError = nil
        let t = session.dataTask(with: req)
        seg.task = t
        taskMap[t.taskIdentifier] = .segment(job, seg)
        t.resume()
    }

    private func flush(_ job: Job, _ seg: Seg) {
        guard !seg.buffer.isEmpty, let h = job.handle else { seg.buffer.removeAll(keepingCapacity: true); return }
        do {
            try h.seek(toOffset: UInt64(seg.bufferStart))
            try h.write(contentsOf: seg.buffer)
        } catch {
            job.item.appendLog("Write error: \(error.localizedDescription)")
        }
        seg.buffer.removeAll(keepingCapacity: true)
    }

    private func flushAll(_ job: Job) { for s in job.segs { flush(job, s) } }

    private func workerDone(_ job: Job, _ seg: Seg) {
        seg.active = false
        seg.task = nil
        seg.speed = 0
        job.activeWorkers -= 1
        if job.activeWorkers > 0 { return }
        if job.handingOff { return }
        if job.stop != nil { stopped(job) } else { finish(job) }
    }

    private func stopJob(_ job: Job, _ reason: StopReason) {
        if job.stop == nil { job.stop = reason }
        if job.probing {
            if let t = job.probeTask { taskMap[t.taskIdentifier] = nil; t.cancel() }
            job.probeTask = nil
            job.probing = false
            stopped(job)
            return
        }
        var idle: [Seg] = []
        for s in job.segs where s.active {
            if let t = s.task { t.cancel() } else { idle.append(s) }
        }
        for s in idle { workerDone(job, s) }
        if job.activeWorkers == 0 && idle.isEmpty { stopped(job) }
    }

    private func syncSegments(_ job: Job) {
        let range = job.item.supportsRange && job.total > 0
        job.item.segments = range ? job.segs.map { Segment(start: $0.start, end: $0.end, downloaded: $0.downloaded) } : []
        job.item.downloadedBytes = job.downloaded
        job.item.elapsed = job.baseElapsed + Date().timeIntervalSince(job.sessionStart)
    }

    private func stopped(_ job: Job) {
        guard jobs[job.id] === job else { return }
        if let m = job.failMessage { fail(job, m); return }
        flushAll(job)
        try? job.handle?.close()
        job.handle = nil
        jobs[job.id] = nil
        DispatchQueue.main.async { self.onLive?(job.id, nil) }
        if job.stop == .delete { refreshTimer(); return }
        syncSegments(job)
        switch job.stop ?? .pause {
        case .wait: job.item.status = .waiting; job.item.appendLog("Waiting (\(blockedReason ?? "blocked"))")
        case .cancel: job.item.status = .failed; job.item.error = "Cancelled"; job.item.appendLog("Cancelled")
        default: job.item.status = .paused; job.item.appendLog("Paused")
        }
        items[job.id] = job.item
        publishItems(); persistSoon(); refreshTimer()
        schedule()
    }

    private func fail(_ job: Job, _ message: String) {
        flushAll(job)
        try? job.handle?.close()
        job.handle = nil
        jobs[job.id] = nil
        DispatchQueue.main.async { self.onLive?(job.id, nil) }
        syncSegments(job)
        if !pathOK {
            job.item.status = .waiting
            job.item.appendLog("Network lost; will resume automatically")
        } else {
            job.item.status = .failed
            job.item.error = message
            job.item.appendLog("Failed: \(message)")
            Notifier.failed(job.item)
        }
        items[job.id] = job.item
        publishItems(); persistSoon(); refreshTimer()
        schedule()
    }

    private func finish(_ job: Job) {
        flushAll(job)
        try? job.handle?.synchronize()
        try? job.handle?.close()
        job.handle = nil
        let done = job.downloaded
        if job.total > 0 && done < job.total { fail(job, "Incomplete download (\(done) of \(job.total) bytes)"); return }
        jobs[job.id] = nil
        DispatchQueue.main.async { self.onLive?(job.id, nil) }
        if job.total <= 0 { job.item.totalBytes = done }
        syncSegments(job)
        completeItem(&job.item)
        items[job.id] = job.item
        publishItems(); persistSoon(); refreshTimer()
        schedule()
    }

    /// Shared finishing steps (foreground and background paths).
    private func completeItem(_ it: inout DownloadItem) {
        it.status = .completed
        it.downloadedBytes = it.totalBytes
        it.completedAt = Date()
        it.error = nil
        it.segments = []
        it.peakSpeed = max(it.peakSpeed, 0)
        it.appendLog("Completed: \(Fmt.bytes(it.totalBytes)) in \(Fmt.duration(Int64(it.elapsed)))")
        if let expected = it.expectedChecksum, let type = it.checksumType, let url = Paths.fileURL(for: it) {
            let h = Checksums.compute(url: url, type: type)
            it.checksumResult = (h?.caseInsensitiveCompare(expected) == .orderedSame) ? "\(type) OK" : "\(type) MISMATCH: \(h ?? "unreadable")"
            it.appendLog("Checksum: \(it.checksumResult!)")
        }
        if cachedSettings.autoExtractZip, FileTypes.ext(of: it.fileName) == "zip", let url = Paths.fileURL(for: it) {
            let dest = url.deletingPathExtension()
            let n = (try? ZipExtractor.extract(zip: url, to: dest)) ?? -1
            it.appendLog(n >= 0 ? "Extracted \(n) file(s)" : "ZIP extraction failed")
        }
        Notifier.completed(it, sound: cachedSettings.notifySound)
    }

    // MARK: Ticker (foreground only; stopped when backgrounded or idle)

    private func startTimer() {
        guard timer == nil else { return }
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now() + 0.25, repeating: 0.25, leeway: .milliseconds(50))
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
    }

    private func stopTimer() { timer?.cancel(); timer = nil }

    private func refreshTimer() {
        if jobs.isEmpty { stopTimer() }
        LiveActivityController.shared.refresh(active: jobs.count)
    }

    private func tick() {
        guard isForeground else { stopTimer(); return }
        tickCount += 1
        let now = Date()
        var totalSpeed: Int64 = 0
        var totalDone: Int64 = 0
        var totalSize: Int64 = 0
        for job in jobs.values {
            let bytes = job.downloaded
            let dt = max(now.timeIntervalSince(job.lastTick), 0.05)
            let inst = Double(max(bytes - job.lastBytes, 0)) / dt
            job.ema = job.ema == 0 ? inst : job.ema * 0.75 + inst * 0.25
            job.lastBytes = bytes
            job.lastTick = now
            job.item.peakSpeed = max(job.item.peakSpeed, Int64(job.ema))
            if now.timeIntervalSince(job.lastHistory) >= 1 {
                job.history.append(job.ema)
                if job.history.count > 90 { job.history.removeFirst() }
                job.lastHistory = now
            }
            var pieces: [LiveStats.Piece] = []
            for s in job.segs {
                let d = Double(s.downloaded - s.lastSample) / dt
                s.lastSample = s.downloaded
                s.speed = s.active ? s.speed * 0.75 + d * 0.25 : 0
                pieces.append(.init(start: s.start, end: s.end >= 0 ? s.end : max(s.pos - 1, s.start), downloaded: s.downloaded,
                                    active: s.active, speed: Int64(s.speed), conn: s.conn))
            }
            let elapsed = job.baseElapsed + now.timeIntervalSince(job.sessionStart)
            let eta: Int64 = job.total > 0 && job.ema > 1 ? Int64(Double(job.total - bytes) / job.ema) : -1
            let stats = LiveStats(downloaded: bytes, total: job.total, speed: Int64(job.ema),
                                  avgSpeed: elapsed > 0 ? Int64(Double(bytes) / elapsed) : 0, peakSpeed: job.item.peakSpeed,
                                  elapsed: elapsed, eta: eta, connections: job.segs.filter { $0.active }.count,
                                  pieces: pieces, history: job.history)
            let id = job.id
            DispatchQueue.main.async { self.onLive?(id, stats) }
            totalSpeed += Int64(job.ema); totalDone += bytes; totalSize += max(job.total, 0)

            // Adaptive connections: add one while total throughput keeps rising.
            if cachedSettings.adaptive && !job.adaptStopped && !isLowMemory && job.item.supportsRange && job.total > 0 &&
                now.timeIntervalSince(job.lastAdapt) >= 3 {
                job.lastAdapt = now
                let cap = effectivePerServer(cachedSettings)
                if job.activeWorkers < cap && job.total - bytes > 4 * Engine.minSplit {
                    if job.adaptBaseline == 0 || job.ema > job.adaptBaseline * 1.10 {
                        job.adaptBaseline = job.ema
                        startWorker(job)
                        job.item.appendLog("Adaptive: added a connection (\(job.activeWorkers) total)")
                    } else {
                        job.adaptStopped = true
                    }
                }
            }
        }
        if tickCount % 8 == 0 {
            persistNow()
            publishItems()
            LiveActivityController.shared.update(active: jobs.count, speed: totalSpeed, done: totalDone, total: totalSize,
                                                 title: jobs.values.first?.item.displayName ?? "")
        }
        if jobs.isEmpty { stopTimer() }
    }

    private func throttle(_ task: URLSessionTask, bytes: Int) {
        let wait = bucket.consume(bytes)
        if wait > 0.02 {
            task.suspend()
            q.asyncAfter(deadline: .now() + wait) { task.resume() }
        }
    }
}

// MARK: - URLSession delegate

extension Engine: URLSessionDataDelegate, URLSessionDownloadDelegate {
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let ref = taskMap[dataTask.taskIdentifier] else { completionHandler(.cancel); return }
        let http = response as? HTTPURLResponse
        switch ref {
        case .probe(let job):
            job.probeResponse = http
            completionHandler((http.map { (200..<300).contains($0.statusCode) } ?? false) ? .allow : .cancel)
        case .segment(let job, let seg):
            let code = http?.statusCode ?? 0
            if !(200..<300).contains(code) { seg.httpError = code; completionHandler(.cancel); return }
            if job.item.supportsRange && code != 206 && seg.pos > 0 { seg.httpError = -206; completionHandler(.cancel); return }
            seg.bufferStart = seg.pos
            completionHandler(.allow)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let ref = taskMap[dataTask.taskIdentifier] else { return }
        switch ref {
        case .probe(let job):
            job.probeData.append(data.prefix(max(0, 40000 - job.probeData.count)))
            if job.probeData.count >= 40000 { dataTask.cancel() }
        case .segment(let job, let seg):
            if job.stop != nil || seg.handedOff { return }
            let allowed = seg.end >= 0 ? Int(min(Int64(data.count), max(seg.remaining, 0))) : data.count
            if allowed <= 0 { dataTask.cancel(); return }
            if seg.buffer.isEmpty { seg.bufferStart = seg.pos }
            seg.buffer.append(allowed == data.count ? data : data.prefix(allowed))
            seg.downloaded += Int64(allowed)
            if seg.buffer.count >= Engine.bufSize { flush(job, seg) }
            if seg.end >= 0 && seg.remaining <= 0 { flush(job, seg); dataTask.cancel(); return }
            throttle(dataTask, bytes: allowed)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if session === bgSession { backgroundTaskCompleted(task, error: error); return }
        guard let ref = taskMap.removeValue(forKey: task.taskIdentifier) else { return }
        switch ref {
        case .probe(let job):
            probeFinished(job, error: (error as NSError?)?.code == NSURLErrorCancelled ? nil : error)
        case .segment(let job, let seg):
            flush(job, seg)
            seg.task = nil
            if seg.handedOff || job.handingOff { seg.active = false; job.activeWorkers -= 1; return }
            if job.stop != nil { workerDone(job, seg); return }
            let finished = seg.end >= 0 ? seg.remaining <= 0 : (error == nil && seg.httpError == nil)
            if finished {
                if seg.end < 0 { seg.end = seg.pos - 1; job.item.totalBytes = seg.pos }
                seg.retries = 0
                seg.active = false
                if let next = nextSegment(job) { launch(job, next); seg.speed = 0 } else { workerDone(job, seg) }
                return
            }
            // Error path: retry with exponential backoff, or fail.
            let msg: String
            if let code = seg.httpError { msg = code == -206 ? "Server stopped honouring ranges" : "HTTP \(code)" }
            else { msg = error?.localizedDescription ?? "Connection closed early" }
            let permanent = seg.httpError.map { $0 == -206 || ($0 >= 400 && $0 < 500 && $0 != 408 && $0 != 429) } ?? false
            seg.retries += 1
            let s = cachedSettings
            if permanent || seg.retries > s.retryCount {
                job.failMessage = msg
                job.stop = .cancel
                for other in job.segs where other.active && other !== seg { other.task?.cancel() }
                workerDone(job, seg)
                return
            }
            let delay = min(60, Double(max(s.retryDelaySec, 1)) * pow(2, Double(min(seg.retries - 1, 5))))
            job.item.appendLog("Connection \(seg.conn): \(msg); retry \(seg.retries) in \(Int(delay))s")
            if !job.item.supportsRange { seg.downloaded = 0; try? job.handle?.truncate(atOffset: 0) }
            q.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let self = self else { return }
                if job.stop != nil || self.jobs[job.id] !== job { if seg.active { self.workerDone(job, seg) }; return }
                self.launch(job, seg)
            }
        }
    }

    // MARK: Background session

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let parts = downloadTask.taskDescription?.split(separator: "|"), parts.count == 4,
              let id = UUID(uuidString: String(parts[0])),
              let segStart = Int64(parts[1]), let pos = Int64(parts[2]), let end = Int64(parts[3]),
              var it = items[id] else { return }
        let code = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code), let dest = Paths.fileURL(for: it) else { return }
        let fm = FileManager.default
        if end < 0 {
            try? fm.removeItem(at: dest)
            try? fm.moveItem(at: location, to: dest)
            let size = (try? fm.attributesOfItem(atPath: dest.path)[.size] as? NSNumber)?.int64Value ?? 0
            it.totalBytes = size
            it.downloadedBytes = size
            it.segments = []
        } else {
            // Write the piece into place at its offset (64-bit), in 1 MB chunks.
            guard let out = try? FileHandle(forUpdating: dest), let input = try? FileHandle(forReadingFrom: location) else { return }
            var offset = UInt64(pos)
            while true {
                let chunk = input.readData(ofLength: 1024 * 1024)
                if chunk.isEmpty { break }
                try? out.seek(toOffset: offset)
                try? out.write(contentsOf: chunk)
                offset += UInt64(chunk.count)
            }
            try? input.close(); try? out.close()
            if let i = it.segments.firstIndex(where: { $0.start == segStart }) {
                it.segments[i].downloaded = it.segments[i].end - it.segments[i].start + 1
            } else {
                it.segments.append(Segment(start: pos, end: end, downloaded: end - pos + 1))
            }
            it.downloadedBytes = it.segments.reduce(0) { $0 + $1.downloaded }
        }
        it.appendLog("Background piece finished (\(Fmt.bytes((end < 0 ? it.totalBytes : end - pos + 1))))")
        items[id] = it
    }

    private func backgroundTaskCompleted(_ task: URLSessionTask, error: Error?) {
        guard let parts = task.taskDescription?.split(separator: "|"), let id = UUID(uuidString: String(parts.first ?? "")) else { return }
        let left = max((bgPending[id] ?? 1) - 1, 0)
        bgPending[id] = left == 0 ? nil : left
        guard var it = items[id] else { return }
        if let error = error, (error as NSError).code != NSURLErrorCancelled {
            it.appendLog("Background transfer interrupted: \(error.localizedDescription)")
            if it.status == .downloading { it.error = error.localizedDescription }
        }
        if left == 0 && it.status == .downloading {
            let complete = it.totalBytes > 0 ? it.segments.allSatisfy({ $0.downloaded >= $0.end - $0.start + 1 }) && it.downloadedBytes >= it.totalBytes
                : it.downloadedBytes > 0 && it.error == nil
            if complete { completeItem(&it) } else { it.status = .queued }
        }
        items[id] = it
        persistNow()
        publishItems()
        if isForeground { schedule() }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        // Progress of background pieces: refresh the list now and then while visible.
        if isForeground && Int(totalBytesWritten / (1024 * 1024)) % 4 == 0 { publishItems() }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            self.backgroundCompletion?()
            self.backgroundCompletion = nil
        }
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
