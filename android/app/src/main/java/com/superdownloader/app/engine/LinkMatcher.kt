package com.superdownloader.app.engine

/** What a server says about a file, from HEAD or a one-byte range request. */
data class RemoteInfo(
    val total: Long,
    val etag: String? = null,
    val lastModified: String? = null,
    val fileName: String? = null,
    val supportsRange: Boolean = false,
    val finalUrl: String? = null,
)

enum class LinkMatch {
    /** Same size and, where both sides report one, a matching ETag, Last-Modified or file name. */
    SAME,
    /** Size matches but every validator both sides report disagrees. */
    LIKELY_DIFFERENT,
    /** The total size differs. */
    DIFFERENT_SIZE,
    /** One side does not report a size, so the file cannot be confirmed. */
    UNKNOWN_SIZE,
}

/** Pure matching logic for "Change link", kept free of Android APIs so it is unit-tested. */
object LinkMatcher {
    fun normalizeEtag(e: String?): String? =
        e?.trim()?.removePrefix("W/")?.trim('"')?.takeIf { it.isNotEmpty() }

    private fun normalizeName(n: String?): String? =
        n?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun match(old: RemoteInfo, new: RemoteInfo): LinkMatch {
        if (old.total <= 0 || new.total <= 0) return LinkMatch.UNKNOWN_SIZE
        if (old.total != new.total) return LinkMatch.DIFFERENT_SIZE
        val checks = ArrayList<Boolean>()
        val oe = normalizeEtag(old.etag)
        val ne = normalizeEtag(new.etag)
        if (oe != null && ne != null) checks.add(oe == ne)
        val ol = old.lastModified?.trim()?.takeIf { it.isNotEmpty() }
        val nl = new.lastModified?.trim()?.takeIf { it.isNotEmpty() }
        if (ol != null && nl != null) checks.add(ol == nl)
        val on = normalizeName(old.fileName)
        val nn = normalizeName(new.fileName)
        if (on != null && nn != null) checks.add(on == nn)
        return if (checks.isEmpty() || checks.any { it }) LinkMatch.SAME else LinkMatch.LIKELY_DIFFERENT
    }

    /** True when a failure looks like an expired or revoked link. */
    fun looksExpired(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val e = error.lowercase()
        return Regex("\\bhttp (401|403|404|410|498)\\b").containsMatchIn(e) ||
            e.contains("expired") || e.contains("token") || e.contains("signature") || e.contains("forbidden")
    }

    /** Parses the total from a Content-Range header such as "bytes 0-0/12345". */
    fun totalFromContentRange(h: String?): Long =
        h?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
}
