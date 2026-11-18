import SwiftUI
import SDCore

enum Route: Hashable {
    case detail(UUID), settings, about, stats, browser(String?), privateFolder
}

struct RootView: View {
    @EnvironmentObject var store: Store
    @State private var route: Route?
    @State private var showMenu = false

    var body: some View {
        NavigationView {
            ZStack {
                HomeView(route: $route, showMenu: $showMenu)
                NavigationLink(isActive: Binding(get: { route != nil }, set: { if !$0 { route = nil } })) {
                    destination
                } label: { EmptyView() }
                .hidden()
            }
            .navigationBarHidden(true)
        }
        .navigationViewStyle(.stack)
        .overlay(alignment: .bottom) { SnackbarView().padding(.bottom, 92) }
        .sheet(item: $store.prefill) { p in AddSheet(prefill: p) }
        .sheet(item: Binding(get: { store.changeLinkFor.map { IDBox(id: $0) } }, set: { store.changeLinkFor = $0?.id })) { b in
            ChangeLinkSheet(id: b.id)
        }
        .confirmationDialog("Menu", isPresented: $showMenu, titleVisibility: .hidden) {
            Button("Browser") { route = .browser(nil) }
            Button("Private folder") { openPrivate() }
            Button("Statistics") { route = .stats }
            Button("Settings") { route = .settings }
            Button("About") { route = .about }
        }
        .onChange(of: store.openDetail) { id in
            if let id = id { route = .detail(id); store.openDetail = nil }
        }
    }

    private func openPrivate() {
        if store.privateUnlocked { route = .privateFolder; return }
        Biometrics.unlock { ok in
            if ok { store.privateUnlocked = true; route = .privateFolder } else { store.toast("Private folder stays locked") }
        }
    }

    @ViewBuilder private var destination: some View {
        if let r = route {
            switch r {
            case .detail(let id): DetailView(id: id)
            case .settings: SettingsView()
            case .about: AboutView()
            case .stats: StatsView()
            case .browser(let url): BrowserView(startURL: url)
            case .privateFolder: PrivateFolderView()
            }
        } else {
            EmptyView()
        }
    }
}

struct IDBox: Identifiable { let id: UUID }

private let sortLabels = ["Newest", "Oldest", "Name", "Size", "Progress", "Queue"]
private let filterLabels = ["All files", "Active", "Completed", "Failed"] + FileTypes.categories

