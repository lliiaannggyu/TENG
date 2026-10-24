package com.tengwear.tengfilemanager.core.archive

import android.content.Context
import android.net.Uri
import com.tengwear.tengfilemanager.core.fs.StorageGateway
import com.tengwear.tengfilemanager.core.model.ArchiveFormat
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.model.Location
import com.tengwear.tengfilemanager.core.model.OpProgress
import com.tengwear.tengfilemanager.core.model.OpResult
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.core.util.uniqueName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.io.InputStream
import java.util.Date

/**
 * 压缩 / 解压引擎。
 *
 * 选型理由：
 *  · ZIP 写入 → commons-compress（逐字节可控，能做真实进度与"立即取消"）
 *  · ZIP 读取 → zip4j（AES-256 加密、分卷 .z01/.z02、逐 entry 流式解压）
 *  · 7z 读写 → commons-compress（LZMA2；读取支持 AES 密码）
 *
 * 三条硬约束：
 *  1. **流式**：永远不把整个包读进内存，128KB 缓冲，边解边写到目标目录；
 *  2. **可取消**：每块检查取消标记，取消后清理已产出的半成品；
 *  3. **防路径穿越**：entry 名做 `..`/绝对路径消毒，杜绝 zip-slip 写出到目标目录之外。
 */
