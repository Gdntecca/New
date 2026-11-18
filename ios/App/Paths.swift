import Foundation
import SDCore

/// Where files live. Documents/<Category>/ shows up in the Files app under
/// On My iPhone › Super Downloader. Private files sit in Library/Private (hidden from Files).
enum Paths {
    static let fm = FileManager.default

    static var documents: URL { fm.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    static var privateRoot: URL {
        fm.urls(for: .libraryDirectory, in: .userDomainMask)[0].appendingPathComponent("Private", isDirectory: true)
    }
    static var support: URL {
        let u = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? fm.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    static func root(isPrivate: Bool) -> URL { isPrivate ? privateRoot : documents }

    static func ensureFolders() {
        for c in FileTypes.categories {
            try? fm.createDirectory(at: documents.appendingPathComponent(c, isDirectory: true), withIntermediateDirectories: true)
        }
        try? fm.createDirectory(at: privateRoot, withIntermediateDirectories: true)
    }

    static func fileURL(for item: DownloadItem) -> URL? {
        guard let rel = item.relativePath else { return nil }
        return root(isPrivate: item.isPrivate).appendingPathComponent(rel)
    }

    static func uniqueName(_ name: String, in dir: URL) -> String {
        if !fm.fileExists(atPath: dir.appendingPathComponent(name).path) { return name }
        let ns = name as NSString
        let ext = ns.pathExtension
        let base = ext.isEmpty ? name : ns.deletingPathExtension
        var i = 1
        while true {
            let c = ext.isEmpty ? "\(base) (\(i))" : "\(base) (\(i)).\(ext)"
            if !fm.fileExists(atPath: dir.appendingPathComponent(c).path) { return c }
            i += 1
        }
    }

    /// Creates the output file. Returns the path relative to its root, or nil when skipped.
    static func create(name: String, category: String, policy: ConflictPolicy, isPrivate: Bool) throws -> String? {
        let dir = root(isPrivate: isPrivate).appendingPathComponent(category, isDirectory: true)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        var final = name
        if fm.fileExists(atPath: dir.appendingPathComponent(name).path) {
            switch policy {
            case .overwrite: try? fm.removeItem(at: dir.appendingPathComponent(name))
            case .skip: return nil
            case .rename: final = uniqueName(name, in: dir)
            }
        }
        let url = dir.appendingPathComponent(final)
        guard fm.createFile(atPath: url.path, contents: nil) else {
            throw NSError(domain: "SD", code: 1, userInfo: [NSLocalizedDescriptionKey: "Cannot create \(final)"])
        }
        return "\(category)/\(final)"
    }

    static func freeBytes() -> Int64 {
        let v = try? documents.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return v?.volumeAvailableCapacityForImportantUsage ?? Int64.max
    }

    /// Opens the Files app at our folder.
    static var filesAppURL: URL? { URL(string: "shareddocuments://" + documents.path) }
}
