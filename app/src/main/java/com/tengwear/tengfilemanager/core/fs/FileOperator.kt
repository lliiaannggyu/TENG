package com.tengwear.tengfilemanager.core.fs

import android.net.Uri
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.model.Location
import com.tengwear.tengfilemanager.core.model.OpProgress
import com.tengwear.tengfilemanager.core.model.OpResult
import com.tengwear.tengfilemanager.core.model.TrashItem
import com.tengwear.tengfilemanager.core.model.UndoRecord
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.core.util.uniqueName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 文件操作引擎。
 *
 * 统一约束（所有操作都必须遵守）：
 *  · 全部跑在 Dispatchers.IO，主线程只收 progress 回调；
 *  · 128KB 缓冲 + 每块检查取消，保证"取消"是即时的而不是等整个文件传完；
 *  · 目标重名默认保留两者（自动 " (1)"），覆盖必须由调用方显式声明；
 *  · 删除永远进回收站，永不直删（"清空回收站"是唯一例外，且需二次确认）；
 *  · 每个操作返回可读的失败原因，UI 直接展示，不出现 Exception 类名。
 */
class FileOperator(
    private val fs: StorageGateway,
    private val trash: TrashRepository,
) {

    private companion object {
        const val BUFFER = 128 * 1024
        const val EMIT_INTERVAL_MS = 80L
        const val MAX_ESTIMATE_DEPTH = 6
    }

    // ------------------------------------------------------------ 复制 / 移动

    suspend fun copy(
        entries: List<FileEntry>,
        srcParent: Location,
        dest: Location,
        onProgress: (OpProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): OpResult = transfer(entries, srcParent, dest, deleteSource = false, onProgress, isCancelled)

    suspend fun move(
        entries: List<FileEntry>,
        srcParent: Location,
        dest: Location,
        onProgress: (OpProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): OpResult = transfer(entries, srcParent, dest, deleteSource = true, onProgress, isCancelled)

    private suspend fun transfer(
        entries: List<FileEntry>,
        srcParent: Location,
        dest: Location,
        deleteSource: Boolean,
        onProgress: (OpProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): OpResult = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext OpResult.Success(0)
        if (!fs.isWritable(dest)) return@withContext OpResult.Failed("目标文件夹不可写入")

        // 把文件夹复制进它自己（或子目录）会造成无限递归，必须提前拦截
        entries.firstOrNull { it.isDirectory && dest.uri.toString().startsWith(it.uri.toString()) }
            ?.let { return@withContext OpResult.Failed("不能把「${it.name}」放进它自己里面") }

        val totalBytes = entries.sumOf { estimateSize(it, srcParent, 0) }
        ensureSpace(dest, totalBytes)?.let { return@withContext OpResult.Failed(it) }

        val ctx = TransferContext(
            dest = dest,
            existing = fs.list(dest).map { it.name }.toMutableSet(),
            totalBytes = totalBytes,
            filesTotal = entries.size,
            title = if (deleteSource) "移动" else "复制",
            onProgress = onProgress,
            isCancelled = isCancelled,
        )

        val failures = ArrayList<OpResult.Failure>()
        var succeeded = 0

        for (entry in entries) {
            if (isCancelled()) break
            // 沙盒内部移动走 rename 快路径，避免几十 MB 的无谓读写
            val renamed = if (deleteSource && entry.uri.scheme == "file" && dest.isSandbox) {
                tryRenameInto(entry, dest, ctx)
            } else null

            if (renamed != null) {
                succeeded++
                ctx.bytesDone += if (entry.isDirectory) 0L else entry.size
                ctx.emit(force = true)
                continue
            }

            val ok = try {
                copyNode(entry, srcParent, ctx, entry.name)
                if (deleteSource) {
                    if (!fs.delete(entry.uri)) throw IllegalStateException("源文件删除失败（可能无写权限）")
                }
                true
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                failures += OpResult.Failure(entry.name, readable(t))
                false
            }
            if (ok) succeeded++
            ctx.filesDone++
            ctx.emit(force = true)
        }

        val cancelled = isCancelled() || !coroutineContext.isActive
        when {
            failures.isEmpty() && cancelled && succeeded < entries.size -> OpResult.Cancelled
            failures.isEmpty() -> OpResult.Success(succeeded, ctx.bytesDone, ctx.created.toList())
            succeeded == 0 -> OpResult.Failed(failures.first().reason)
            else -> OpResult.Partial(succeeded, failures, cancelled)
        }
    }

    private fun tryRenameInto(entry: FileEntry, dest: Location, ctx: TransferContext): Uri? {
        val src = File(entry.uri.path ?: return null)
        val name = uniqueName(entry.name, ctx.existing::contains)
        val target = File(dest.uri.path ?: return null, name)
        if (!src.renameTo(target)) return null
        ctx.existing += name
        ctx.created += Uri.fromFile(target)
        return Uri.fromFile(target)
    }

    private fun copyNode(
        entry: FileEntry,
        srcParent: Location,
        ctx: TransferContext,
        desiredName: String,
    ): Uri {
        val name = uniqueName(desiredName, ctx.existing::contains)
        ctx.existing += name

        if (entry.isDirectory) {
            val dirUri = fs.createDirectory(ctx.dest, name)
                ?: throw IllegalStateException("无法创建文件夹「$name」")
            ctx.created += dirUri
            val childDest = Location(dirUri, ctx.dest.treeUri, name, ctx.dest.isSandbox)
            val childSrc = fs.childLocation(srcParent, entry)
            fs.list(childSrc).forEach { child ->
                if (ctx.isCancelled()) throw kotlinx.coroutines.CancellationException("用户取消")
                copyNode(child, childSrc, ctx, child.name)
            }
            return dirUri
        }

        val outUri = fs.createFile(ctx.dest, name)
            ?: throw IllegalStateException("无法创建文件「$name」")
        ctx.created += outUri
        ctx.currentName = name
        fs.openInput(entry.uri).use { input ->
            fs.openOutput(outUri).use { output ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    if (ctx.isCancelled()) throw kotlinx.coroutines.CancellationException("用户取消")
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    ctx.bytesDone += read
                    ctx.emit()
                }
                output.flush()
            }
        }
        return outUri
    }

    private fun ensureSpace(dest: Location, needed: Long): String? {
        if (!dest.isSandbox && dest.uri.scheme != "file") return null
        val dir = File(dest.uri.path ?: return null)
        val free = dir.usableSpace
        return if (free in 1 until needed) {
            "空间不足：需要 ${formatBytes(needed)}，剩余 ${formatBytes(free)}"
        } else null
    }

    /** 递归估算体积。深度上限 6 层，避免用户在主目录上误触发全盘遍历。 */
    private fun estimateSize(entry: FileEntry, parent: Location, depth: Int): Long {
        if (!entry.isDirectory) return entry.size
        if (depth >= MAX_ESTIMATE_DEPTH) return 0L
        val childLocation = fs.childLocation(parent, entry)
        return fs.list(childLocation).sumOf { estimateSize(it, childLocation, depth + 1) }
    }

    // ------------------------------------------------------------ 重命名

    suspend fun rename(entry: FileEntry, newName: String): OpResult = withContext(Dispatchers.IO) {
        val cleaned = com.tengwear.tengfilemanager.core.util.sanitizeFileName(newName)
        if (cleaned == entry.name) return@withContext OpResult.Success(0)
        val result = fs.rename(entry.uri, cleaned)
        if (result != null) OpResult.Success(1) else OpResult.Failed("重命名失败，可能没有写权限或名称已被占用")
    }

    // ------------------------------------------------------------ 删除（进回收站）

    suspend fun delete(
        entries: List<FileEntry>,
        parent: Location,
        onProgress: (OpProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Pair<OpResult, List<TrashItem>> {
        val (items, failures) = trash.moveToTrash(parent, entries, onProgress, isCancelled)
        val result: OpResult = when {
            items.isEmpty() && failures.isEmpty() -> OpResult.Cancelled
            failures.isEmpty() -> OpResult.Success(items.size)
            items.isEmpty() -> OpResult.Failed(failures.first().reason)
            else -> OpResult.Partial(items.size, failures)
        }
        return result to items
    }

    // ------------------------------------------------------------ 新建

    suspend fun createFolder(dest: Location, name: String): OpResult = withContext(Dispatchers.IO) {
        val cleaned = com.tengwear.tengfilemanager.core.util.sanitizeFileName(name)
        val existing = fs.list(dest).map { it.name }.toSet()
        if (cleaned in existing) return@withContext OpResult.Failed("同名文件夹已存在")
        val uri = fs.createDirectory(dest, cleaned)
        if (uri != null) OpResult.Success(1) else OpResult.Failed("创建文件夹失败")
    }

    suspend fun createFile(dest: Location, name: String): OpResult = withContext(Dispatchers.IO) {
        val cleaned = com.tengwear.tengfilemanager.core.util.sanitizeFileName(name)
        val existing = fs.list(dest).map { it.name }.toSet()
        if (cleaned in existing) return@withContext OpResult.Failed("同名文件已存在")
        val uri = fs.createFile(dest, cleaned)
        if (uri != null) OpResult.Success(1) else OpResult.Failed("创建文件失败")
    }

    // ------------------------------------------------------------ 撤销

    /** 撤销最近一次操作。返回是否完全成功。 */
    suspend fun undo(record: UndoRecord): Boolean = withContext(Dispatchers.IO) {
        when (record) {
            is UndoRecord.DeleteCreated -> record.uris.all { fs.delete(it) }
            is UndoRecord.RestoreFromTrash -> record.items.all { trash.restore(it) != null }
            is UndoRecord.MoveBack -> record.moved.all { (newUri, oldParent) ->
                runCatching {
                    val name = fs.displayName(newUri) ?: "恢复"
                    val parentLocation = Location(oldParent, null, "恢复", oldParent.scheme == "file")
                    val srcLocation = Location(newUri, null, name, newUri.scheme == "file")
                    val entry = FileEntry.of(newUri, name, fs.sizeOf(newUri) == 0L && isDir(newUri), fs.sizeOf(newUri), 0L, null)
                    val ctx = TransferContext(
                        dest = parentLocation,
                        existing = fs.list(parentLocation).map { it.name }.toMutableSet(),
                        totalBytes = entry.size,
                        filesTotal = 1,
                        title = "撤销",
                        onProgress = {},
                        isCancelled = { false },
                    )
                    copyNode(entry, srcLocation, ctx, name)
                    fs.delete(newUri)
                }.isSuccess
            }
        }
    }

    private fun isDir(uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") File(uri.path!!).isDirectory else false
    }.getOrDefault(false)

    private fun readable(t: Throwable): String = when (t) {
        is SecurityException -> "没有写权限"
        is java.io.IOException -> "读写中断（${t.message ?: "存储忙"}）"
        else -> t.message?.take(50) ?: "操作失败"
    }

    // ------------------------------------------------------------ 进度节流

    private class TransferContext(
        val dest: Location,
        val existing: MutableSet<String>,
        val totalBytes: Long,
        val filesTotal: Int,
        val title: String,
        val onProgress: (OpProgress) -> Unit,
        val isCancelled: () -> Boolean,
    ) {
        val created = mutableListOf<Uri>()
        var bytesDone = 0L
        var filesDone = 0
        var currentName = ""
        private var lastEmit = 0L

        /** 80ms 节流：UI 每秒最多刷新 12 次，避免进度回调本身成为卡顿源。 */
        fun emit(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmit < EMIT_INTERVAL_MS) return
            lastEmit = now
            onProgress(
                OpProgress(
                    title = title,
                    currentName = currentName,
                    filesDone = filesDone,
                    filesTotal = filesTotal,
                    bytesDone = bytesDone,
                    bytesTotal = totalBytes,
                    indeterminate = totalBytes <= 0L,
                )
            )
        }
    }
}
