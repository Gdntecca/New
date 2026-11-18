package com.superdownloader.app.engine

import android.webkit.MimeTypeMap
import java.net.URLDecoder

/**
 * Detects the real type of any download. Order: Content-Disposition filename,
 * Content-Type header, URL path extension, then magic-byte sniffing. Nothing is
 * ever blocked by type; unknown data is saved as .bin in "Other".
 */
object FileTypes {
    val categories = listOf("Video", "Music", "Documents", "Archives", "Programs", "Images", "Other")

    private val video = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "flv", "wmv", "ts", "mpg", "mpeg")
    private val music = setOf("mp3", "m4a", "flac", "wav", "ogg", "oga", "opus", "aac", "wma", "amr")
    private val docs = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp", "epub", "csv", "json", "md", "html", "htm", "xml")
    private val archives = setOf("zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz", "zst")
    private val programs = setOf("apk", "xapk", "apks", "exe", "msi", "dmg", "ipa", "deb", "rpm", "appimage", "pkg", "iso", "jar")
    private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tif", "tiff", "avif")
    private val scriptExts = setOf("php", "asp", "aspx", "jsp", "cgi", "pl", "do", "action")
    private val placeholderExts = setOf("bin", "dat", "tmp", "download", "file")
    private val genericMimes = setOf("application/octet-stream", "binary/octet-stream", "application/force-download", "application/download", "application/x-download", "application/unknown")
    private val zipFamily = setOf("zip", "apk", "ipa", "docx", "xlsx", "pptx", "jar", "xapk", "epub", "odt")
    private val textExts = setOf("txt", "html", "htm", "json", "xml")
    private val extraMimeExt = mapOf(
        "application/vnd.android.package-archive" to "apk",
        "application/x-7z-compressed" to "7z",
        "application/x-rar-compressed" to "rar",
        "application/vnd.rar" to "rar",
        "application/gzip" to "gz",
        "application/x-iso9660-image" to "iso",
        "application/x-apple-diskimage" to "dmg",
        "video/x-matroska" to "mkv",
        "audio/flac" to "flac",
        "image/heic" to "heic",
        "application/x-msdownload" to "exe",
        "application/json" to "json",
    )

    fun categoryForExt(ext: String): String = when (ext.lowercase()) {
        in video -> "Video"
        in music -> "Music"
        in docs -> "Documents"
        in archives -> "Archives"
        in programs -> "Programs"
        in images -> "Images"
        else -> "Other"
    }

    fun categoryForName(name: String) = categoryForExt(extOf(name))

    fun extOf(name: String?): String {
        if (name == null) return ""
        val n = name.substringAfterLast('/')
        if (!n.contains('.')) return ""
        val e = n.substringAfterLast('.').lowercase()
        return if (e.length in 1..8 && e.all { it.isLetterOrDigit() }) e else ""
    }

    private fun isKnown(ext: String): Boolean =
        ext.isNotEmpty() && ext !in scriptExts && ext !in placeholderExts &&
            (categoryForExt(ext) != "Other" || MimeTypeMap.getSingleton().hasExtension(ext))

    fun mimeForExt(ext: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: extraMimeExt.entries.firstOrNull { it.value == ext.lowercase() }?.key
            ?: "application/octet-stream"

    private fun extForMime(mime: String): String? =
        extraMimeExt[mime] ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)

    fun parseContentDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*([^']*)''([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            return runCatching { URLDecoder.decode(m.groupValues[2].trim().trim('"'), charset) }.getOrNull()
        }
        Regex("filename\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1] }
        Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1].trim() }
        return null
    }

    fun nameFromUrl(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?').substringAfter("://", "").substringAfter('/', "")
        val last = path.substringAfterLast('/')
        if (last.isBlank()) return null
        return runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
    }

    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        return cleaned.take(180).ifBlank { "download" }
    }

    /** Magic-byte sniffing. Returns an extension or null. */
    fun sniff(b: ByteArray, n: Int): String? {
        fun at(off: Int, vararg sig: Int): Boolean =
            n >= off + sig.size && sig.indices.all { (b[off + it].toInt() and 0xFF) == sig[it] }
        fun ascii(off: Int, s: String): Boolean = at(off, *s.map { it.code }.toIntArray())
        return when {
            ascii(0, "%PDF") -> "pdf"
            at(0, 0x50, 0x4B, 0x03, 0x04) -> zipKind(b, n)
            ascii(0, "Rar!") -> "rar"
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "7z"
            at(0, 0x1F, 0x8B) -> "gz"
            n >= 12 && ascii(4, "ftyp") -> {
                val brand = String(b, 8, 4, Charsets.ISO_8859_1)
                when {
                    brand.startsWith("qt") -> "mov"
                    brand.startsWith("M4A") || brand.startsWith("M4B") -> "m4a"
                    brand in setOf("heic", "heix", "mif1", "msf1", "hevc") -> "heic"
                    brand.startsWith("avif") -> "avif"
                    brand.startsWith("3gp") -> "3gp"
                    else -> "mp4"
                }
            }
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> if (String(b, 0, minOf(n, 64), Charsets.ISO_8859_1).contains("webm")) "webm" else "mkv"
            ascii(0, "RIFF") && ascii(8, "AVI ") -> "avi"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "wav"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "webp"
            ascii(0, "ID3") || at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) || at(0, 0xFF, 0xF2) -> "mp3"
            ascii(0, "fLaC") -> "flac"
            ascii(0, "OggS") -> "ogg"
            at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "png"
            ascii(0, "GIF8") -> "gif"
            ascii(0, "MZ") -> "exe"
            ascii(257, "ustar") -> "tar"
            ascii(32769, "CD001") -> "iso"
            else -> textKind(b, n)
        }
    }

    private fun zipKind(b: ByteArray, n: Int): String {
        val s = String(b, 0, minOf(n, 8192), Charsets.ISO_8859_1)
        return when {
            s.contains("AndroidManifest.xml") || s.contains("classes.dex") -> "apk"
            s.contains("Payload/") -> "ipa"
            s.contains("word/") -> "docx"
            s.contains("xl/") -> "xlsx"
            s.contains("ppt/") -> "pptx"
            s.contains("mimetypeapplication/epub") -> "epub"
            else -> "zip"
        }
    }

    private fun textKind(b: ByteArray, n: Int): String? {
        if (n <= 0) return null
        val len = minOf(n, 512)
        for (i in 0 until len) {
            val c = b[i].toInt() and 0xFF
            if (c < 0x09 || (c in 0x0E..0x1F)) return null
        }
        val t = String(b, 0, len, Charsets.UTF_8).trimStart()
        return when {
            t.startsWith("{") || t.startsWith("[") -> "json"
            t.startsWith("<!DOCTYPE html", true) || t.startsWith("<html", true) -> "html"
            t.startsWith("<?xml") -> "xml"
            else -> "txt"
        }
    }

    data class Resolved(val name: String, val mime: String, val category: String)

    fun resolve(
        contentDisposition: String?,
        contentType: String?,
        url: String,
        head: ByteArray,
        headLen: Int,
        override: String?,
    ): Resolved {
        val cdName = parseContentDisposition(contentDisposition)?.let { sanitize(it) }
        val urlName = nameFromUrl(url)?.let { sanitize(it) }
        val given = override?.takeIf { it.isNotBlank() }?.let { sanitize(it) }
        val baseName = given ?: cdName ?: urlName ?: "download"
        val ext0 = extOf(baseName)
        val final: String = if ((given != null || cdName != null) && isKnown(ext0)) {
            baseName
        } else {
            val mimeCt = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it !in genericMimes }
            val ctExt = mimeCt?.let { extForMime(it) }
            val urlExt = extOf(urlName).takeIf { isKnown(it) } ?: ext0.takeIf { isKnown(it) }
            val sniffed = sniff(head, headLen)
            val chosen = when {
                ctExt != null && urlExt != null &&
                    (mimeForExt(urlExt) == mimeCt || categoryForExt(urlExt) == categoryForExt(ctExt) ||
                        (urlExt in zipFamily && ctExt in zipFamily)) -> urlExt
                ctExt != null && sniffed != null && ctExt in textExts && sniffed !in textExts -> sniffed
                ctExt == "zip" && sniffed != null && sniffed in zipFamily -> sniffed
                ctExt != null -> ctExt
                urlExt != null -> urlExt
                sniffed != null -> sniffed
                else -> "bin"
            }
            val stem = if (ext0.isNotEmpty() && (isKnown(ext0) || ext0 in scriptExts || ext0 in placeholderExts))
                baseName.substringBeforeLast('.') else baseName
            "${stem.ifBlank { "download" }}.$chosen"
        }
        val ext = extOf(final)
        return Resolved(final, mimeForExt(ext), categoryForExt(ext))
    }
}
