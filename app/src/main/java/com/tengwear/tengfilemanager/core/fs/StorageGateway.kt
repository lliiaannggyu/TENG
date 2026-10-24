package com.tengwear.tengfilemanager.core.fs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.model.FileKind
import com.tengwear.tengfilemanager.core.model.Location
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 存储网关：把「应用沙盒(File)」和「SAF 授权目录(DocumentsContract)」收敛成同一套 API。
 *
 * 设计要点：
 * 1. 不做 DocumentFile 的层层封装。DocumentFile.listFiles() 会为每个子项单独 query，
 *    一个 200 项的目录就是 201 次跨进程调用——在手表上足以卡住主线程。
 *    这里对 SAF 只用 **一次** child-documents cursor 查询拿全 size/mtime/mime。
 * 2. 所有 Location 的 uri 一律是 **document uri**（不是 tree uri），
 *    这样 createDocument / renameDocument / deleteDocument 可以直接复用。
 * 3. 沙盒路径走 java.io.File，速度是 SAF 的 5~10 倍；这也是"默认只操作沙盒"的性能理由。
 */
class StorageGateway(private val context: Context) {

    private val resolver get() = context.contentResolver

    // ---------------------------------------------------------------- 位置枚举

    /** 沙盒目录：应用内部存储 + 应用外部私有目录。无需任何权限。 */
    fun sandboxLocations(): List<Location> = buildList {
        add(Location(Uri.fromFile(context.filesDir), null, "应用内部存储", isSandbox = true))
        context.getExternalFilesDir(null)?.let {
            add(Location(Uri.fromFile(it), null, "应用外部存储", isSandbox = true))
        }
    }

    /** 用户通过 SAF 长期授权的目录（persisted permission）。 */
    fun authorizedLocations(): List<Location> =
        resolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .mapNotNull { permission ->
                val tree = permission.uri
                runCatching {
                    val docId = DocumentsContract.getDocumentId(tree)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    val name = displayName(docUri) ?: tree.lastPathSegment ?: "已授权目录"
                    Location(docUri, tree, name)
                }.getOrNull()
            }
            .distinctBy { it.uri }

    fun allRoots(): List<Location> = sandboxLocations() + authorizedLocations()

    fun defaultRoot(): Location = sandboxLocations().first()

    /** 从系统文件选择器带回的 tree uri 生成 Location，并持久化授权。 */
    fun locationFromTreeUri(treeUri: Uri): Location? = runCatching {
        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val docId = DocumentsContract.getDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        Location(docUri, treeUri, displayName(docUri) ?: "已授权目录")
    }.getOrNull()

