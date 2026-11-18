import SwiftUI
import UniformTypeIdentifiers
import SDCore

struct SettingsView: View {
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var store: Store
    @State private var exporting = false
    @State private var importing = false

    private func minutesBinding(_ key: WritableKeyPath<AppSettings, Int>) -> Binding<Date> {
        Binding(get: {
            let m = settings.value[keyPath: key]
            return Calendar.current.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: Date()) ?? Date()
        }, set: { d in
            let c = Calendar.current.dateComponents([.hour, .minute], from: d)
            settings.value[keyPath: key] = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        })
    }

    private func intField(_ title: String, _ key: WritableKeyPath<AppSettings, Int>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0", text: Binding(get: { String(settings.value[keyPath: key]) },
                                         set: { settings.value[keyPath: key] = Int($0.filter(\.isNumber).prefix(7)) ?? 0 }))
                .keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 100)
        }
    }

    var body: some View {
        Form {
            Section("Appearance") {
                Picker("Theme", selection: $settings.value.theme) {
                    Text("System").tag(0); Text("Light").tag(1); Text("Dark").tag(2)
                }.pickerStyle(.segmented)
            }
            Section {
                Picker("Traffic mode", selection: $settings.value.trafficMode) {
                    ForEach(TrafficMode.allCases, id: \.self) { Text($0.label).tag($0) }
                }
                if settings.value.trafficMode == .custom { intField("Custom limit (KB/s)", \.customLimitKBps) }
            } header: { Text("Traffic") } footer: {
                Text(settings.value.trafficMode == .unlimited ? "No speed limit." : "Limit: \(Fmt.speed(settings.value.limitBytesPerSec)) for all downloads.")
            }
            Section {
                Stepper("Connections per download: \(settings.value.parts)", value: $settings.value.parts, in: 1...16)
                Stepper("Max connections per server: \(settings.value.maxPerServer)", value: $settings.value.maxPerServer, in: 1...16)
                Stepper("Simultaneous downloads: \(settings.value.maxSimultaneous)", value: $settings.value.maxSimultaneous, in: 1...32)
                Toggle("Adaptive connections", isOn: $settings.value.adaptive)
                Stepper("Retries: \(settings.value.retryCount)", value: $settings.value.retryCount, in: 0...20)
                Stepper("Retry delay: \(settings.value.retryDelaySec) s", value: $settings.value.retryDelaySec, in: 1...60)
            } header: { Text("Connections") } footer: {
                if store.engine.isLowMemory { Text("This device has less memory, so downloads use at most 4 connections and 2 at a time.") }
            }
            Section("Network") {
                Toggle("Wi‑Fi only", isOn: $settings.value.wifiOnly)
                Toggle("Pause in Low Power Mode", isOn: $settings.value.pauseInLowPower)
                Picker("Proxy", selection: $settings.value.proxyType) {
                    Text("None").tag(0); Text("HTTP").tag(1); Text("SOCKS").tag(2)
                }
                if settings.value.proxyType != 0 {
                    TextField("Proxy host", text: $settings.value.proxyHost).textInputAutocapitalization(.never).disableAutocorrection(true)
                    intField("Proxy port", \.proxyPort)
                }
                TextField("Custom User-Agent (blank = default)", text: $settings.value.userAgent).textInputAutocapitalization(.never).disableAutocorrection(true)
            }
            Section {
                HStack { Text("Save to"); Spacer(); Text("Files › On My iPhone › Super Downloader").font(.footnote).foregroundColor(.secondary) }
                Button("Open folder") { if let u = Paths.filesAppURL { UIApplication.shared.open(u) } }
                Picker("When a file exists", selection: $settings.value.conflictPolicy) {
                    ForEach(ConflictPolicy.allCases, id: \.self) { Text($0.label).tag($0) }
                }
                Toggle("Auto-extract ZIP files", isOn: $settings.value.autoExtractZip)
            } header: { Text("Files") }
            Section {
                Toggle("Run queue on a schedule", isOn: $settings.value.scheduleEnabled)
                DatePicker("Start", selection: minutesBinding(\.scheduleStart), displayedComponents: .hourAndMinute)
                DatePicker("Stop", selection: minutesBinding(\.scheduleStop), displayedComponents: .hourAndMinute)
            } header: { Text("Scheduler") } footer: {
                Text("iOS can't wake an app at a set time, so the schedule applies while Super Downloader is open or finishing background transfers.")
            }
            Section("Notifications & clipboard") {
                Toggle("Sound when a download finishes", isOn: $settings.value.notifySound)
                Toggle("Watch clipboard for links", isOn: $settings.value.clipboardMonitor)
            }
            Section("Data") {
                Button("Export list") { exporting = true }
                Button("Import list") { importing = true }
            }
            Section {
                NavigationLink("About") { AboutView() }
            }
        }
        .navigationTitle("Settings")
        .fileExporter(isPresented: $exporting, document: JSONDoc(data: store.engine.exportJSON()), contentType: .json,
                      defaultFilename: "superdownloader-list") { result in
            if case .success = result { store.toast("List exported") }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            guard let d = try? Data(contentsOf: url) else { store.toast("Import failed"); return }
            let n = store.engine.importJSON(d)
            store.toast(n >= 0 ? "Imported \(n) downloads (paused)" : "Not a Super Downloader list")
        }
    }
}

