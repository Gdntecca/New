import SwiftUI
import SDCore

/// Add-link sheet: one or many links, optional name, connections, headers, cookies, checksum.
struct AddSheet: View {
    let prefill: AddPrefill
    @EnvironmentObject var store: Store
    @EnvironmentObject var settings: SettingsStore
    @Environment(\.dismiss) private var dismiss
    @State private var urls = ""
    @State private var fileName = ""
    @State private var advanced = false
    @State private var conns: Double = 8
    @State private var sequential = false
    @State private var startPaused = false
    @State private var headers = ""
    @State private var cookies = ""
    @State private var referer = ""
    @State private var ua = ""
    @State private var checksumType = "None"
    @State private var checksum = ""
    @State private var error: String?
    @State private var dupes: [String] = []
    @State private var loaded = false

    private var parsed: [String] { FileTypes.extractURLs(urls) }

    var body: some View {
        NavigationView {
            Form {
                Section {
                    TextEditor(text: $urls)
                        .frame(minHeight: 90)
                        .textInputAutocapitalization(.never)
                        .disableAutocorrection(true)
                        .keyboardType(.URL)
                    HStack {
                        Button("Paste") {
                            let t = UIPasteboard.general.url?.absoluteString ?? UIPasteboard.general.string ?? ""
                            urls = urls.isEmpty ? t : urls + "\n" + t
                        }
                        Spacer()
                        if parsed.count > 1 { Text("\(parsed.count) links").foregroundColor(.secondary) }
                    }
                    if let e = error { Text(e).foregroundColor(.red).font(.footnote) }
                } header: { Text("Link(s) — one per line") }

                if !dupes.isEmpty {
                    Section {
                        Text("\(dupes.count) link(s) are already in your list.").foregroundColor(Theme.red)
                        Button("Add again") { submit(parsed) }
                        Button("Skip duplicates") { submit(parsed.filter { !dupes.contains($0) }) }
                    }
                }

                if parsed.count <= 1 {
                    Section { TextField("File name (optional)", text: $fileName) }
                }

                Section {
                    DisclosureGroup("More options", isExpanded: $advanced) {
                        VStack(alignment: .leading) {
                            Text("Connections: \(Int(conns))")
                            Slider(value: $conns, in: 1...16, step: 1)
                        }
                        Toggle("Sequential (in order)", isOn: $sequential)
                        Toggle("Add paused", isOn: $startPaused)
                        TextField("Referer / web page", text: $referer).textInputAutocapitalization(.never).disableAutocorrection(true)
                        TextField("Cookies (name=value; …)", text: $cookies).textInputAutocapitalization(.never).disableAutocorrection(true)
                        TextField("Extra headers (Name: value per line)", text: $headers).textInputAutocapitalization(.never).disableAutocorrection(true)
                        TextField("User-Agent (optional)", text: $ua).textInputAutocapitalization(.never).disableAutocorrection(true)
                        if parsed.count <= 1 {
                            Picker("Verify checksum", selection: $checksumType) {
                                ForEach(["None", "MD5", "SHA-256"], id: \.self) { Text($0) }
                            }
                            if checksumType != "None" {
                                TextField("Expected \(checksumType)", text: $checksum).textInputAutocapitalization(.never).disableAutocorrection(true)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Add download")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Download") {
                        let list = parsed
                        if list.isEmpty { error = "Enter at least one http(s) link"; return }
                        let d = store.duplicates(list)
                        if d.isEmpty { submit(list) } else { dupes = d }
                    }.font(.body.weight(.bold))
                }
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            urls = prefill.urls
            fileName = prefill.fileName
            referer = prefill.referer ?? ""
            cookies = prefill.cookies ?? ""
            ua = prefill.userAgent ?? ""
            conns = Double(store.engine.effectiveParts(settings.value))
        }
    }

    private func submit(_ list: [String]) {
        let single = list.count == 1
        let reqs = list.map { u -> AddRequest in
            var r = AddRequest(url: u)
            r.fileName = single ? fileName : ""
            r.headers = headers
            r.cookies = cookies.nilIfEmpty
            r.userAgent = ua.nilIfEmpty
            r.referer = referer.nilIfEmpty
            r.connections = Int(conns)
            r.sequential = sequential
            r.startPaused = startPaused
            if single && checksumType != "None" { r.checksumType = checksumType; r.expectedChecksum = checksum }
            return r
        }
        store.addAll(reqs)
        dismiss()
    }
}

/// Change link: paste a fresh URL for an expired download. A matching file resumes from the
/// saved pieces; a different file restarts only after the user agrees.
struct ChangeLinkSheet: View {
    let id: UUID
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var headers = ""
    @State private var checking = false
    @State private var error: String?
    @State private var mismatch: (LinkMatch, RemoteInfo?)?
    @State private var showMismatch = false
    @State private var loaded = false

    var body: some View {
        let item = store.item(id)
        NavigationView {
            Form {
                if let it = item {
                    Section {
                        Text(it.displayName).font(.subheadline)
                        if it.downloadedBytes > 0 {
                            Text("\(Fmt.bytes(it.downloadedBytes)) already downloaded is kept if the file matches.")
                                .font(.footnote).foregroundColor(.secondary)
                        }
                    }
                }
                Section {
                    TextField("New link", text: $url).textInputAutocapitalization(.never).disableAutocorrection(true).keyboardType(.URL)
                    if let e = error { Text(e).foregroundColor(.red).font(.footnote) }
                } header: { Text("New link") }
                Section {
                    TextEditor(text: $headers).frame(minHeight: 70).textInputAutocapitalization(.never).disableAutocorrection(true)
                } header: { Text("Headers (Name: value per line)") } footer: { Text("Cookies and referer are kept.") }
                if checking { Section { ProgressView("Checking link…") } }
            }
            .navigationTitle("Change link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Check & use") { check(item) }.disabled(checking) }
            }
            .alert("Different file?", isPresented: $showMismatch) {
                Button("Restart", role: .destructive) {
                    store.engine.changeLink(id, url: url, headers: headers, info: mismatch?.1, restart: true)
                    store.toast("Restarting with the new link")
                    dismiss()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(mismatchText(item))
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            headers = item?.headers ?? ""
            let clip = UIPasteboard.general.url?.absoluteString ?? UIPasteboard.general.string ?? ""
            if let u = FileTypes.extractURLs(clip).first, u != item?.url { url = u }
        }
    }

    private func mismatchText(_ item: DownloadItem?) -> String {
        guard let m = mismatch else { return "" }
        switch m.0 {
        case .differentSize:
            return "This link looks like a different file (\(Fmt.bytes(m.1?.total ?? -1)) instead of \(Fmt.bytes(item?.totalBytes ?? -1))). Restart from zero?"
        case .unknownSize:
            return "The server doesn't report a size, so this can't be confirmed as the same file. Restart from zero?"
        default:
            return "This link looks like a different file. Restart from zero?"
        }
    }

    private func check(_ item: DownloadItem?) {
        guard let it = item else { dismiss(); return }
        guard let u = FileTypes.extractURLs(url).first else { error = "Enter an http(s) link"; return }
        url = u
        checking = true
        error = nil
        store.engine.inspect(url: u, like: it, headers: headers) { result in
            DispatchQueue.main.async {
                checking = false
                switch result {
                case .failure(let e):
                    error = e.localizedDescription
                case .success(let info):
                    let m = LinkMatcher.match(old: store.engine.knownInfo(it), new: info)
                    if m == .same {
                        store.engine.changeLink(id, url: u, headers: headers, info: info, restart: false)
                        store.toast("Link changed, resuming")
                        dismiss()
                    } else {
                        mismatch = (m, info)
                        showMismatch = true
                    }
                }
            }
        }
    }
}
