package com.superdownloader.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkMatcherTest {
    private val base = RemoteInfo(total = 7_204_000_000L, etag = "\"abc123\"", lastModified = "Tue, 17 Nov 2026 10:00:00 GMT", fileName = "game.iso")

    @Test fun sameSizeAndEtag() = assertEquals(LinkMatch.SAME, LinkMatcher.match(base, base.copy()))

    @Test fun weakEtagEqualsStrong() =
        assertEquals(LinkMatch.SAME, LinkMatcher.match(base, base.copy(etag = "W/\"abc123\"", lastModified = null, fileName = null)))

    @Test fun differentSize() = assertEquals(LinkMatch.DIFFERENT_SIZE, LinkMatcher.match(base, base.copy(total = 7_204_000_001L)))

    @Test fun unknownSize() {
        assertEquals(LinkMatch.UNKNOWN_SIZE, LinkMatcher.match(base, base.copy(total = -1)))
        assertEquals(LinkMatch.UNKNOWN_SIZE, LinkMatcher.match(base.copy(total = 0), base))
    }

    @Test fun sizeOnlyWhenNoValidators() =
        assertEquals(LinkMatch.SAME, LinkMatcher.match(RemoteInfo(100), RemoteInfo(100, etag = "x")))

    @Test fun mirrorWithDifferentEtagButSameName() =
        assertEquals(LinkMatch.SAME, LinkMatcher.match(base, base.copy(etag = "other", lastModified = null, fileName = "GAME.iso")))

    @Test fun allValidatorsDisagree() =
        assertEquals(LinkMatch.LIKELY_DIFFERENT, LinkMatcher.match(base, base.copy(etag = "zzz", lastModified = "Mon, 01 Jan 2024 00:00:00 GMT", fileName = "other.iso")))

    @Test fun expiredDetection() {
        assertTrue(LinkMatcher.looksExpired("HTTP 403"))
        assertTrue(LinkMatcher.looksExpired("HTTP 410"))
        assertTrue(LinkMatcher.looksExpired("Token expired"))
        assertFalse(LinkMatcher.looksExpired("HTTP 500"))
        assertFalse(LinkMatcher.looksExpired("Connection closed early"))
        assertFalse(LinkMatcher.looksExpired(null))
    }

    @Test fun contentRange() {
        assertEquals(12345L, LinkMatcher.totalFromContentRange("bytes 0-0/12345"))
        assertEquals(-1L, LinkMatcher.totalFromContentRange("bytes 0-0/*"))
        assertEquals(-1L, LinkMatcher.totalFromContentRange(null))
    }
}
