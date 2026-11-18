import SwiftUI
import WebKit
import SafariServices
import SDCore

/// Built-in browser. Uses the default website data store and a Safari user agent so sign-ins and
/// bot checks (Cloudflare, Turnstile, reCAPTCHA, hCaptcha) work as in Safari; the user completes
/// them normally. Downloads reuse the page's cookies, user agent and referer.
final class BrowserModel: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate, WKDownloadDelegate {
    @Published var address = ""
    @Published var progress: Double = 0
    @Published var canBack = false
    @Published var canForward = false
    @Published var googleBanner = false
    @Published var links: [String]?
    @Published var safariURL: URL?
    let webView: WKWebView
    private var observers: [NSKeyValueObservation] = []
    private var tempDownloads: [ObjectIdentifier: (URL, String?, String?)] = [:]

    override init() {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = true
        cfg.allowsInlineMediaPlayback = true
        webView = WKWebView(frame: .zero, configuration: cfg)
        webView.customUserAgent = Engine.defaultUA
        webView.allowsBackForwardNavigationGestures = true
        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        observers.append(webView.observe(\.estimatedProgress) { [weak self] w, _ in DispatchQueue.main.async { self?.progress = w.estimatedProgress } })
        observers.append(webView.observe(\.url) { [weak self] w, _ in
            DispatchQueue.main.async {
                self?.address = w.url?.absoluteString ?? ""
                self?.googleBanner = w.url?.host?.contains("accounts.google.") == true
            }
        })
    }

    func go(_ input: String) {
        let t = input.trimmingCharacters(in: .whitespaces)
        let s: String
        if t.hasPrefix("http://") || t.hasPrefix("https://") { s = t }
        else if t.contains(".") && !t.contains(" ") { s = "https://" + t }
        else { s = "https://www.google.com/search?q=" + (t.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? t) }
        if let u = URL(string: s) { webView.load(URLRequest(url: u)) }
    }

    /// Cookie header for a URL from the browser's own cookie store.
    func cookies(for url: URL, done: @escaping (String?) -> Void) {
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { all in
            let host = url.host ?? ""
            let matching = all.filter { c in
                let d = c.domain.hasPrefix(".") ? String(c.domain.dropFirst()) : c.domain
                return host == d || host.hasSuffix("." + d)
            }
            done(matching.isEmpty ? nil : matching.map { "\($0.name)=\($0.value)" }.joined(separator: "; "))
        }
    }

    private func hand(off url: URL, name: String?) {
        let referer = webView.url?.absoluteString
        cookies(for: url) { cookie in
            DispatchQueue.main.async {
                var r = AddRequest(url: url.absoluteString)
                r.fileName = name ?? ""
                r.cookies = cookie
                r.userAgent = Engine.defaultUA
                r.referer = referer
                Task { @MainActor in Store.shared.startFromBrowser(r) }
            }
        }
    }

    func grabLinks() {
        let js = """
        (function(){var s={};var out=[];document.querySelectorAll('a[href],source[src],video[src],audio[src],img[src]').forEach(function(e){var u=e.href||e.src;if(u&&/^https?:/i.test(u)&&!s[u]){s[u]=1;out.push(u);}});return out;})()
        """
        webView.evaluateJavaScript(js) { [weak self] result, _ in
            DispatchQueue.main.async { self?.links = (result as? [String]) ?? [] }
        }
    }

    func downloadLinks(_ list: [String]) {
        for s in list { if let u = URL(string: s) { hand(off: u, name: nil) } }
    }

