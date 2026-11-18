package com.superdownloader.app.engine

import android.net.Uri
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object Checksums {
    fun compute(output: OutputManager, uri: Uri, type: String): String? {
        val algo = if (type.uppercase().contains("MD5")) "MD5" else "SHA-256"
        val md = MessageDigest.getInstance(algo)
        val input = output.openInput(uri) ?: return null
        input.use {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }
}

object ZipExtractor {
    /** Extracts every entry; skips entries that try to escape the folder. Returns entry count. */
    fun extract(output: OutputManager, zipUri: Uri, zipName: String, isPrivate: Boolean, policy: Int): Int {
        val folder = FileTypes.sanitize(zipName.substringBeforeLast('.'))
        var count = 0
        val input = output.openInput(zipUri) ?: return 0
        ZipInputStream(input.buffered(64 * 1024)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                if (name.split('/').any { it == ".." }) continue
                val target = output.createExtracted(folder, name, isPrivate, policy) ?: continue
                output.openOutput(target)?.use { out -> zis.copyTo(out, 64 * 1024) }
                output.finalize(target)
                count++
            }
        }
        return count
    }
}