struct JSONDoc: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct AboutView: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                CrestLogo().frame(width: 120, height: 120)
                Text("Super Downloader").font(.title2.weight(.bold))
                Text("Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.1")-\(Bundle.main.object(forInfoDictionaryKey: "SDReleaseLabel") as? String ?? "pre-alpha")")
                    .foregroundColor(.secondary)
                Text("Pre-alpha").font(.caption.weight(.bold)).foregroundColor(Theme.red)
                Text(Credits.author).font(.headline).foregroundColor(Theme.red).padding(.top, 8)
                Text("A free, open-source download manager for everyone. Released under the MIT License.")
                    .multilineTextAlignment(.center).foregroundColor(.secondary).padding(.top, 8)
            }
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("About")
    }
}

struct StatsView: View {
    @EnvironmentObject var store: Store
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let done = store.items.filter { $0.status == .completed }
        let totalBytes = done.reduce(Int64(0)) { $0 + max($1.totalBytes, 0) }
        let totalTime = done.reduce(0.0) { $0 + $1.elapsed }
        let avg = totalTime > 0 ? Int64(Double(totalBytes) / totalTime) : 0
        let peak = store.items.map { $0.peakSpeed }.max() ?? 0
        var byCat: [String: Int64] = [:]
        for it in done { byCat[it.category, default: 0] += max(it.totalBytes, 0) }
        let maxCat = max(byCat.values.max() ?? 1, 1)
        return List {
            Section {
                statRow("Total downloaded", Fmt.bytes(totalBytes))
                statRow("Completed files", "\(done.count)")
                statRow("In list", "\(store.items.count)")
                statRow("Average speed", Fmt.speed(avg))
                statRow("Peak speed", Fmt.speed(peak))
                statRow("Time downloading", Fmt.duration(Int64(totalTime)))
            }
            Section("By category") {
                ForEach(FileTypes.categories, id: \.self) { c in
                    let b = byCat[c] ?? 0
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Image(systemName: Theme.icon(for: c)).foregroundColor(Theme.red)
                            Text(c); Spacer(); Text(Fmt.bytes(b)).foregroundColor(.secondary)
                        }
                        GeometryReader { g in
                            ZStack(alignment: .leading) {
                                Capsule().fill(Theme.track(scheme))
                                Capsule().fill(Theme.red).frame(width: g.size.width * CGFloat(Double(b) / Double(maxCat)))
                            }
                        }.frame(height: 6)
                    }
                }
            }
        }
        .navigationTitle("Statistics")
    }

    private func statRow(_ k: String, _ v: String) -> some View {
        HStack { Text(k).foregroundColor(.secondary); Spacer(); Text(v).fontWeight(.semibold) }
    }
}

struct PrivateFolderView: View {
    @EnvironmentObject var store: Store
    var body: some View {
        let items = store.items.filter { $0.isPrivate && !store.hidden.contains($0.id) }
        Group {
            if items.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "lock.fill").font(.system(size: 44)).foregroundColor(Theme.red)
                    Text("Nothing here yet").font(.headline)
                    Text("Use “Move to private folder” on a finished download. Private files stay inside the app and are hidden from the Files app.")
                        .font(.footnote).foregroundColor(.secondary).multilineTextAlignment(.center)
                }.padding(32)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(items) { it in
                            DownloadRow(item: it, live: store.box(it.id), selected: false, selectionMode: false, expanded: false,
                                        onTap: { FileOpener.open(it) }, onLongPress: {}, openDetail: {})
                            Divider().opacity(0.4)
                        }
                    }
                }
            }
        }
        .navigationTitle("Private folder")
        .onDisappear { store.privateUnlocked = false }
    }
}
