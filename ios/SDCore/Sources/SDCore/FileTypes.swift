import Foundation
import UniformTypeIdentifiers

/// Detects the real type of any download: Content-Disposition name, then Content-Type,
/// then URL extension, then magic bytes. Never blocks a type; unknown data becomes .bin.
public enum FileTypes {
    public static let categories = ["Video", "Music", "Documents", "Archives", "Programs", "Images", "Other"]

    static let video: Set<String> = ["mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "flv", "wmv", "ts", "mpg", "mpeg"]
    static let music: Set<String> = ["mp3", "m4a", "flac", "wav", "ogg", "oga", "opus", "aac", "wma", "amr"]
    static let docs: Set<String> = ["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp", "epub", "csv", "json", "md", "html", "htm", "xml"]
    static let archives: Set<String> = ["zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz", "zst"]
    static let programs: Set<String> = ["apk", "xapk", "apks", "exe", "msi", "dmg", "ipa", "deb", "rpm", "appimage", "pkg", "iso", "jar"]
    static let images: Set<String> = ["jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tif", "tiff", "avif"]
    static let scriptExts: Set<String> = ["php", "asp", "aspx", "jsp", "cgi", "pl", "do", "action"]
    static let placeholderExts: Set<String> = ["bin", "dat", "tmp", "download", "file"]
    static let genericMimes: Set<String> = ["application/octet-stream", "binary/octet-stream", "application/force-download", "application/download", "application/x-download", "application/unknown"]
    static let zipFamily: Set<String> = ["zip", "apk", "ipa", "docx", "xlsx", "pptx", "jar", "xapk", "epub", "odt"]
    static let textExts: Set<String> = ["txt", "html", "htm", "json", "xml"]
    static let extraMimeExt: [String: String] = [
        "application/vnd.android.package-archive": "apk", "application/x-7z-compressed": "7z",
        "application/x-rar-compressed": "rar", "application/vnd.rar": "rar", "application/gzip": "gz",
        "application/x-iso9660-image": "iso", "application/x-apple-diskimage": "dmg", "video/x-matroska": "mkv",
        "audio/flac": "flac", "image/heic": "heic", "application/x-msdownload": "exe", "application/json": "json",
        "video/mp4": "mp4", "audio/mpeg": "mp3", "application/zip": "zip", "application/pdf": "pdf",
    ]

    public static func category(forExt ext: String) -> String {
        let e = ext.lowercased()
        if video.contains(e) { return "Video" }
        if music.contains(e) { return "Music" }
        if docs.contains(e) { return "Documents" }
        if archives.contains(e) { return "Archives" }
        if programs.contains(e) { return "Programs" }
        if images.contains(e) { return "Images" }
        return "Other"
    }

    public static func category(forName name: String) -> String { category(forExt: ext(of: name)) }

    public static func ext(of name: String?) -> String {
        guard let name = name else { return "" }
        let last = name.split(separator: "/").last.map(String.init) ?? name
        guard let dot = last.lastIndex(of: "."), dot != last.startIndex else { return "" }
        let e = last[last.index(after: dot)...].lowercased()
        return (1...8).contains(e.count) && e.allSatisfy({ $0.isLetter || $0.isNumber }) ? e : ""
    }

    static func isKnown(_ e: String) -> Bool {
        guard !e.isEmpty, !scriptExts.contains(e), !placeholderExts.contains(e) else { return false }
        return category(forExt: e) != "Other" || UTType(filenameExtension: e)?.isDynamic == false
    }

    public static func mime(forExt e: String) -> String {
        if let t = UTType(filenameExtension: e.lowercased()), let m = t.preferredMIMEType { return m }
        return extraMimeExt.first(where: { $0.value == e.lowercased() })?.key ?? "application/octet-stream"
    }

    static func ext(forMime m: String) -> String? {
        if let e = extraMimeExt[m] { return e }
        return UTType(mimeType: m)?.preferredFilenameExtension
    }

    public static func parseContentDisposition(_ cd: String?) -> String? {
        guard let cd = cd, !cd.isEmpty else { return nil }
        if let r = cd.range(of: #"filename\*\s*=\s*[^']*''([^;]+)"#, options: [.regularExpression, .caseInsensitive]) {
            let part = String(cd[r])
            if let q = part.range(of: "''") {
                let enc = part[q.upperBound...].trimmingCharacters(in: CharacterSet(charactersIn: "\" "))
                return enc.removingPercentEncoding ?? enc
            }
        }
        if let r = cd.range(of: #"filename\s*=\s*"[^"]*""#, options: [.regularExpression, .caseInsensitive]) {
            let part = String(cd[r])
            if let first = part.firstIndex(of: "\"") {
                return String(part[part.index(after: first)...].dropLast())
            }
        }
        if let r = cd.range(of: #"filename\s*=\s*[^;]+"#, options: [.regularExpression, .caseInsensitive]) {
            let part = String(cd[r])
            if let eq = part.firstIndex(of: "=") { return part[part.index(after: eq)...].trimmingCharacters(in: .whitespaces) }
        }
        return nil
    }

    public static func name(fromURL url: URL?) -> String? {
        guard let last = url?.lastPathComponent, !last.isEmpty, last != "/" else { return nil }
        return last.removingPercentEncoding ?? last
    }

    public static func sanitize(_ name: String) -> String {
        let bad = CharacterSet(charactersIn: "\\/:*?\"<>|").union(.controlCharacters)
        let cleaned = name.unicodeScalars.map { bad.contains($0) ? "_" : String($0) }.joined()
            .trimmingCharacters(in: CharacterSet.whitespaces.union(CharacterSet(charactersIn: ".")))
        let cut = String(cleaned.prefix(180))
        return cut.isEmpty ? "download" : cut
    }

    /// Magic-byte sniffing. Returns an extension or nil.
    public static func sniff(_ d: Data) -> String? {
        let b = [UInt8](d.prefix(40000))
        let n = b.count
        func at(_ off: Int, _ sig: [UInt8]) -> Bool { n >= off + sig.count && Array(b[off..<off + sig.count]) == sig }
        func ascii(_ off: Int, _ s: String) -> Bool { at(off, Array(s.utf8)) }
        if ascii(0, "%PDF") { return "pdf" }
        if at(0, [0x50, 0x4B, 0x03, 0x04]) { return zipKind(b) }
        if ascii(0, "Rar!") { return "rar" }
        if at(0, [0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C]) { return "7z" }
        if at(0, [0x1F, 0x8B]) { return "gz" }
        if n >= 12 && ascii(4, "ftyp") {
            let brand = String(bytes: b[8..<12], encoding: .isoLatin1) ?? ""
            if brand.hasPrefix("qt") { return "mov" }
            if brand.hasPrefix("M4A") || brand.hasPrefix("M4B") { return "m4a" }
            if ["heic", "heix", "mif1", "msf1", "hevc"].contains(brand) { return "heic" }
            if brand.hasPrefix("avif") { return "avif" }
            if brand.hasPrefix("3gp") { return "3gp" }
            return "mp4"
        }
        if at(0, [0x1A, 0x45, 0xDF, 0xA3]) {
            let head = String(bytes: b.prefix(64), encoding: .isoLatin1) ?? ""
            return head.contains("webm") ? "webm" : "mkv"
        }
        if ascii(0, "RIFF") && ascii(8, "AVI ") { return "avi" }
        if ascii(0, "RIFF") && ascii(8, "WAVE") { return "wav" }
        if ascii(0, "RIFF") && ascii(8, "WEBP") { return "webp" }
        if ascii(0, "ID3") || at(0, [0xFF, 0xFB]) || at(0, [0xFF, 0xF3]) || at(0, [0xFF, 0xF2]) { return "mp3" }
        if ascii(0, "fLaC") { return "flac" }
        if ascii(0, "OggS") { return "ogg" }
        if at(0, [0xFF, 0xD8, 0xFF]) { return "jpg" }
        if at(0, [0x89, 0x50, 0x4E, 0x47]) { return "png" }
        if ascii(0, "GIF8") { return "gif" }
        if ascii(0, "MZ") { return "exe" }
        if ascii(257, "ustar") { return "tar" }
        if ascii(32769, "CD001") { return "iso" }
        return textKind(b)
    }

    static func zipKind(_ b: [UInt8]) -> String {
        let s = String(bytes: b.prefix(8192), encoding: .isoLatin1) ?? ""
        if s.contains("AndroidManifest.xml") || s.contains("classes.dex") { return "apk" }
        if s.contains("Payload/") { return "ipa" }
        if s.contains("word/") { return "docx" }
        if s.contains("xl/") { return "xlsx" }
        if s.contains("ppt/") { return "pptx" }
        if s.contains("mimetypeapplication/epub") { return "epub" }
        return "zip"
    }

    static func textKind(_ b: [UInt8]) -> String? {
        guard !b.isEmpty else { return nil }
        let head = Array(b.prefix(512))
        for c in head where c < 0x09 || (0x0E...0x1F).contains(c) { return nil }
        let t = (String(bytes: head, encoding: .utf8) ?? String(bytes: head, encoding: .isoLatin1) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("{") || t.hasPrefix("[") { return "json" }
        let lower = t.lowercased()
        if lower.hasPrefix("<!doctype html") || lower.hasPrefix("<html") { return "html" }
        if t.hasPrefix("<?xml") { return "xml" }
        return "txt"
    }

    public struct Resolved: Equatable {
        public let name: String
        public let mime: String
        public let category: String
    }

    public static func resolve(contentDisposition: String?, contentType: String?, url: URL?, head: Data, override: String?) -> Resolved {
        let cdName = parseContentDisposition(contentDisposition).map(sanitize)
        let urlName = name(fromURL: url).map(sanitize)
        let given = override.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : sanitize($0) }
        let baseName = given ?? cdName ?? urlName ?? "download"
        let ext0 = ext(of: baseName)
        var final: String
        if (given != nil || cdName != nil) && isKnown(ext0) {
            final = baseName
        } else {
            let mimeCt = contentType?.split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
                .flatMap { $0.isEmpty || genericMimes.contains($0) ? nil : $0 }
            let ctExt = mimeCt.flatMap { ext(forMime: $0) }
            let urlExtRaw = ext(of: urlName)
            let urlExt: String? = isKnown(urlExtRaw) ? urlExtRaw : (isKnown(ext0) ? ext0 : nil)
            let sniffed = sniff(head)
            let chosen: String
            if let c = ctExt, let u = urlExt, (mime(forExt: u) == mimeCt || category(forExt: u) == category(forExt: c) || (zipFamily.contains(u) && zipFamily.contains(c))) {
                chosen = u
            } else if let c = ctExt, let s = sniffed, textExts.contains(c), !textExts.contains(s) {
                chosen = s
            } else if ctExt == "zip", let s = sniffed, zipFamily.contains(s) {
                chosen = s
            } else if let c = ctExt {
                chosen = c
            } else if let u = urlExt {
                chosen = u
            } else if let s = sniffed {
                chosen = s
            } else {
                chosen = "bin"
            }
            var stem = baseName
            if !ext0.isEmpty && (isKnown(ext0) || scriptExts.contains(ext0) || placeholderExts.contains(ext0)),
               let dot = baseName.lastIndex(of: ".") {
                stem = String(baseName[..<dot])
            }
            final = "\(stem.isEmpty ? "download" : stem).\(chosen)"
        }
        let e = ext(of: final)
        return Resolved(name: final, mime: mime(forExt: e), category: category(forExt: e))
    }

    /// Finds URLs in free text.
    public static func extractURLs(_ text: String) -> [String] {
        guard let re = try? NSRegularExpression(pattern: #"https?://[^\s"'<>]+"#, options: .caseInsensitive) else { return [] }
        let ns = text as NSString
        var out: [String] = []
        for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            var s = ns.substring(with: m.range)
            while let last = s.last, ".,)]".contains(last) { s.removeLast() }
            if !out.contains(s) { out.append(s) }
        }
        return out
    }
}

public enum Fmt {
    public static func bytes(_ b: Int64) -> String {
        if b < 0 { return "?" }
        if b < 1024 { return "\(b) B" }
        let units = ["KB", "MB", "GB", "TB"]
        var v = Double(b) / 1024
        var i = 0
        while v >= 1024 && i < units.count - 1 { v /= 1024; i += 1 }
        return String(format: v >= 100 ? "%.0f %@" : "%.2f %@", v, units[i])
    }

    public static func speed(_ bps: Int64) -> String { bytes(bps) + "/s" }

    public static func duration(_ sec: Int64) -> String {
        if sec < 0 { return "--" }
        let h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60
        if h > 0 { return "\(h)h \(m)m" }
        if m > 0 { return "\(m)m \(s)s" }
        return "\(s)s"
    }
}