    // MARK: Navigation

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        canBack = webView.canGoBack
        canForward = webView.canGoForward
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else { decisionHandler(.allow); return }
        if let scheme = url.scheme, !["http", "https", "about", "blob", "data"].contains(scheme) {
            UIApplication.shared.open(url)
            decisionHandler(.cancel); return
        }
        if navigationAction.shouldPerformDownload { decisionHandler(.download); return }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        let http = navigationResponse.response as? HTTPURLResponse
        let attachment = http?.value(forHTTPHeaderField: "Content-Disposition")?.lowercased().contains("attachment") == true
        if !navigationResponse.canShowMIMEType || attachment { decisionHandler(.download) } else { decisionHandler(.allow) }
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) { download.delegate = self }
    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) { download.delegate = self }

    /// Pop-up windows (some sign-in and verification flows use them) open in this view.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil, navigationAction.request.url != nil { webView.load(navigationAction.request) }
        return nil
    }

    // MARK: WKDownloadDelegate

    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String,
                  completionHandler: @escaping (URL?) -> Void) {
        let url = download.originalRequest?.url ?? response.url
        if let u = url, u.scheme == "http" || u.scheme == "https" {
            // Real links go to our multi-connection engine with the page's session.
            completionHandler(nil)
            download.cancel { _ in }
            hand(off: u, name: suggestedFilename)
            return
        }
        // blob: and data: downloads exist only inside the page; let WebKit save them, then import.
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "-" + suggestedFilename)
        tempDownloads[ObjectIdentifier(download)] = (tmp, suggestedFilename, response.mimeType)
        completionHandler(tmp)
    }

    func downloadDidFinish(_ download: WKDownload) {
        guard let entry = tempDownloads.removeValue(forKey: ObjectIdentifier(download)) else { return }
        let (tmp, name, mime) = entry
        Engine.shared.importFile(at: tmp, suggestedName: name, mime: mime, source: webView.url?.absoluteString ?? "blob", referer: webView.url?.absoluteString)
        Task { @MainActor in Store.shared.toast("Saved from page") }
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        tempDownloads.removeValue(forKey: ObjectIdentifier(download))
    }
}

struct BrowserView: View {
    var startURL: String?
    @StateObject private var model = BrowserModel()
    @State private var address = ""
    @State private var loaded = false
    @State private var selected: Set<String> = []

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                TextField("Search or enter address", text: $address, onCommit: { model.go(address) })
                    .textInputAutocapitalization(.never).disableAutocorrection(true).keyboardType(.webSearch)
                    .padding(8).background(RoundedRectangle(cornerRadius: 10).fill(Color.gray.opacity(0.15)))
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            if model.progress > 0 && model.progress < 1 { ProgressView(value: model.progress).tint(Theme.red) }
            if model.googleBanner {
                HStack {
                    Text("Google may block sign-in inside in-app browsers. You can sign in with Safari instead, but that sign-in stays in Safari and isn't shared with this browser.")
                        .font(.caption)
                    Button("Use Safari") { model.safariURL = model.webView.url }.font(.caption.weight(.bold))
                }
                .padding(10).background(Theme.red.opacity(0.15))
            }
            WebViewHost(webView: model.webView)
            HStack {
                Button { model.webView.goBack() } label: { Image(systemName: "chevron.left") }.disabled(!model.canBack).frame(maxWidth: .infinity)
                Button { model.webView.goForward() } label: { Image(systemName: "chevron.right") }.disabled(!model.canForward).frame(maxWidth: .infinity)
                Button { model.webView.reload() } label: { Image(systemName: "arrow.clockwise") }.frame(maxWidth: .infinity)
                Button { model.grabLinks() } label: { Image(systemName: "link") }.frame(maxWidth: .infinity).accessibilityLabel("Grab links")
                Button { model.safariURL = model.webView.url } label: { Image(systemName: "safari") }.frame(maxWidth: .infinity).accessibilityLabel("Open in Safari")
            }
            .font(.title3)
            .padding(.vertical, 10)
        }
        .navigationTitle("Browser")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            let start = startURL ?? "https://www.google.com"
            address = start
            model.go(start)
        }
        .onChange(of: model.address) { address = $0 }
        .sheet(item: Binding(get: { model.safariURL.map { URLBox(url: $0) } }, set: { model.safariURL = $0?.url })) { b in
            SafariView(url: b.url)
        }
        .sheet(isPresented: Binding(get: { model.links != nil }, set: { if !$0 { model.links = nil } })) {
            NavigationView {
                List(model.links ?? [], id: \.self) { l in
                    Button {
                        if selected.contains(l) { selected.remove(l) } else { selected.insert(l) }
                    } label: {
                        HStack {
                            Image(systemName: selected.contains(l) ? "checkmark.circle.fill" : "circle").foregroundColor(Theme.red)
                            Text(l).font(.caption).lineLimit(2).foregroundColor(.primary)
                        }
                    }
                }
                .navigationTitle("Links (\(model.links?.count ?? 0))")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Close") { model.links = nil } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Download \(selected.count)") { model.downloadLinks(Array(selected)); model.links = nil; selected = [] }
                            .disabled(selected.isEmpty)
                    }
                }
                .onAppear { selected = Set(model.links ?? []) }
            }
        }
    }
}

struct URLBox: Identifiable { let url: URL; var id: String { url.absoluteString } }

struct WebViewHost: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

struct SafariView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> SFSafariViewController { SFSafariViewController(url: url) }
    func updateUIViewController(_ vc: SFSafariViewController, context: Context) {}
}
