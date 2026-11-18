import SwiftUI
import SDCore

struct DetailView: View {
    let id: UUID
    @EnvironmentObject var store: Store
    @Environment(\.colorScheme) private var scheme
    @Environment(\.presentationMode) private var presentation
    @State private var tab = 0
    @State private var confirmDeleteFile = false
    @State private var hash: String?

    var body: some View {
        if let item = store.item(id), !store.hidden.contains(id) {
            DetailContent(item: item, live: store.box(id), tab: $tab, hash: $hash, confirmDeleteFile: $confirmDeleteFile)
                .navigationTitle(item.status.label)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Menu { RowMenu(item: item, confirmDeleteFile: $confirmDeleteFile, openDetail: nil) } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
                .alert("Delete file from storage?", isPresented: $confirmDeleteFile) {
                    Button("Delete file", role: .destructive) { store.deleteFile(id); presentation.wrappedValue.dismiss() }
                    Button("Cancel", role: .cancel) {}
                } message: { Text("“\(item.displayName)” will be removed from your iPhone. This can't be undone.") }
        } else {
            Color.clear.onAppear { presentation.wrappedValue.dismiss() }
        }
    }
}

private struct DetailContent: View {
    let item: DownloadItem
    @ObservedObject var live: LiveBox
    @Binding var tab: Int
    @Binding var hash: String?
    @Binding var confirmDeleteFile: Bool
    @EnvironmentObject var store: Store
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let ls = live.stats
        let pieces = ls?.pieces ?? item.segments.enumerated().map {
            LiveStats.Piece(start: $0.element.start, end: $0.element.end, downloaded: $0.element.downloaded, active: false, speed: 0, conn: $0.offset + 1)
        }
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 14) {
                        Image(systemName: Theme.icon(for: item.category)).font(.system(size: 28)).foregroundColor(Theme.red)
                            .frame(width: 56, height: 56)
                            .background(RoundedRectangle(cornerRadius: 14).fill(Theme.red.opacity(0.15)))
                        Text(item.displayName).font(.headline)
                    }
                    HStack(spacing: 8) {
                        switch item.status {
                        case .downloading, .queued, .waiting:
                            Button { store.pause(item.id) } label: { Label("Pause", systemImage: "pause.fill") }.buttonStyle(.borderedProminent)
                        case .paused:
                            Button { store.resume(item.id) } label: { Label("Resume", systemImage: "play.fill") }.buttonStyle(.borderedProminent)
                        case .failed:
                            Button { store.resume(item.id) } label: { Label("Retry", systemImage: "arrow.clockwise") }.buttonStyle(.borderedProminent)
                        case .completed:
                            Button { FileOpener.open(item) } label: { Label("Open", systemImage: "arrow.up.forward.app") }.buttonStyle(.borderedProminent)
                        }
                        if item.status != .completed {
                            Button(item.status == .failed && LinkMatcher.looksExpired(item.error) ? "Link expired? Change link" : "Change link") {
                                store.changeLinkFor = item.id
                            }.buttonStyle(.bordered)
                        } else {
                            Button { FileOpener.share(item) } label: { Label("Share", systemImage: "square.and.arrow.up") }.buttonStyle(.bordered)
                        }
                    }
                    switch tab {
                    case 0: info(ls: ls, pieces: pieces)
                    case 1: connections(ls: ls, pieces: pieces)
                    default:
                        Text(item.log.isEmpty ? "No log entries yet." : item.log)
                            .font(.system(.caption, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                }
                .padding(16)
            }
            Picker("", selection: $tab) {
                Text("INFO").tag(0)
                Text("CONNECTIONS").tag(1)
                Text("LOG").tag(2)
            }
            .pickerStyle(.segmented)
            .padding(12)
            .background(Theme.card(scheme))
        }
        .background(Theme.background(scheme).ignoresSafeArea())
    }

    private func row(_ label: String, _ value: String, link: URL? = nil, copy: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundColor(.secondary)
            HStack {
                if let link = link {
                    Button(value) { UIApplication.shared.open(link) }.foregroundColor(Theme.red).multilineTextAlignment(.leading)
                } else {
                    Text(value).font(.subheadline).textSelection(.enabled)
                }
                Spacer()
                if let c = copy {
                    Button { UIPasteboard.general.string = c; store.toast("Copied") } label: { Image(systemName: "doc.on.doc") }
                }
            }
            Divider().padding(.top, 6)
        }
    }

    @ViewBuilder private func info(ls: LiveStats?, pieces: [LiveStats.Piece]) -> some View {
        let total = ls?.total ?? item.totalBytes
        let done = item.status == .completed ? item.totalBytes : (ls?.downloaded ?? item.downloadedBytes)
        let df: DateFormatter = { let f = DateFormatter(); f.dateStyle = .medium; f.timeStyle = .short; return f }()
        row("Downloaded", total > 0 ? "\(Fmt.bytes(done)) of \(Fmt.bytes(total)) (\(done * 100 / max(total, 1))%)" : Fmt.bytes(done))
        row("Added at", df.string(from: item.createdAt))
        row("Saved in", Paths.fileURL(for: item)?.deletingLastPathComponent().path ?? "Super Downloader/\(item.category)")
        if let r = item.referer, !r.isEmpty { row("Web page", r, link: URL(string: r)) }
        row("File", item.url, link: URL(string: item.url), copy: item.url)
        if let w = item.warning { row("Warning", w) }
        if let e = item.error { row("Error", e) }
        if let c = item.checksumResult { row("Checksum", c) }

        Text("Progress").font(.caption).foregroundColor(.secondary).padding(.top, 6)
        if total <= 0 && item.status != .completed {
            SegmentedBar(progress: 0, total: total, pieces: nil, indeterminate: item.status == .downloading)
        } else {
            PieceMap(total: total, downloaded: done, pieces: pieces, completed: item.status == .completed)
        }

        VStack(alignment: .leading, spacing: 10) {
            let elapsed = Int64(ls?.elapsed ?? item.elapsed)
            let avg: Int64 = ls?.avgSpeed ?? (item.elapsed > 0 ? Int64(Double(max(item.totalBytes, 0)) / item.elapsed) : 0)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 10) {
                stat("Speed", ls.map { Fmt.speed($0.speed) } ?? "—")
                stat("Average", Fmt.speed(avg))
                stat("Peak", Fmt.speed(ls?.peakSpeed ?? item.peakSpeed))
                stat("Elapsed", Fmt.duration(elapsed))
                stat("ETA", (ls?.eta ?? -1) >= 0 ? Fmt.duration(ls!.eta) : "—")
                stat("Connections", "\(ls?.connections ?? 0) / \(item.maxConnections)")
            }
            SpeedGraph(history: ls?.history ?? [])
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18).fill(Theme.card(scheme)))

        if item.status == .completed {
            HStack {
                Button("MD5") { verify("MD5") }.buttonStyle(.bordered)
                Button("SHA-256") { verify("SHA-256") }.buttonStyle(.bordered)
                Button("Open folder") { if let u = Paths.filesAppURL { UIApplication.shared.open(u) } }.buttonStyle(.bordered)
            }
            if let h = hash {
                Text(h).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
            }
        }
    }

    private func stat(_ k: String, _ v: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(k).font(.caption2).foregroundColor(.secondary)
            Text(v).font(.subheadline.weight(.semibold))
        }
    }

    private func verify(_ type: String) {
        guard let url = Paths.fileURL(for: item) else { return }
        hash = "Computing \(type)…"
        DispatchQueue.global(qos: .userInitiated).async {
            let h = Checksums.compute(url: url, type: type) ?? "Could not read file"
            DispatchQueue.main.async { hash = "\(type): \(h)" }
        }
    }

    @ViewBuilder private func connections(ls: LiveStats?, pieces: [LiveStats.Piece]) -> some View {
        if pieces.isEmpty {
            Text(item.status == .completed ? "All pieces finished." : (!item.supportsRange && item.relativePath != nil ? "This server sends the file as one stream (no ranges)." : "No connections yet."))
                .foregroundColor(.secondary)
        } else {
            let total = ls?.total ?? item.totalBytes
            ForEach(Array(pieces.sorted { $0.start < $1.start }.enumerated()), id: \.offset) { _, p in
                let size = p.end >= p.start ? p.end - p.start + 1 : 0
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        RoundedRectangle(cornerRadius: 3).fill(p.active ? Theme.conn(p.conn) : Color.gray).frame(width: 10, height: 10)
                        Text(p.active ? "Connection #\(p.conn)" : "Piece").font(.subheadline.weight(.medium))
                        Spacer()
                        Text(p.active ? Fmt.speed(p.speed) : (size > 0 && p.downloaded >= size ? "done" : "idle")).font(.caption).foregroundColor(.secondary)
                    }
                    Text("Bytes \(p.start)–\(p.end) · \(Fmt.bytes(p.downloaded)) of \(Fmt.bytes(size))").font(.caption).foregroundColor(.secondary)
                    SegmentedBar(progress: size > 0 ? Double(p.downloaded) / Double(size) : 0, total: total, pieces: nil, indeterminate: false, height: 4)
                    Divider()
                }
            }
        }
    }
}