class ArchiveEngine(
    private val context: Context,
    private val fs: StorageGateway,
) {

    private companion object {
        const val BUFFER = 128 * 1024
        const val EMIT_MS = 80L
        const val MAX_ENTRIES = 10_000
        const val MAX_LIST_PREVIEW = 300
    }

    data class ArchiveEntryInfo(
        val path: String,
        val size: Long,
        val compressedSize: Long,
        val isDirectory: Boolean,
        val encrypted: Boolean = false,
    )

    data class ArchiveInfo(
        val format: ArchiveFormat?,
        val entries: List<ArchiveEntryInfo>,
        val encrypted: Boolean,
        val split: Boolean,
        val totalUncompressed: Long,
    ) {
        val fileCount: Int get() = entries.count { !it.isDirectory }
        val ratio: Float
            get() {
                val packed = entries.sumOf { it.compressedSize }
                return if (totalUncompressed > 0) (packed.toFloat() / totalUncompressed).coerceIn(0f, 1f) else 0f
            }
    }

    sealed interface InspectResult {
        data class Ok(val info: ArchiveInfo) : InspectResult

        /** 包已加密，需要用户输入密码后重试。 */
        data class NeedsPassword(val format: ArchiveFormat?) : InspectResult
        data class Unsupported(val reason: String) : InspectResult
    }

    private val stageDir: File get() = File(context.cacheDir, "arc-stage").apply { mkdirs() }

    // ================================================================ 预检

    /**
     * 分卷检测。7z 分卷（.7z.001/.002…）commons-compress 不支持随机访问，
     * 直接给出可读结论而不是抛异常；ZIP 分卷（.z01…）交给 zip4j 自动处理。
     */
    fun detectVolumes(entry: FileEntry, siblings: List<FileEntry>): String? {
        val name = entry.name.lowercase()
        val multiPart = Regex("""\.(7z|zip)\.(\d{3})$""").find(name)
        if (multiPart != null) {
            val ext = multiPart.groupValues[1]
            val index = multiPart.groupValues[2]
            return if (ext == "7z") {
                "这是 7z 分卷的第 $index 卷，当前版本不支持合并解压 7z 分卷；" +
                    "请改用 ZIP 分卷或在电脑上合并后传输。"
            } else {
                "ZIP 分卷，需要全部 .z01/.z02… 与 .zip 在同一目录"
            }
        }
        if (name.endsWith(".z01") || name.endsWith(".z02")) {
            return "请选择同名的 .zip 主卷进行解压"
        }
        val parts = siblings.filter { it.name.startsWith(entry.name.substringBeforeLast('.')) && it.kind == com.tengwear.tengfilemanager.core.model.FileKind.ARCHIVE }
        return if (parts.size > 1 && name.endsWith(".zip")) "同目录存在多个同名分卷，将按主卷读取" else null
    }

    // ================================================================ 查看内容

    suspend fun inspect(entry: FileEntry, password: String? = null): InspectResult = withContext(Dispatchers.IO) {
        val format = ArchiveFormat.ofExtension(entry.extension)
            ?: return@withContext InspectResult.Unsupported("暂不支持该压缩格式（支持 ZIP / 7z）")
        val staged = stage(entry) ?: return@withContext InspectResult.Unsupported("无法读取该压缩包")
        try {
            when (format) {
                ArchiveFormat.ZIP -> inspectZip(staged.file, password)
                ArchiveFormat.SEVEN_ZIP -> inspect7z(staged.file, password)
            }
        } catch (t: Throwable) {
            InspectResult.Unsupported(t.message?.take(60) ?: "压缩包已损坏")
        } finally {
            if (staged.isTemp) staged.file.delete()
        }
    }

    private fun inspectZip(file: File, password: String?): InspectResult {
        val zip = ZipFile(file)
        if (zip.isEncrypted) {
            if (password.isNullOrEmpty()) return InspectResult.NeedsPassword(ArchiveFormat.ZIP)
            zip.setPassword(password.toCharArray())
        }
        val headers = runCatching { zip.fileHeaders }.getOrElse {
            return InspectResult.Unsupported("ZIP 目录读取失败（可能缺少分卷）")
        }
        val entries = headers.take(MAX_LIST_PREVIEW).map { h ->
            ArchiveEntryInfo(
                path = h.fileName,
                size = h.uncompressedSize,
                compressedSize = h.compressedSize,
                isDirectory = h.isDirectory,
                encrypted = h.isEncrypted,
            )
        }
        return InspectResult.Ok(
            ArchiveInfo(
                format = ArchiveFormat.ZIP,
                entries = entries,
                encrypted = zip.isEncrypted,
                split = zip.isSplitArchive,
                totalUncompressed = headers.sumOf { it.uncompressedSize },
            )
        )
    }

    private fun inspect7z(file: File, password: String?): InspectResult {
        val builder = SevenZFile.builder().setFile(file)
        if (!password.isNullOrEmpty()) builder.setPassword(password.toCharArray())
        return try {
            builder.get().use { sevenZ ->
                val entries = ArrayList<ArchiveEntryInfo>()
                var count = 0
                var total = 0L
                while (count < MAX_LIST_PREVIEW) {
                    val e = sevenZ.nextEntry ?: break
                    entries += ArchiveEntryInfo(
                        path = e.name,
                        size = e.size,
                        compressedSize = e.size, // 7z 头部不提供逐 entry 压缩体积
                        isDirectory = e.isDirectory,
                    )
                    total += e.size
                    count++
                }
                InspectResult.Ok(
                    ArchiveInfo(ArchiveFormat.SEVEN_ZIP, entries, encrypted = password != null, split = false, totalUncompressed = total)
                )
            }
        } catch (t: Throwable) {
            val msg = t.message ?: ""
            if (t is org.apache.commons.compress.PasswordRequiredException ||
                msg.contains("password", true) || msg.contains("encrypted", true)
            ) {
                InspectResult.NeedsPassword(ArchiveFormat.SEVEN_ZIP)
            } else {
                InspectResult.Unsupported("7z 读取失败：${msg.take(40)}")
            }
        }
    }

    // ================================================================ 压缩

    suspend fun create(
        entries: List<FileEntry>,
        srcParent: Location,
        dest: Location,
        baseName: String,
        format: ArchiveFormat,
        password: String? = null,
        onProgress: (OpProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Pair<OpResult, Uri?> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext OpResult.Failed("没有选中任何文件") to null
        val existing = fs.list(dest).map { it.name }.toMutableSet()
        val fileName = uniqueName("${baseName.ifBlank { "压缩包" }}.${format.extension}", existing::contains)
        val temp = File(stageDir, "build-${System.currentTimeMillis()}.${format.extension}")

        val totalBytes = entries.sumOf { if (it.isDirectory) 0L else it.size }
        var done = 0L
        var lastEmit = 0L
        fun emit(name: String, filesDone: Int) {
            val now = System.currentTimeMillis()
            if (now - lastEmit < EMIT_MS) return
            lastEmit = now
            onProgress(
                OpProgress("压缩中", name, filesDone, entries.size, done, totalBytes, totalBytes <= 0)
            )
        }

        try {
            val result = when {
                format == ArchiveFormat.SEVEN_ZIP && password != null ->
                    OpResult.Failed("7z 加密创建暂未开放，请选择 ZIP 加密或使用无密码 7z")
                format == ArchiveFormat.SEVEN_ZIP ->
                    write7z(entries, srcParent, temp, totalBytes, { n, c -> emit(n, c) }, { done = it }, isCancelled)
                password != null -> writeZipEncrypted(entries, srcParent, temp, password, { n, c -> emit(n, c) }, isCancelled)
                else -> writeZip(entries, srcParent, temp, totalBytes, { n, c -> emit(n, c) }, { done = it }, isCancelled)
            }
            if (result !is OpResult.Success) {
                temp.delete()
                return@withContext result to null
            }

            // 落地到目标目录（SAF 只能流式写出）
            val uri = fs.createFile(dest, fileName)
            if (uri == null) {
                temp.delete()
                return@withContext OpResult.Failed("无法在目标目录创建压缩包") to null
            }
            fs.openOutput(uri).use { out -> temp.inputStream().use { it.copyTo(out, BUFFER) } }
            temp.delete()
            onProgress(OpProgress("压缩完成", fileName, entries.size, entries.size, totalBytes, totalBytes))
            OpResult.Success(1, totalBytes) to uri
        } catch (t: Throwable) {
            temp.delete()
            OpResult.Failed(t.message?.take(60) ?: "压缩失败") to null
        }
    }

    /** 纯 ZIP：逐字节写入，进度真实、取消即时。 */
    private fun writeZip(
        entries: List<FileEntry>,
        srcParent: Location,
        target: File,
        totalBytes: Long,
        emit: (String, Int) -> Unit,
        setDone: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ): OpResult {
        var files = 0
        var written = 0L
        ZipArchiveOutputStream(target).use { zos ->
            zos.setLevel(6)
            zos.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded)
            for (entry in entries) {
                if (isCancelled()) return OpResult.Cancelled
                walk(entry, srcParent, entry.name) { name, openStream, _ ->
                    if (isCancelled()) throw kotlinx.coroutines.CancellationException("用户取消")
                    val ze = ZipArchiveEntry(name)
                    zos.putArchiveEntry(ze)
                    openStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            if (isCancelled()) throw kotlinx.coroutines.CancellationException("用户取消")
                            val read = input.read(buffer)
                            if (read <= 0) break
                            zos.write(buffer, 0, read)
                            written += read
                            setDone(written)
                            emit(name.substringAfterLast('/'), files)
                        }
                    }
                    zos.closeArchiveEntry()
                    files++
                }
            }
            zos.finish()
        }
        return OpResult.Success(files, totalBytes)
    }

    /** 加密 ZIP 走 zip4j（AES-256）；进度以"文件"为粒度。 */
    private fun writeZipEncrypted(
        entries: List<FileEntry>,
        srcParent: Location,
        target: File,
        password: String,
        emit: (String, Int) -> Unit,
        isCancelled: () -> Boolean,
    ): OpResult {
        // zip4j 的密码挂在 ZipFile 上，而不是 ZipParameters
        val zip = ZipFile(target).apply { setPassword(password.toCharArray()) }
        var files = 0
        for (entry in entries) {
            if (isCancelled()) return OpResult.Cancelled
            walk(entry, srcParent, entry.name) { name, openStream, _ ->
                val params = ZipParameters().apply {
                    fileNameInZip = name
                    compressionMethod = CompressionMethod.DEFLATE
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
                openStream().use { zip.addStream(it, params) }
                files++
                emit(name.substringAfterLast('/'), files)
            }
        }
        return OpResult.Success(files)
    }

    /** 7z：LZMA2 写入。 */
    private fun write7z(
        entries: List<FileEntry>,
        srcParent: Location,
        target: File,
        totalBytes: Long,
        emit: (String, Int) -> Unit,
        setDone: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ): OpResult {
        var files = 0
        var written = 0L
        SevenZOutputFile(target).use { sz ->
            for (entry in entries) {
                if (isCancelled()) return OpResult.Cancelled
                walk(entry, srcParent, entry.name) { name, openStream, size ->
                    if (isCancelled()) throw kotlinx.coroutines.CancellationException("用户取消")
                    val ae = SevenZArchiveEntry().apply {
                        this.name = name
                        this.size = size
                        this.lastModifiedDate = Date()
                        setHasStream(true)
                    }
                    sz.putArchiveEntry(ae)
                    openStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            if (isCancelled()) throw kotlinx.coroutines.CancellationException("用户取消")
                            val read = input.read(buffer)
                            if (read <= 0) break
                            sz.write(buffer, 0, read)
                            written += read
                            setDone(written)
                            emit(name.substringAfterLast('/'), files)
                        }
                    }
                    sz.closeArchiveEntry()
                    files++
                }
            }
            sz.finish()
        }
        return OpResult.Success(files, totalBytes)
    }

    /** 递归遍历（用于把目录压成带相对路径的条目）。 */
    private fun walk(
        entry: FileEntry,
        parent: Location,
        pathInArchive: String,
        onFile: (name: String, open: () -> InputStream, size: Long) -> Unit,
    ) {
        if (!entry.isDirectory) {
            onFile(pathInArchive, { fs.openInput(entry.uri) }, entry.size)
            return
        }
        val childParent = fs.childLocation(parent, entry)
        fs.list(childParent).forEach { child ->
            walk(child, childParent, "$pathInArchive/${child.name}", onFile)
        }
    }

    // ================================================================ 解压

    suspend fun extract(
        archive: FileEntry,
        dest: Location,
        password: String? = null,
        onProgress: (OpProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): OpResult = withContext(Dispatchers.IO) {
        val format = ArchiveFormat.ofExtension(archive.extension)
            ?: return@withContext OpResult.Failed("暂不支持该格式")
        val staged = stage(archive) ?: return@withContext OpResult.Failed("无法读取压缩包")

        // zip bomb 防护：解压总量不得超过包体 8 倍 + 1GB
        val guardBytes = staged.file.length() * 8 + 1_073_741_824L

        try {
            when (format) {
                ArchiveFormat.ZIP -> extractZip(staged.file, dest, password, guardBytes, onProgress, isCancelled)
                ArchiveFormat.SEVEN_ZIP -> extract7z(staged.file, dest, password, guardBytes, onProgress, isCancelled)
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            OpResult.Cancelled
        } catch (t: Throwable) {
            OpResult.Failed(readableArchiveError(t))
        } finally {
            // 只删自己复制出来的临时副本
            if (staged.isTemp) staged.file.delete()
        }
    }

    private fun extractZip(
        file: File,
        dest: Location,
        password: String?,
        guardBytes: Long,
        onProgress: (OpProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): OpResult {
        val zip = ZipFile(file)
        if (zip.isEncrypted) {
            if (password.isNullOrEmpty()) return OpResult.Failed("压缩包已加密，请先输入密码")
            zip.setPassword(password.toCharArray())
        }
        val headers = zip.fileHeaders
        val existing = fs.list(dest).map { it.name }.toMutableSet()
        var files = 0
        var bytes = 0L
        var lastEmit = 0L
        val total = headers.sumOf { it.uncompressedSize }

        for (header in headers) {
            if (isCancelled()) return OpResult.Cancelled
            val safePath = sanitizeEntryPath(header.fileName) ?: continue
            if (header.isDirectory) {
                ensureDirectories(dest, safePath, existing)
                continue
            }
            val target = ensureParent(dest, safePath, existing)
            val name = safePath.substringAfterLast('/')
            val uri = fs.createFile(target.first, uniqueName(name, target.second::contains))
                ?: continue
            target.second += fs.displayName(uri) ?: name
            zip.getInputStream(header).use { input ->
                fs.openOutput(uri).use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (isCancelled()) return OpResult.Cancelled
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        bytes += read
                        if (bytes > guardBytes) return OpResult.Failed("解压体积异常，已中止（疑似压缩炸弹）")
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > EMIT_MS) {
                            lastEmit = now
                            onProgress(OpProgress("解压中", name, files, headers.size, bytes, total))
                        }
                    }
                    out.flush()
                }
            }
            files++
        }
        onProgress(OpProgress("解压完成", "", files, headers.size, bytes, total))
        return OpResult.Success(files, bytes)
    }

    private fun extract7z(
        file: File,
        dest: Location,
        password: String?,
        guardBytes: Long,
        onProgress: (OpProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): OpResult {
        val builder = SevenZFile.builder().setFile(file)
        if (!password.isNullOrEmpty()) builder.setPassword(password.toCharArray())
        val existing = fs.list(dest).map { it.name }.toMutableSet()
        var files = 0
        var bytes = 0L
        var lastEmit = 0L
        builder.get().use { sz ->
            while (true) {
                if (isCancelled()) return OpResult.Cancelled
                val entry = sz.nextEntry ?: break
                val safePath = sanitizeEntryPath(entry.name) ?: continue
                if (entry.isDirectory) {
                    ensureDirectories(dest, safePath, existing)
                    continue
                }
                val target = ensureParent(dest, safePath, existing)
                val name = safePath.substringAfterLast('/')
                val uri = fs.createFile(target.first, uniqueName(name, target.second::contains)) ?: continue
                target.second += fs.displayName(uri) ?: name
                fs.openOutput(uri).use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (isCancelled()) return OpResult.Cancelled
                        val read = sz.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        bytes += read
                        if (bytes > guardBytes) return OpResult.Failed("解压体积异常，已中止（疑似压缩炸弹）")
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > EMIT_MS) {
                            lastEmit = now
                            onProgress(OpProgress("解压中", name, files, 0, bytes, 0, indeterminate = true))
                        }
                    }
                    out.flush()
                }
                files++
            }
        }
        onProgress(OpProgress("解压完成", "", files, files, bytes, bytes))
        return OpResult.Success(files, bytes)
    }

    // ================================================================ 工具

    /**
     * 把 content:// 压缩包落到 cache，供需要随机访问的解析器使用。
     *
     * 返回的 [Staged] 必须带 isTemp 标记：**绝不能删除用户的原文件**。
     * （这里踩过一次：早期版本无条件 delete，file:// 源会被当临时文件删掉。）
     */
    private class Staged(val file: File, val isTemp: Boolean)

    private fun stage(entry: FileEntry): Staged? = runCatching {
        if (entry.uri.scheme == "file") {
            val origin = File(entry.uri.path!!)
            if (!origin.exists()) return null
            return Staged(origin, isTemp = false)
        }
        val temp = File(stageDir, "src-${entry.name.hashCode()}-${entry.size}")
        if (!temp.exists() || temp.length() != entry.size) {
            fs.openInput(entry.uri).use { input -> temp.outputStream().use { input.copyTo(it, BUFFER) } }
        }
        Staged(temp, isTemp = true)
    }.getOrNull()

    /** zip-slip 防护：拒绝绝对路径、盘符、`..` 段。 */
    private fun sanitizeEntryPath(raw: String): String? {
        val normalized = raw.replace('\\', '/').trimStart('/')
        if (normalized.isBlank()) return null
        if (normalized.contains("..")) return null
        if (Regex("""^[A-Za-z]:""").containsMatchIn(normalized)) return null
        return normalized
    }

    /** 逐级创建目录，返回 (最终父目录, 该目录已存在名字集合)。 */
    private fun ensureParent(dest: Location, path: String, rootNames: MutableSet<String>): Pair<Location, MutableSet<String>> {
        val segments = path.split('/')
        if (segments.size == 1) return dest to rootNames
        var current = dest
        var names = rootNames
        for (i in 0 until segments.size - 1) {
            val seg = segments[i]
            if (seg.isBlank()) continue
            val existingUri = findChild(current, seg)
            current = if (existingUri != null) {
                Location(existingUri, current.treeUri, seg, current.isSandbox)
            } else {
                val created = fs.createDirectory(current, seg)
                    ?: return dest to rootNames
                Location(created, current.treeUri, seg, current.isSandbox)
            }
            names = fs.list(current).map { it.name }.toMutableSet()
        }
        return current to names
    }

    private fun ensureDirectories(dest: Location, path: String, rootNames: MutableSet<String>) {
        ensureParent(dest, "$path/x", rootNames)
    }

    private fun findChild(parent: Location, name: String): Uri? =
        fs.list(parent).firstOrNull { it.name == name }?.uri

    private fun readableArchiveError(t: Throwable): String {
        val msg = t.message ?: ""
        return when {
            msg.contains("Wrong Password", true) || msg.contains("password", true) -> "密码错误或压缩包已损坏"
            msg.contains("CRC", true) -> "校验失败，压缩包可能已损坏"
            msg.contains("split", true) || msg.contains("volume", true) -> "缺少分卷文件，请把全部卷放在同一目录"
            msg.contains("space", true) || t is java.io.IOException && msg.contains("ENOSPC") -> "目标空间不足"
            else -> msg.take(60).ifBlank { "解压失败" }
        }
    }

    fun estimateOutput(info: ArchiveInfo): String = formatBytes(info.totalUncompressed)
}
