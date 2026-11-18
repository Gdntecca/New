package com.superdownloader.app.engine

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.superdownloader.app.data.AppSettings
import com.superdownloader.app.data.ConflictPolicy
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

class WriteHandle(
    val channel: FileChannel,
    private val closeable: Closeable,
    private val raf: RandomAccessFile?,
    private val pfd: ParcelFileDescriptor?,
) {
    fun setLength(size: Long) {
        try {
            if (raf != null) raf.setLength(size)
            else if (pfd != null) Os.ftruncate(pfd.fileDescriptor, size)
        } catch (_: Throwable) { }
    }

    fun close() {
        runCatching { channel.force(false) }
        runCatching { channel.close() }
        runCatching { closeable.close() }
        runCatching { pfd?.close() }
    }
}

/**
 * Where files go: public Download/SuperDownloader/<Category>/ (MediaStore on Android 10+,
 * direct path on 8–9), a user-picked SAF folder, or the private (app-internal) folder.
 */
class OutputManager(private val ctx: Context, private val settings: AppSettings) {
    private val resolver = ctx.contentResolver

    data class Created(val uri: Uri, val name: String, val location: String)

    companion object {
        const val ROOT = "SuperDownloader"
    }

    fun hasLegacyPermission(): Boolean = Build.VERSION.SDK_INT >= 29 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun legacyBase(): File {
        val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ROOT)
        return if (hasLegacyPermission()) pub else File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), ROOT)
    }

    fun privateDir(): File = File(ctx.filesDir, "private").apply { mkdirs() }

    /** Creates the category folders where the platform allows empty folders. */
    fun ensureFolders() {
        runCatching {
            val base = if (Build.VERSION.SDK_INT >= 29)
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ROOT)
            else legacyBase()
            FileTypes.categories.forEach { File(base, it).mkdirs() }
        }
    }

    fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun create(name: String, mime: String, category: String, policy: Int, isPrivate: Boolean): Created? = when {
        isPrivate -> createFile(File(privateDir(), category), name, policy)
        settings.value.customFolderUri.isNotBlank() -> createSaf(name, mime, category, policy)
        Build.VERSION.SDK_INT >= 29 -> createMediaStore(name, mime, category, policy)
        else -> createFile(File(legacyBase(), category), name, policy)
    }

    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val hasExt = name.contains('.')
        val base = if (hasExt) name.substringBeforeLast('.') else name
        val ext = if (hasExt) name.substringAfterLast('.') else ""
        var i = 1
        while (true) {
            val c = if (hasExt) "$base ($i).$ext" else "$base ($i)"
            if (!exists(c)) return c
            i++
        }
    }

    private fun createFile(dir: File, name: String, policy: Int): Created? {
        dir.mkdirs()
        var f = File(dir, name)
        if (f.exists()) {
            when (policy) {
                ConflictPolicy.OVERWRITE -> f.delete()
                ConflictPolicy.SKIP -> return null
                else -> f = File(dir, uniqueName(name) { File(dir, it).exists() })
            }
        }
        if (!f.exists() && !f.createNewFile()) throw IOException("Cannot create ${f.path}")
        return Created(Uri.fromFile(f), f.name, f.parentFile?.absolutePath ?: dir.absolutePath)
    }

    @RequiresApi(29)
    private fun findMediaStore(name: String, rel: String): Uri? {
        val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(
            col, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, rel), null,
        )?.use { c -> if (c.moveToFirst()) return Uri.withAppendedPath(col, c.getLong(0).toString()) }
        return null
    }

    @RequiresApi(29)
    private fun createMediaStore(name: String, mime: String, category: String, policy: Int): Created? {
        return createMediaStoreAt("${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$category/", name, mime, policy)
    }

    @RequiresApi(29)
    private fun createMediaStoreAt(rel: String, name: String, mime: String, policy: Int): Created? {
        var finalName = name
        val existing = findMediaStore(name, rel)
        if (existing != null) {
            when (policy) {
                ConflictPolicy.OVERWRITE -> runCatching { resolver.delete(existing, null, null) }
                ConflictPolicy.SKIP -> return null
                else -> finalName = uniqueName(name) { findMediaStore(it, rel) != null }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create file in Downloads")
        val actual = queryName(uri) ?: finalName
        return Created(uri, actual, "/storage/emulated/0/$rel".trimEnd('/'))
    }

    private fun queryName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun safDir(category: String): DocumentFile {
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(settings.value.customFolderUri))
            ?: throw IOException("Custom folder is not accessible")
        return tree.findFile(category)?.takeIf { it.isDirectory } ?: tree.createDirectory(category) ?: tree
    }

    private fun createSaf(name: String, mime: String, category: String, policy: Int): Created? {
        val dir = safDir(category)
        var finalName = name
        val ex = dir.findFile(name)
        if (ex != null) {
            when (policy) {
                ConflictPolicy.OVERWRITE -> ex.delete()
                ConflictPolicy.SKIP -> return null
                else -> finalName = uniqueName(name) { dir.findFile(it) != null }
            }
        }
        val doc = dir.createFile(mime, finalName) ?: throw IOException("Cannot create file in custom folder")
        return Created(doc.uri, doc.name ?: finalName, "${Uri.decode(settings.value.customFolderUri).substringAfterLast(':')}/$category")
    }

    fun openWrite(uri: Uri): WriteHandle {
        return if (uri.scheme == "file") {
            val raf = RandomAccessFile(uri.path!!, "rw")
            WriteHandle(raf.channel, raf, raf, null)
        } else {
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot open output file")
            val fos = FileOutputStream(pfd.fileDescriptor)
            WriteHandle(fos.channel, fos, null, pfd)
        }
    }

    fun openInput(uri: Uri): InputStream? = runCatching { resolver.openInputStream(uri) }.getOrNull()

    fun openOutput(uri: Uri): OutputStream? = runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()

    fun finalize(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY) {
            runCatching {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } else if (uri.scheme == "file") {
            runCatching { MediaScannerConnection.scanFile(ctx, arrayOf(uri.path), null, null) }
        }
    }

    fun delete(uri: Uri): Boolean = runCatching {
        when {
            uri.scheme == "file" -> File(uri.path!!).delete()
            DocumentsContract.isDocumentUri(ctx, uri) -> DocumentsContract.deleteDocument(resolver, uri)
            else -> resolver.delete(uri, null, null) > 0
        }
    }.getOrDefault(false)

    /** A content:// uri other apps can read. */
    fun shareUri(uri: Uri): Uri =
        if (uri.scheme == "file") FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(uri.path!!)) else uri

    /** Creates a file for one ZIP entry next to the archive, in a folder named after it. */
    fun createExtracted(folder: String, relPath: String, isPrivate: Boolean, policy: Int): Uri? {
        val parts = relPath.split('/').filter { it.isNotBlank() && it != "." && it != ".." }
        if (parts.isEmpty()) return null
        val fileName = FileTypes.sanitize(parts.last())
        val subDirs = parts.dropLast(1).map { FileTypes.sanitize(it) }
        val mime = FileTypes.mimeForExt(FileTypes.extOf(fileName))
        return when {
            isPrivate -> createFile(File(File(File(privateDir(), "Archives"), folder), subDirs.joinToString("/")), fileName, policy)?.uri
            settings.value.customFolderUri.isNotBlank() -> {
                var dir = safDir("Archives")
                for (d in listOf(folder) + subDirs) dir = dir.findFile(d)?.takeIf { it.isDirectory } ?: dir.createDirectory(d) ?: dir
                dir.createFile(mime, fileName)?.uri
            }
            Build.VERSION.SDK_INT >= 29 -> {
                val rel = (listOf(Environment.DIRECTORY_DOWNLOADS, ROOT, "Archives", folder) + subDirs).joinToString("/") + "/"
                createMediaStoreAt(rel, fileName, mime, policy)?.uri
            }
            else -> createFile(File(File(File(legacyBase(), "Archives"), folder), subDirs.joinToString("/")), fileName, policy)?.uri
        }
    }

    /** Moves a finished file into the private folder. Returns the new uri. */
    fun moveToPrivate(uri: Uri, name: String, category: String): Uri? {
        val created = createFile(File(privateDir(), category), name, ConflictPolicy.RENAME) ?: return null
        val ok = runCatching {
            openInput(uri)!!.use { input -> FileOutputStream(File(created.uri.path!!)).use { input.copyTo(it, 64 * 1024) } }
            true
        }.getOrDefault(false)
        if (!ok) { File(created.uri.path!!).delete(); return null }
        delete(uri)
        return created.uri
    }
}