struct HomeView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var settings: SettingsStore
    @Environment(\.colorScheme) private var scheme
    @Binding var route: Route?
    @Binding var showMenu: Bool
    @State private var sort = 0
    @State private var filter = 0
    @State private var searching = false
    @State private var query = ""
    @State private var selected: Set<UUID> = []
    @State private var expanded: UUID?
    @State private var confirmDeleteFiles = false
    @State private var showScanner = false

    private var visible: [DownloadItem] {
        var list = store.items.filter { !$0.isPrivate && !store.hidden.contains($0.id) }
        switch filter {
        case 0: break
        case 1: list = list.filter { $0.status != .completed && $0.status != .failed }
        case 2: list = list.filter { $0.status == .completed }
        case 3: list = list.filter { $0.status == .failed }
        default: list = list.filter { $0.category == filterLabels[filter] }
        }
        if !query.isEmpty {
            list = list.filter { $0.displayName.localizedCaseInsensitiveContains(query) || $0.url.localizedCaseInsensitiveContains(query) }
        }
        switch sort {
        case 1: list.sort { $0.createdAt < $1.createdAt }
        case 2: list.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
        case 3: list.sort { $0.totalBytes > $1.totalBytes }
        case 4: list.sort { $0.progress > $1.progress }
        case 5: list.sort { $0.queueOrder < $1.queueOrder }
        default: list.sort { $0.createdAt > $1.createdAt }
        }
        return list
    }

    var body: some View {
        let rows = visible
        VStack(spacing: 0) {
            // Title bar
            ZStack {
                Text("Super Downloader").font(.headline.weight(.bold))
                HStack {
                    Spacer()
                    Menu {
                        Button("Resume all") { store.engine.resumeAll() }
                        Button("Pause all") { store.engine.pauseAll() }
                        Button("Scan QR code") { showScanner = true }
                        Button("Browser") { route = .browser(nil) }
                        Button("Open folder") { if let u = Paths.filesAppURL { UIApplication.shared.open(u) } }
                    } label: {
                        Image(systemName: "ellipsis").font(.title3).frame(width: 44, height: 44)
                    }
                }
            }
            .padding(.horizontal, 8)
            .frame(height: 48)

            if searching {
                HStack {
                    Image(systemName: "magnifyingglass").foregroundColor(.secondary)
                    TextField("Search downloads", text: $query).textInputAutocapitalization(.never).disableAutocorrection(true)
                    Button { searching = false; query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundColor(.secondary) }
                }
                .padding(10)
                .background(Theme.card(scheme))
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .padding(.horizontal, 12)
                .padding(.bottom, 6)
            }

            // Filter row: select-all, sort chip, filter chip, traffic-mode pill
            HStack(spacing: 6) {
                Button {
                    if selected.count == rows.count && !rows.isEmpty { selected.removeAll() } else { selected = Set(rows.map { $0.id }) }
                } label: {
                    Image(systemName: selected.count == rows.count && !rows.isEmpty ? "checkmark.square.fill" : "square")
                        .font(.title3).foregroundColor(selected.isEmpty ? .secondary : Theme.red)
                }
                Menu {
                    Button("Select all") { selected = Set(rows.map { $0.id }) }
                    Button("Select none") { selected.removeAll() }
                    if !selected.isEmpty {
                        Button("Resume selected") { selected.forEach(store.resume) }
                        Button("Pause selected") { selected.forEach(store.pause) }
                        Button("Delete selected") {
                            store.items.filter { selected.contains($0.id) }.forEach(store.removeFromList)
                            selected.removeAll()
                        }
                        Button("Delete files of selected…", role: .destructive) { confirmDeleteFiles = true }
                    }
                } label: { Image(systemName: "chevron.down").font(.caption.weight(.bold)).foregroundColor(.secondary) }

                Menu {
                    ForEach(sortLabels.indices, id: \.self) { i in
                        Button { sort = i } label: { if i == sort { Label(sortLabels[i], systemImage: "checkmark") } else { Text(sortLabels[i]) } }
                    }
                } label: { Chip(text: sortLabels[sort], icon: "arrow.up.arrow.down") }

                Menu {
                    ForEach(filterLabels.indices, id: \.self) { i in
                        Button { filter = i } label: { if i == filter { Label(filterLabels[i], systemImage: "checkmark") } else { Text(filterLabels[i]) } }
                    }
                } label: { Chip(text: filterLabels[filter], icon: "line.3.horizontal.decrease") }

                Spacer()
                TrafficPill()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)

            if let b = store.blocked {
                Text(b).font(.caption).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.vertical, 6)
                    .background(Theme.red.opacity(0.18))
            }

            if rows.isEmpty {
                Spacer()
                VStack(spacing: 10) {
                    Image(systemName: "arrow.down.circle").font(.system(size: 48)).foregroundColor(Theme.red)
                    Text(store.items.isEmpty ? "No downloads yet" : "Nothing here").font(.headline)
                }
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(rows) { it in
                            DownloadRow(item: it, live: store.box(it.id),
                                        selected: selected.contains(it.id), selectionMode: !selected.isEmpty,
                                        expanded: expanded == it.id,
                                        onTap: { tap(it) }, onLongPress: { selected.insert(it.id) },
                                        openDetail: { route = .detail(it.id) })
                                .id(it.id)
                            Divider().padding(.leading, 64).opacity(0.4)
                        }
                    }
                    .padding(.bottom, 100)
                }
            }
        }
        .background(Theme.background(scheme).ignoresSafeArea())
        .overlay(alignment: .bottom) {
            BottomBar(home: { searching = false; query = ""; filter = 0 },
                      search: { searching.toggle() },
                      add: { store.prefill = AddPrefill(urls: "") },
                      settings: { route = .settings },
                      menu: { showMenu = true })
        }
        .alert("Delete \(selected.count) file(s) from storage?", isPresented: $confirmDeleteFiles) {
            Button("Delete files", role: .destructive) { selected.forEach(store.deleteFile); selected.removeAll() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("The files are removed from your iPhone. This can't be undone.") }
        .sheet(isPresented: $showScanner) {
            QRScannerView { text in
                showScanner = false
                let urls = FileTypes.extractURLs(text)
                if urls.isEmpty { store.toast("No link in that QR code") } else { store.prefill = AddPrefill(urls: urls.joined(separator: "\n")) }
            }
        }
    }

    private func tap(_ it: DownloadItem) {
        if !selected.isEmpty {
            if selected.contains(it.id) { selected.remove(it.id) } else { selected.insert(it.id) }
        } else if it.status == .completed {
            FileOpener.open(it)
        } else {
            expanded = expanded == it.id ? nil : it.id
        }
    }
}

struct Chip: View {
    var text: String
    var icon: String
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: icon).font(.caption)
            Text(text).font(.subheadline).lineLimit(1)
        }
        .foregroundColor(.primary)
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(Capsule().stroke(Color.gray.opacity(0.35)))
    }
}

