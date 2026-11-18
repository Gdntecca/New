import Foundation
import SwiftUI
import UIKit
import SDCore

/// Per-row live stats, so a progress tick only redraws its own row.
final class LiveBox: ObservableObject {
    @Published var stats: LiveStats?
}

struct Snack: Identifiable, Equatable {
    let id = UUID()
    var message: String
    var actionLabel: String?
    var action: (() -> Void)?
    var onDismiss: (() -> Void)?
    static func == (a: Snack, b: Snack) -> Bool { a.id == b.id }
}

struct AddPrefill: Identifiable {
    let id = UUID()
    var urls: String
    var referer: String?
    var cookies: String?
    var userAgent: String?
    var fileName = ""
}

@MainActor
final class Store: ObservableObject {
    static let shared = Store()

    @Published private(set) var items: [DownloadItem] = []
    @Published var hidden: Set<UUID> = []
    @Published var blocked: String?
    @Published var snack: Snack?
    @Published var prefill: AddPrefill?
    @Published var changeLinkFor: UUID?
    @Published var openDetail: UUID?
    @Published var clipboardOffer: String?
    @Published var privateUnlocked = false
    private var boxes: [UUID: LiveBox] = [:]
    private var lastClipboard: String?
    let engine = Engine.shared

    private init() {
        engine.onItems = { [weak self] list in Task { @MainActor in self?.items = list } }
        engine.onLive = { [weak self] id, stats in Task { @MainActor in self?.box(id).stats = stats } }
        engine.onBlocked = { [weak self] r in Task { @MainActor in self?.blocked = r } }
        engine.onEvent = { [weak self] m in Task { @MainActor in self?.toast(m) } }
    }

    func box(_ id: UUID) -> LiveBox {
        if let b = boxes[id] { return b }
        let b = LiveBox()
        boxes[id] = b
        return b
    }

    func item(_ id: UUID) -> DownloadItem? { items.first { $0.id == id } }

    func toast(_ m: String) { snack = Snack(message: m) }

    // MARK: Actions

    func addAll(_ reqs: [AddRequest]) {
        guard !reqs.isEmpty else { return }
        Notifier.requestPermissionIfNeeded()
        reqs.forEach { engine.add($0) }
        toast(reqs.count == 1 ? "Download added" : "\(reqs.count) downloads added")
    }

    func duplicates(_ urls: [String]) -> [String] {
        let existing = Set(items.map { $0.url })
        return urls.filter { existing.contains($0) }
    }

    func pause(_ id: UUID) { engine.pause(id) }
    func resume(_ id: UUID) { engine.resume(id) }

    /// Delete: removes it from the list only (file stays). Offers Undo.
    func removeFromList(_ it: DownloadItem) {
        hidden.insert(it.id)
        if it.status == .downloading { engine.pause(it.id) }
        let id = it.id
        snack = Snack(message: "Removed \(it.displayName)", actionLabel: "Undo",
                      action: { [weak self] in self?.hidden.remove(id) },
                      onDismiss: { [weak self] in
                          guard let self = self, self.hidden.contains(id) else { return }
                          self.engine.remove(id, deleteFile: false)
                          self.hidden.remove(id)
                      })
    }

    /// Delete file: removes the entry and the file from disk (confirmed in the UI).
    func deleteFile(_ id: UUID) { engine.remove(id, deleteFile: true) }

    func offerClipboard() {
        guard SettingsStore.shared.value.clipboardMonitor, UIPasteboard.general.hasURLs || UIPasteboard.general.hasStrings else { return }
        let text = UIPasteboard.general.url?.absoluteString ?? UIPasteboard.general.string ?? ""
        guard let url = FileTypes.extractURLs(text).first, url != lastClipboard else { return }
        lastClipboard = url
        if items.contains(where: { $0.url == url }) { return }
        snack = Snack(message: "Link copied: \(url.prefix(50))", actionLabel: "Download",
                      action: { [weak self] in self?.prefill = AddPrefill(urls: url) })
    }

    func handleIncoming(url: URL) {
        if url.scheme == "superdl" {
            let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
            let target = comps?.queryItems?.first(where: { $0.name == "url" })?.value
                ?? url.absoluteString.replacingOccurrences(of: "superdl://", with: "")
            let urls = FileTypes.extractURLs(target)
            if !urls.isEmpty { prefill = AddPrefill(urls: urls.joined(separator: "\n")) }
        } else if url.scheme == "http" || url.scheme == "https" {
            prefill = AddPrefill(urls: url.absoluteString)
        }
    }

    /// Links the Share Extension parked in the App Group container.
    func importSharedLinks() {
        guard let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: SharedState.group) else { return }
        let f = dir.appendingPathComponent("incoming.txt")
        guard let text = try? String(contentsOf: f, encoding: .utf8), !text.isEmpty else { return }
        try? FileManager.default.removeItem(at: f)
        let urls = FileTypes.extractURLs(text)
        if !urls.isEmpty { prefill = AddPrefill(urls: urls.joined(separator: "\n")) }
    }

    /// From the browser: start a download, or offer to resume a matching failed one.
    func startFromBrowser(_ r: AddRequest) {
        Notifier.requestPermissionIfNeeded()
        let probe = DownloadItem(url: r.url, headers: r.headers, userAgent: r.userAgent, referer: r.referer, cookies: r.cookies)
        engine.inspect(url: r.url, like: probe, headers: r.headers) { [weak self] result in
            Task { @MainActor in
                guard let self = self else { return }
                let name = r.fileName.nilIfEmpty ?? FileTypes.name(fromURL: URL(string: r.url)) ?? ""
                if case .success(let info) = result,
                   let match = self.engine.findResumable(name: info.fileName ?? name, info: info) ?? self.engine.findResumable(name: name, info: info) {
                    self.snack = Snack(message: "Use this link to resume \(match.displayName)?", actionLabel: "Resume",
                                       action: { [weak self] in self?.engine.changeLink(match.id, url: r.url, headers: match.headers, info: info, restart: false) })
                } else {
                    self.engine.add(r)
                    self.toast("Downloading \(name.isEmpty ? "file" : name)")
                }
            }
        }
    }
}
