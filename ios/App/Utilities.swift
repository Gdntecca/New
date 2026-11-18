import SwiftUI
import UIKit
import QuickLook
import LocalAuthentication
import AVFoundation

/// Opens finished files in the system viewer (Quick Look, with "Open in…" from its share button).
enum FileOpener {
    private static var dataSource: PreviewSource?

    static func open(_ item: DownloadItem) {
        guard let url = Paths.fileURL(for: item), FileManager.default.fileExists(atPath: url.path) else {
            Task { @MainActor in Store.shared.toast("File not found") }
            return
        }
        let src = PreviewSource(url: url)
        dataSource = src
        let ql = QLPreviewController()
        ql.dataSource = src
        topController()?.present(ql, animated: true)
    }

    static func share(_ item: DownloadItem) {
        guard let url = Paths.fileURL(for: item) else { return }
        let vc = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        if let top = topController() {
            vc.popoverPresentationController?.sourceView = top.view
            vc.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 1, height: 1)
            top.present(vc, animated: true)
        }
    }

    static func topController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let p = top?.presentedViewController { top = p }
        return top
    }

    final class PreviewSource: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}

/// Face ID / Touch ID (or passcode) gate for the private folder.
enum Biometrics {
    static func unlock(_ done: @escaping (Bool) -> Void) {
        let ctx = LAContext()
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else { done(false); return }
        ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Unlock your private downloads") { ok, _ in
            DispatchQueue.main.async { done(ok) }
        }
    }
}

/// QR scanner using AVFoundation (works on iOS 15).
struct QRScannerView: UIViewControllerRepresentable {
    var onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let vc = ScannerController()
        vc.onCode = onCode
        return vc
    }

    func updateUIViewController(_ vc: ScannerController, context: Context) {}

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?
        private let session = AVCaptureSession()
        private var preview: AVCaptureVideoPreviewLayer?
        private var done = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            let label = UILabel()
            label.text = "Point at a QR code with a link"
            label.textColor = .white
            label.textAlignment = .center
            label.translatesAutoresizingMaskIntoConstraints = false
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    guard granted else { label.text = "Camera access is off. Turn it on in Settings."; return }
                    self.configure()
                }
            }
            view.addSubview(label)
            NSLayoutConstraint.activate([
                label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
                label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
                label.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
            ])
        }

        private func configure() {
            guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else { return }
            session.addInput(input)
            let out = AVCaptureMetadataOutput()
            guard session.canAddOutput(out) else { return }
            session.addOutput(out)
            out.setMetadataObjectsDelegate(self, queue: .main)
            out.metadataObjectTypes = [.qr]
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            layer.frame = view.bounds
            view.layer.insertSublayer(layer, at: 0)
            preview = layer
            DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            preview?.frame = view.bounds
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            if session.isRunning { session.stopRunning() }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !done, let code = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
            done = true
            session.stopRunning()
            onCode?(code)
        }
    }
}