struct TrafficPill: View {
    @EnvironmentObject var settings: SettingsStore
    var body: some View {
        let mode = settings.value.trafficMode
        Button {
            let order: [TrafficMode] = [.unlimited, .high, .medium, .low]
            let i = order.firstIndex(of: mode) ?? 0
            settings.value.trafficMode = order[(i + 1) % order.count]
        } label: {
            Text(mode.label).font(.caption.weight(.semibold))
                .padding(.horizontal, 12).padding(.vertical, 5)
                .foregroundColor(mode == .unlimited ? .white : .primary)
                .background(Capsule().fill(mode == .unlimited ? Theme.red : Color.gray.opacity(0.25)))
        }
        .accessibilityLabel("Traffic mode: \(mode.label)")
    }
}

struct BottomBar: View {
    var home: () -> Void
    var search: () -> Void
    var add: () -> Void
    var settings: () -> Void
    var menu: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack(alignment: .top) {
            HStack {
                barButton("house.fill", "Home", home, tint: Theme.red)
                barButton("magnifyingglass", "Search", search)
                Spacer().frame(width: 64)
                barButton("gearshape", "Settings", settings)
                barButton("line.3.horizontal", "Menu", menu)
            }
            .frame(height: 60)
            .frame(maxWidth: .infinity)
            .background(Theme.card(scheme).ignoresSafeArea(edges: .bottom))
            .padding(.top, 26)
            Button(action: add) {
                Image(systemName: "plus").font(.title2.weight(.bold)).foregroundColor(.white)
                    .frame(width: 58, height: 58)
                    .background(Circle().fill(Theme.red))
                    .shadow(color: Theme.red.opacity(0.4), radius: 8, y: 3)
            }
            .accessibilityLabel("Add download")
        }
    }

    private func barButton(_ icon: String, _ label: String, _ action: @escaping () -> Void, tint: Color = .primary) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.title3).foregroundColor(tint).frame(maxWidth: .infinity, minHeight: 44)
        }
        .accessibilityLabel(label)
    }
}

struct DownloadRow: View {
    let item: DownloadItem
    @ObservedObject var live: LiveBox
    var selected: Bool
    var selectionMode: Bool
    var expanded: Bool
    var onTap: () -> Void
    var onLongPress: () -> Void
    var openDetail: () -> Void
    @EnvironmentObject var store: Store
    @State private var confirmDeleteFile = false

    var body: some View {
        let ls = live.stats
        let total = ls?.total ?? item.totalBytes
        let done = ls?.downloaded ?? item.downloadedBytes
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                if selectionMode {
                    Image(systemName: selected ? "checkmark.circle.fill" : "circle").foregroundColor(selected ? Theme.red : .secondary)
                }
                Image(systemName: item.status == .completed && item.category == "Archives" ? "folder.fill" : Theme.icon(for: item.category))
                    .font(.system(size: 26)).foregroundColor(Theme.red).frame(width: 36)
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.displayName).font(.body.weight(.bold)).lineLimit(1).truncationMode(.middle)
                    if item.status != .completed && item.status != .failed {
                        SegmentedBar(progress: total > 0 ? Double(done) / Double(total) : 0, total: total, pieces: ls?.pieces,
                                     indeterminate: item.status == .downloading && total <= 0, height: 4)
                    }
                    statusLine(ls: ls, total: total, done: done)
                }
                Spacer(minLength: 0)
                Menu { RowMenu(item: item, confirmDeleteFile: $confirmDeleteFile, openDetail: openDetail) } label: {
                    Image(systemName: "ellipsis").rotationEffect(.degrees(90)).foregroundColor(.secondary).frame(width: 36, height: 44)
                }
            }
            if expanded || (item.status == .failed && LinkMatcher.looksExpired(item.error)) {
                HStack(spacing: 8) {
                    switch item.status {
                    case .downloading, .queued, .waiting:
                        Button { store.pause(item.id) } label: { Label("Pause", systemImage: "pause.fill") }.buttonStyle(.borderedProminent)
                    case .paused:
                        Button { store.resume(item.id) } label: { Label("Resume", systemImage: "play.fill") }.buttonStyle(.borderedProminent)
                    case .failed:
                        Button { store.resume(item.id) } label: { Label("Retry", systemImage: "arrow.clockwise") }.buttonStyle(.borderedProminent)
                        if LinkMatcher.looksExpired(item.error) {
                            Button("Link expired? Change link") { store.changeLinkFor = item.id }.buttonStyle(.bordered)
                        }
                    case .completed:
                        Button("Open") { FileOpener.open(item) }.buttonStyle(.borderedProminent)
                    }
                    if expanded { Button("Details", action: openDetail).buttonStyle(.bordered) }
                }
                .font(.subheadline)
                .padding(.leading, 48)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(selected ? Theme.red.opacity(0.15) : Color.clear)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .onLongPressGesture(perform: onLongPress)
        .alert("Delete file from storage?", isPresented: $confirmDeleteFile) {
            Button("Delete file", role: .destructive) { store.deleteFile(item.id) }
            Button("Cancel", role: .cancel) {}
        } message: { Text("“\(item.displayName)” will be removed from your iPhone. This can't be undone.") }
    }

    private func statusLine(ls: LiveStats?, total: Int64, done: Int64) -> some View {
        let (status, color): (String, Color) = {
            switch item.status {
            case .completed: return ("Completed", Theme.red)
            case .failed: return ("Error", .red)
            case .downloading: return (total > 0 ? "\(done * 100 / total)%" : "Downloading", Theme.red)
            default: return (item.status.label, Theme.red)
            }
        }()
        var rest: [String] = []
        switch item.status {
        case .completed:
            rest = [Fmt.bytes(item.totalBytes), whenLabel(item.completedAt)]
        case .downloading:
            if let ls = ls { rest.append(Fmt.speed(ls.speed)); if ls.eta >= 0 { rest.append(Fmt.duration(ls.eta) + " left") } }
            rest.append(total > 0 ? "\(Fmt.bytes(done)) / \(Fmt.bytes(total))" : Fmt.bytes(done))
        case .failed:
            rest = [item.error ?? "failed"]
        default:
            if total > 0 { rest.append("\(Fmt.bytes(done)) / \(Fmt.bytes(total))") }
            rest.append(whenLabel(item.createdAt))
        }
        return HStack(spacing: 0) {
            Text(status).foregroundColor(color).fontWeight(.semibold)
            Text(" | " + rest.filter { !$0.isEmpty }.joined(separator: " | ")).foregroundColor(.secondary).lineLimit(1)
        }
        .font(.caption)
    }
}

