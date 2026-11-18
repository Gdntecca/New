import XCTest
@testable import SDCore

final class SegmentPlannerTests: XCTestCase {
    func testSplitCoversFileWithoutGaps() {
        let total: Int64 = 10_000_019
        let parts = SegmentPlanner.initialSplit(total: total, parts: 8, minPart: 512 * 1024)
        XCTAssertEqual(parts.count, 8)
        XCTAssertEqual(parts.first?.start, 0)
        XCTAssertEqual(parts.last?.end, total - 1)
        for i in 1..<parts.count { XCTAssertEqual(parts[i - 1].end + 1, parts[i].start) }
    }

    func testSmallFileFewerParts() {
        XCTAssertEqual(SegmentPlanner.initialSplit(total: 100_000, parts: 8, minPart: 512 * 1024).count, 1)
        XCTAssertEqual(SegmentPlanner.initialSplit(total: 1_100_000, parts: 8, minPart: 512 * 1024).count, 2)
    }

    func testOver4GB() {
        let total: Int64 = 6_710_000_000
        let parts = SegmentPlanner.initialSplit(total: total, parts: 16, minPart: 512 * 1024)
        XCTAssertEqual(parts.last?.end, total - 1)
        XCTAssertGreaterThan(parts.last!.start, Int64(Int32.max))
    }

    func testResumeOffsets() {
        XCTAssertEqual(SegmentPlanner.resumeFrom(start: 1000, downloaded: 500), 1500)
        XCTAssertEqual(SegmentPlanner.rangeHeader(start: 1000, downloaded: 500, end: 1999), "bytes=1500-1999")
        XCTAssertEqual(SegmentPlanner.rangeHeader(start: 1000, downloaded: 500, end: -1), "bytes=1500-")
        XCTAssertEqual(SegmentPlanner.remaining(start: 1000, downloaded: 500, end: 1999), 500)
    }

    func testSteal() {
        XCTAssertEqual(SegmentPlanner.stealPoint(start: 0, downloaded: 1_000_000, end: 9_999_999, minSplit: 2 * 1024 * 1024), 1_000_000 + 9_000_000 / 2)
        XCTAssertNil(SegmentPlanner.stealPoint(start: 0, downloaded: 9_000_000, end: 9_999_999, minSplit: 2 * 1024 * 1024))
        XCTAssertNil(SegmentPlanner.stealPoint(start: 0, downloaded: 0, end: -1, minSplit: 1))
    }
}

final class LinkMatcherTests: XCTestCase {
    let base = RemoteInfo(total: 7_204_000_000, etag: "\"abc123\"", lastModified: "Tue, 17 Nov 2026 10:00:00 GMT", fileName: "game.iso")

    func testSame() { XCTAssertEqual(LinkMatcher.match(old: base, new: base), .same) }

    func testWeakEtag() {
        var n = base; n.etag = "W/\"abc123\""; n.lastModified = nil; n.fileName = nil
        XCTAssertEqual(LinkMatcher.match(old: base, new: n), .same)
    }

    func testDifferentSize() {
        var n = base; n.total += 1
        XCTAssertEqual(LinkMatcher.match(old: base, new: n), .differentSize)
    }

    func testUnknownSize() {
        var n = base; n.total = -1
        XCTAssertEqual(LinkMatcher.match(old: base, new: n), .unknownSize)
    }

    func testMirrorSameName() {
        var n = base; n.etag = "other"; n.lastModified = nil; n.fileName = "GAME.iso"
        XCTAssertEqual(LinkMatcher.match(old: base, new: n), .same)
    }

    func testAllDisagree() {
        var n = base; n.etag = "zzz"; n.lastModified = "Mon, 01 Jan 2024 00:00:00 GMT"; n.fileName = "other.iso"
        XCTAssertEqual(LinkMatcher.match(old: base, new: n), .likelyDifferent)
    }

    func testExpired() {
        XCTAssertTrue(LinkMatcher.looksExpired("HTTP 403"))
        XCTAssertTrue(LinkMatcher.looksExpired("Token expired"))
        XCTAssertFalse(LinkMatcher.looksExpired("HTTP 500"))
        XCTAssertFalse(LinkMatcher.looksExpired(nil))
    }

    func testContentRange() {
        XCTAssertEqual(LinkMatcher.totalFromContentRange("bytes 0-0/12345"), 12345)
        XCTAssertEqual(LinkMatcher.totalFromContentRange("bytes 0-0/*"), -1)
        XCTAssertEqual(LinkMatcher.totalFromContentRange(nil), -1)
    }
}

final class TokenBucketTests: XCTestCase {
    func testUnlimitedNoWait() {
        let b = TokenBucket()
        for _ in 0..<1000 { XCTAssertEqual(b.consume(256 * 1024), 0) }
    }

    func testCappedWaits() {
        var now: TimeInterval = 100
        let b = TokenBucket(clock: { now })
        b.bytesPerSecond = 100_000
        XCTAssertEqual(b.consume(100_000), 1.0, accuracy: 0.001)
        now += 1
        XCTAssertEqual(b.consume(50_000), 0.5, accuracy: 0.001)
    }
}

final class FileTypesTests: XCTestCase {
    func testSniff() {
        XCTAssertEqual(FileTypes.sniff(Data("%PDF-1.7".utf8)), "pdf")
        XCTAssertEqual(FileTypes.sniff(Data([0x89, 0x50, 0x4E, 0x47, 0, 0])), "png")
        XCTAssertEqual(FileTypes.sniff(Data([0, 0, 0, 0x18] + Array("ftypisom".utf8))), "mp4")
        XCTAssertEqual(FileTypes.sniff(Data("{\"a\":1}".utf8)), "json")
    }

    func testContentDisposition() {
        XCTAssertEqual(FileTypes.parseContentDisposition("attachment; filename=\"report.pdf\""), "report.pdf")
        XCTAssertEqual(FileTypes.parseContentDisposition("attachment; filename*=UTF-8''na%C3%AFve.zip"), "naïve.zip")
        XCTAssertEqual(FileTypes.parseContentDisposition("attachment; filename=plain.txt"), "plain.txt")
    }

    func testResolveFixesMissingExtension() {
        let r = FileTypes.resolve(contentDisposition: nil, contentType: "application/octet-stream",
                                  url: URL(string: "https://x.test/get.php?id=1"), head: Data("%PDF-1.4".utf8), override: nil)
        XCTAssertEqual(r.name, "get.pdf")
        XCTAssertEqual(r.category, "Documents")
    }

    func testResolveKeepsCDName() {
        let r = FileTypes.resolve(contentDisposition: "attachment; filename=\"Movie.mkv\"", contentType: "video/x-matroska",
                                  url: URL(string: "https://x.test/dl"), head: Data(), override: nil)
        XCTAssertEqual(r.name, "Movie.mkv")
        XCTAssertEqual(r.category, "Video")
    }

    func testUnknownBecomesBin() {
        let r = FileTypes.resolve(contentDisposition: nil, contentType: nil, url: URL(string: "https://x.test/blob"), head: Data([0, 1, 2, 3]), override: nil)
        XCTAssertEqual(r.name, "blob.bin")
        XCTAssertEqual(r.category, "Other")
    }

    func testExtractURLs() {
        XCTAssertEqual(FileTypes.extractURLs("see https://a.test/x.zip, and http://b.test/y."), ["https://a.test/x.zip", "http://b.test/y"])
    }
}