    fun releaseLocation(location: Location) {
        location.treeUri?.let { tree ->
            runCatching { resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    // ---------------------------------------------------------------- 列表

    /**
     * 列出目录内容。失败返回空列表而不是抛异常——UI 只需要"空"和"错误提示"两种态，
     * 异常在 IO 层已经转成可读文案（见 [lastError]）。
     */
    fun list(location: Location): List<FileEntry> {
        return try {
            if (location.isSandbox || location.uri.scheme == "file") listFile(location) else listSaf(location)
        } catch (t: Throwable) {
            lastError = readableError(t)
            emptyList()
        }
    }

    var lastError: String? = null
        private set

    private fun listFile(location: Location): List<FileEntry> {
        val dir = File(requireNotNull(location.uri.path))
        val children = dir.listFiles() ?: run {
            lastError = "无法读取目录"
            return emptyList()
        }
        return children.map { f ->
            val mime = mimeOf(f.name, f.isDirectory)
            FileEntry(
                uri = Uri.fromFile(f),
                name = f.name,
                isDirectory = f.isDirectory,
                size = if (f.isDirectory) 0L else f.length(),
                lastModified = f.lastModified(),
                mimeType = mime,
                kind = FileKind.of(f.name, mime, f.isDirectory),
            )
        }
    }

    private fun listSaf(location: Location): List<FileEntry> {
        val tree = location.treeUri ?: return emptyList()
        val parentDocId = DocumentsContract.getDocumentId(location.uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<FileEntry>(64)
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(projection[0])
            val nameIdx = cursor.getColumnIndexOrThrow(projection[1])
            val mimeIdx = cursor.getColumnIndexOrThrow(projection[2])
            val sizeIdx = cursor.getColumnIndexOrThrow(projection[3])
            val timeIdx = cursor.getColumnIndexOrThrow(projection[4])
            while (cursor.moveToNext()) {
                val childId = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                val mime = cursor.getString(mimeIdx)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)
                val time = if (cursor.isNull(timeIdx)) 0L else cursor.getLong(timeIdx)
                out += FileEntry(
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId),
                    name = name,
                    isDirectory = isDir,
                    size = if (isDir) 0L else size,
                    lastModified = time,
                    mimeType = mime,
                    kind = FileKind.of(name, mime, isDir),
                )
            }
        }
        return out
    }

    fun childLocation(parent: Location, child: FileEntry): Location =
        Location(child.uri, parent.treeUri, child.name, isSandbox = parent.isSandbox)

    fun parentLocation(location: Location): Location? {
        if (location.isSandbox || location.uri.scheme == "file") {
            val parent = File(location.uri.path ?: return null).parentFile ?: return null
            return Location(Uri.fromFile(parent), null, parent.name, isSandbox = true)
        }
        val tree = location.treeUri ?: return null
        val docId = DocumentsContract.getDocumentId(location.uri)
        val separator = docId.lastIndexOf('/')
        if (separator <= 0) return location
        val parentId = docId.substring(0, separator)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        return Location(parentUri, tree, displayName(parentUri) ?: "上级")
    }

    // ---------------------------------------------------------------- 读写

    fun openInput(uri: Uri): InputStream =
        if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream()
        else resolver.openInputStream(uri) ?: error("无法打开输入流")

    fun openOutput(uri: Uri): OutputStream =
        if (uri.scheme == "file") File(requireNotNull(uri.path)).outputStream()
        else resolver.openOutputStream(uri, "wt") ?: error("无法打开输出流")

    fun exists(uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") File(uri.path!!).exists()
        else resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    fun sizeOf(uri: Uri): Long = runCatching {
        if (uri.scheme == "file") File(uri.path!!).length()
        else resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    fun displayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") File(uri.path!!).name
        else resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    fun mimeOf(name: String, isDirectory: Boolean): String? {
        if (isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    // ---------------------------------------------------------------- 变更

    fun createDirectory(parent: Location, name: String): Uri? = runCatching {
        if (parent.isSandbox || parent.uri.scheme == "file") {
            val dir = File(parent.uri.path!!, name)
            if (dir.mkdirs()) Uri.fromFile(dir) else null
        } else {
            DocumentsContract.createDocument(
                resolver, parent.uri, DocumentsContract.Document.MIME_TYPE_DIR, name,
            )
        }
    }.getOrNull()

    fun createFile(parent: Location, name: String): Uri? = runCatching {
        val mime = mimeOf(name, false) ?: "application/octet-stream"
        if (parent.isSandbox || parent.uri.scheme == "file") {
            val file = File(parent.uri.path!!, name)
            if (file.createNewFile()) Uri.fromFile(file) else null
        } else {
            DocumentsContract.createDocument(resolver, parent.uri, mime, name)
        }
    }.getOrNull()

    fun rename(uri: Uri, newName: String): Uri? = runCatching {
        if (uri.scheme == "file") {
            val src = File(uri.path!!)
            val dst = File(src.parentFile, newName)
            if (src.renameTo(dst)) Uri.fromFile(dst) else null
        } else {
            DocumentsContract.renameDocument(resolver, uri, newName)
        }
    }.getOrNull()

    fun delete(uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") {
            val file = File(uri.path!!)
            file.deleteRecursively()
        } else {
            DocumentsContract.deleteDocument(resolver, uri)
        }
    }.getOrDefault(false)

    fun isWritable(location: Location): Boolean = runCatching {
        if (location.isSandbox || location.uri.scheme == "file") {
            File(location.uri.path!!).canWrite()
        } else {
            resolver.persistedUriPermissions.any { it.uri == location.treeUri && it.isWritePermission } ||
                location.uri.scheme == "content"
        }
    }.getOrDefault(false)

    /**
     * 反查某个 Uri 属于哪个已授权根，用于"搜索结果 / 收藏项"重新进入目录。
     * SAF 的子文档 Uri 必须配回它的 tree，否则无法继续往下浏览。
     */
    fun locationFor(uri: Uri): Location? {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return null)
            return Location(uri, null, file.name.ifEmpty { "存储根" }, isSandbox = true)
        }
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        authorizedLocations().forEach { root ->
            val tree = root.treeUri ?: return@forEach
            val rootId = runCatching { DocumentsContract.getDocumentId(root.uri) }.getOrNull() ?: return@forEach
            if (docId == rootId || docId.startsWith("$rootId/")) {
                return Location(uri, tree, displayName(uri) ?: root.name)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 容量

    /** 返回 (已用字节, 总字节)，取自沙盒所在卷，用于首页圆环。 */
    fun volumeUsage(): Pair<Long, Long> {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return runCatching {
            val total = dir.totalSpace
            val free = dir.usableSpace
            (total - free).coerceAtLeast(0L) to total
        }.getOrDefault(0L to 0L)
    }

    // ---------------------------------------------------------------- 手动输入路径

    sealed interface PathResult {
        data class Ok(val location: Location) : PathResult

        /** 路径合法但当前没有读权限：引导用户用 SAF 授权该目录。 */
        data class NeedsGrant(val path: String, val message: String) : PathResult
        data class Invalid(val message: String) : PathResult
    }

    /**
     * 手动输入路径。手表上没有键盘友好度可言，但仍保留这条通路：
     *  · `content://.../tree/...` → 直接当 SAF 目录；
     *  · `/sdcard/...`、`/storage/emulated/0/...` → 转成 File，能读就打开；
     *  · 相对路径 → 相对当前沙盒根。
     */
    fun resolveManualPath(input: String, base: Location? = null): PathResult {
        val text = input.trim()
        if (text.isEmpty()) return PathResult.Invalid("路径为空")

        if (text.startsWith("content://", ignoreCase = true)) {
            val uri = runCatching { Uri.parse(text) }.getOrNull()
                ?: return PathResult.Invalid("无法解析该 Uri")
            val segments = uri.pathSegments
            return if (segments.contains("tree")) {
                val tree = runCatching { Uri.parse(text.substringBeforeLast("/document/")) }.getOrNull() ?: uri
                val location = locationFromTreeUri(tree)
                if (location != null) PathResult.Ok(location)
                else PathResult.NeedsGrant(text, "该目录尚未授权，请重新选择授权")
            } else {
                PathResult.NeedsGrant(text, "仅支持已授权的目录 Uri")
            }
        }

        val target: File = when {
            text.startsWith("/") -> File(text)
            base != null && (base.isSandbox || base.uri.scheme == "file") ->
                File(base.uri.path!!, text)
            else -> File(context.filesDir, text)
        }

        if (!target.exists()) return PathResult.Invalid("路径不存在")
        if (!target.isDirectory) return PathResult.Invalid("这不是文件夹")
        if (!target.canRead()) {
            return PathResult.NeedsGrant(
                target.absolutePath,
                "系统目录需要授权后才能访问（本应用不申请全盘存储权限）",
            )
        }
        return PathResult.Ok(Location(Uri.fromFile(target), null, target.name.ifEmpty { "存储根" }, isSandbox = true))
    }

    private fun readableError(t: Throwable): String = when (t) {
        is SecurityException -> "没有该目录的访问权限"
        is java.io.FileNotFoundException -> "目录已被移动或删除"
        else -> t.message?.take(60) ?: "读取失败"
    }
}