/// The ⋮ menu used by rows and the detail page.
struct RowMenu: View {
    let item: DownloadItem
    @Binding var confirmDeleteFile: Bool
    var openDetail: (() -> Void)?
    @EnvironmentObject var store: Store

    var body: some View {
        if item.status == .completed {
            Button { FileOpener.open(item) } label: { Label("Open", systemImage: "arrow.up.forward.app") }
            Button { FileOpener.share(item) } label: { Label("Share", systemImage: "square.and.arrow.up") }
            if !item.isPrivate {
                Button { store.engine.makePrivate(item.id) { ok in store.toast(ok ? "Moved to private folder" : "Could not move file") } } label: {
                    Label("Move to private folder", systemImage: "lock")
                }
            }
        } else {
            switch item.status {
            case .downloading, .queued, .waiting: Button { store.pause(item.id) } label: { Label("Pause", systemImage: "pause") }
            default: Button { store.resume(item.id) } label: { Label("Resume", systemImage: "play") }
            }
            Button { store.engine.move(item.id, by: -1) } label: { Label("Move up in queue", systemImage: "arrow.up") }
            Button { store.engine.move(item.id, by: 1) } label: { Label("Move down in queue", systemImage: "arrow.down") }
        }
        Button { store.changeLinkFor = item.id } label: { Label("Change link…", systemImage: "link") }
        Button { UIPasteboard.general.string = item.url; store.toast("Link copied") } label: { Label("Copy link", systemImage: "doc.on.doc") }
        if let openDetail = openDetail { Button(action: openDetail) { Label("Details", systemImage: "info.circle") } }
        Button { store.removeFromList(item) } label: { Label("Delete", systemImage: "minus.circle") }
        Button(role: .destructive) { confirmDeleteFile = true } label: { Label("Delete file…", systemImage: "trash") }
    }
}

struct SnackbarView: View {
    @EnvironmentObject var store: Store
    @State private var work: DispatchWorkItem?
    @State private var current: Snack?

    var body: some View {
        Group {
            if let s = store.snack {
                HStack {
                    Text(s.message).font(.subheadline).foregroundColor(.white).lineLimit(2)
                    Spacer()
                    if let label = s.actionLabel {
                        Button(label) {
                            let a = s.action
                            store.snack = nil
                            a?()
                        }
                        .font(.subheadline.weight(.bold)).foregroundColor(Theme.redBright)
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color(white: 0.15)))
                .padding(.horizontal, 12)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .onAppear { arm(s) }
                .onChange(of: s) { arm($0) }
            }
        }
        .animation(.easeOut(duration: 0.2), value: store.snack)
    }

    private func arm(_ s: Snack) {
        // A newer message replaced an older one: settle the older one (e.g. commit a delete).
        if let prev = current, prev != s, store.snack != prev { prev.onDismiss?() }
        current = s
        work?.cancel()
        let w = DispatchWorkItem {
            if store.snack == s { store.snack = nil; s.onDismiss?() }
        }
        work = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: w)
    }
}
