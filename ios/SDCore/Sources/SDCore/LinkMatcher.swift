import Foundation

/// What a server reports about a file (from HEAD or a one-byte range request).
public struct RemoteInfo: Equatable {
    public var total: Int64
    public var etag: String?
    public var lastModified: String?
    public var fileName: String?
    public var supportsRange: Bool
    public var finalURL: URL?

    public init(total: Int64, etag: String? = nil, lastModified: String? = nil, fileName: String? = nil, supportsRange: Bool = false, finalURL: URL? = nil) {
        self.total = total; self.etag = etag; self.lastModified = lastModified
        self.fileName = fileName; self.supportsRange = supportsRange; self.finalURL = finalURL
    }
}

public enum LinkMatch: Equatable {
    case same, likelyDifferent, differentSize, unknownSize
}

/// Matching logic for "Change link" (unit-tested).
public enum LinkMatcher {
    public static func normalizeEtag(_ e: String?) -> String? {
        guard var s = e?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
        if s.hasPrefix("W/") { s.removeFirst(2) }
        s = s.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
        return s.isEmpty ? nil : s
    }

    private static func clean(_ s: String?) -> String? {
        guard let t = s?.trimmingCharacters(in: .whitespaces), !t.isEmpty else { return nil }
        return t
    }

    public static func match(old: RemoteInfo, new: RemoteInfo) -> LinkMatch {
        if old.total <= 0 || new.total <= 0 { return .unknownSize }
        if old.total != new.total { return .differentSize }
        var checks: [Bool] = []
        if let a = normalizeEtag(old.etag), let b = normalizeEtag(new.etag) { checks.append(a == b) }
        if let a = clean(old.lastModified), let b = clean(new.lastModified) { checks.append(a == b) }
        if let a = clean(old.fileName)?.lowercased(), let b = clean(new.fileName)?.lowercased() { checks.append(a == b) }
        return (checks.isEmpty || checks.contains(true)) ? .same : .likelyDifferent
    }

    /// True when an error looks like an expired or revoked link.
    public static func looksExpired(_ error: String?) -> Bool {
        guard let e = error?.lowercased(), !e.isEmpty else { return false }
        for code in ["http 401", "http 403", "http 404", "http 410", "http 498"] where e.contains(code) { return true }
        return e.contains("expired") || e.contains("token") || e.contains("signature") || e.contains("forbidden")
    }

    /// Total from a Content-Range header such as "bytes 0-0/12345".
    public static func totalFromContentRange(_ h: String?) -> Int64 {
        guard let h = h, let slash = h.lastIndex(of: "/") else { return -1 }
        return Int64(h[h.index(after: slash)...].trimmingCharacters(in: .whitespaces)) ?? -1
    }
}
