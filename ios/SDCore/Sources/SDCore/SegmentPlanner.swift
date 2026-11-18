import Foundation

/// Pure piece arithmetic for segmented downloads. Ranges are inclusive byte offsets (64-bit).
public enum SegmentPlanner {
    public struct Range: Equatable, Codable {
        public var start: Int64
        public var end: Int64
        public init(start: Int64, end: Int64) { self.start = start; self.end = end }
    }

    /// Splits [0, total) into at most `parts` pieces, none smaller than `minPart` (a tiny file gets one).
    public static func initialSplit(total: Int64, parts: Int, minPart: Int64) -> [Range] {
        guard total > 0 else { return [] }
        let byMin = max(Int64(1), total / max(minPart, 1))
        let n = Int(min(Int64(max(parts, 1)), byMin))
        let size = total / Int64(n)
        return (0..<n).map { i in
            let st = Int64(i) * size
            return Range(start: st, end: i == n - 1 ? total - 1 : st + size - 1)
        }
    }

    public static func resumeFrom(start: Int64, downloaded: Int64) -> Int64 { start + downloaded }

    /// Bytes left in a piece; Int64.max when its end is unknown (-1).
    public static func remaining(start: Int64, downloaded: Int64, end: Int64) -> Int64 {
        end < 0 ? Int64.max : end - resumeFrom(start: start, downloaded: downloaded) + 1
    }

    /// Work stealing: the new piece starts halfway through what is left of the victim.
    public static func stealPoint(start: Int64, downloaded: Int64, end: Int64, minSplit: Int64) -> Int64? {
        let rem = remaining(start: start, downloaded: downloaded, end: end)
        guard end >= 0, rem >= minSplit else { return nil }
        return resumeFrom(start: start, downloaded: downloaded) + rem / 2
    }

    public static func rangeHeader(start: Int64, downloaded: Int64, end: Int64) -> String {
        let from = resumeFrom(start: start, downloaded: downloaded)
        return end >= 0 ? "bytes=\(from)-\(end)" : "bytes=\(from)-"
    }
}
