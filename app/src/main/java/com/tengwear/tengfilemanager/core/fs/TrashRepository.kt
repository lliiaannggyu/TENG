package com.tengwear.tengfilemanager.core.fs

import android.content.Context
import android.net.Uri
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.model.Location
import com.tengwear.tengfilemanager.core.model.OpProgress
import com.tengwear.tengfilemanager.core.model.OpResult
import com.tengwear.tengfilemanager.core.model.TrashItem
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.core.util.uniqueName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 回收站。删除 = 移动到 `files/trash/<uuid>/payload`，并写一份 meta.json。
 *
 * 为什么不直接删：手表误触率远高于手机（屏幕小、手指粗、表冠容易滑），
 * 一次不可逆删除的代价远大于几百 KB 的临时空间。
 *
 * 保留策略：30 天后自动清理；用户可手动"清空回收站"。
 */
class TrashRepository(
    private val context: Context,
    private val fs: StorageGateway,
) {
    private val trashRoot: File get() = File(context.filesDir, "trash").apply { mkdirs() }

    private fun metaFile(id: String) = File(File(trashRoot, id), "meta.json")
    private fun payloadFile(id: String) = File(File(trashRoot, id), "payload")

    suspend fun moveToTrash(
        parent: Location,
        entries: List<FileEntry>,
        onProgress: (OpProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Pair<List<TrashItem>, List<OpResult.Failure>> = withContext(Dispatchers.IO) {
        val moved = ArrayList<TrashItem>(entries.size)
        val failures = ArrayList<OpResult.Failure>()
        val totalBytes = entries.sumOf { if (it.isDirectory) 0L else it.size }
        var done = 0L

        entries.forEachIndexed { index, entry ->
            if (isCancelled()) return@withContext moved to failures
            onProgress(
                OpProgress(
                    title = "移入回收站",
                    currentName = entry.name,
                    filesDone = index,
                    filesTotal = entries.size,
                    bytesDone = done,
                    bytesTotal = totalBytes,
                )
            )
            val id = UUID.randomUUID().toString()
            val payload = payloadFile(id)
            payload.parentFile?.mkdirs()
            try {
                if (entry.uri.scheme == "file") {
                    // 同卷直接 rename，O(1) 且不产生额外空间压力
                    val src = File(entry.uri.path!!)
                    if (!src.renameTo(payload)) {
                        src.copyRecursively(payload, overwrite = true)
                        if (!src.deleteRecursively()) error("源文件删除失败")
                    }
                } else {
                    copyNode(entry, parent, payload)
                    if (!fs.delete(entry.uri)) error("源文件删除失败（可能无写权限）")
                }
                val item = TrashItem(
                    id = id,
                    name = entry.name,
                    originalUri = entry.uri,
                    originalParentUri = parent.uri,
                    payloadUri = Uri.fromFile(payload),
                    size = if (entry.isDirectory) payload.walkTopDown().filter { it.isFile }.sumOf { it.length() } else entry.size,
                    isDirectory = entry.isDirectory,
                    deletedAt = System.currentTimeMillis(),
                )
                writeMeta(item)
                moved += item
                done += if (entry.isDirectory) 0L else entry.size
            } catch (t: Throwable) {
                payload.deleteRecursively()
                failures += OpResult.Failure(entry.name, t.message ?: "移入回收站失败")
            }
        }
        onProgress(OpProgress(title = "移入回收站", filesDone = entries.size, filesTotal = entries.size))
        moved to failures
    }

    /** 恢复到原目录；原目录已不存在时退回沙盒根目录，并在 UI 上提示。 */
    suspend fun restore(item: TrashItem): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = File(item.payloadUri.path!!)
            if (!payload.exists()) return@runCatching null
            val parentUri = item.originalParentUri
            val parentLocation = if (parentUri.scheme == "file") {
                Location(Uri.fromFile(File(parentUri.path!!).parentFile ?: context.filesDir), null, "恢复", true)
            } else {
                Location(parentUri, null, "恢复")
            }
            // 目标目录仍可写就放回原处，否则落到沙盒
            val target = if (fs.exists(parentUri)) parentLocation else
                Location(Uri.fromFile(context.filesDir), null, "应用内部存储", true)

            val existing = fs.list(target).map { it.name }.toMutableSet()
            val name = uniqueName(item.name, existing::contains)
            val created = if (payload.isDirectory) {
                fs.createDirectory(target, name)?.let { dirUri ->
                    val dirLoc = Location(dirUri, target.treeUri, name, target.isSandbox)
                    payload.listFiles()?.forEach { child ->
                        fs.createFile(dirLoc, child.name)?.let { childUri ->
                            fs.openOutput(childUri).use { out -> child.inputStream().use { it.copyTo(out) } }
                        }
                    }
                    payload.deleteRecursively()
                    dirUri
                }
            } else {
                fs.createFile(target, name)?.let { uri ->
                    fs.openOutput(uri).use { out -> payload.inputStream().use { it.copyTo(out) } }
                    payload.deleteRecursively()
                    uri
                }
            }
            if (created != null) metaFile(item.id).delete()
            created
        }.getOrNull()
    }

    fun list(): List<TrashItem> {
        val dirs = trashRoot.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching {
                val json = JSONObject(File(dir, "meta.json").readText())
                TrashItem(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    originalUri = Uri.parse(json.getString("originalUri")),
                    originalParentUri = Uri.parse(json.getString("originalParentUri")),
                    payloadUri = Uri.fromFile(File(dir, "payload")),
                    size = json.optLong("size"),
                    isDirectory = json.optBoolean("isDirectory"),
                    deletedAt = json.optLong("deletedAt"),
                )
            }.getOrNull()
        }.sortedByDescending { it.deletedAt }
    }

    suspend fun purge(item: TrashItem) = withContext(Dispatchers.IO) {
        File(trashRoot, item.id).deleteRecursively()
        Unit
    }

    suspend fun emptyAll() = withContext(Dispatchers.IO) {
        trashRoot.listFiles()?.forEach { it.deleteRecursively() }
        Unit
    }

    /** 30 天自动清理，在应用启动时调用一次即可（不做定时任务，省电）。 */
    suspend fun purgeExpired(maxAgeDays: Long = 30) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        list().filter { now - it.deletedAt > maxAgeDays * 86_400_000L }
            .forEach { File(trashRoot, it.id).deleteRecursively() }
        Unit
    }

    fun totalBytes(): Long = list().sumOf { it.size }

    fun summary(): String {
        val items = list()
        return if (items.isEmpty()) "空" else "${items.size} 项 · ${formatBytes(items.sumOf { it.size })}"
    }

    private fun writeMeta(item: TrashItem) {
        val json = JSONObject().apply {
            put("id", item.id)
            put("name", item.name)
            put("originalUri", item.originalUri.toString())
            put("originalParentUri", item.originalParentUri.toString())
            put("size", item.size)
            put("isDirectory", item.isDirectory)
            put("deletedAt", item.deletedAt)
        }
        metaFile(item.id).writeText(json.toString())
    }

    /** 递归把 SAF 内容复制到私有目录（跨 provider 无法 rename，只能流式拷贝）。 */
    private fun copyNode(entry: FileEntry, parent: Location, dest: File) {
        if (!entry.isDirectory) {
            dest.parentFile?.mkdirs()
            fs.openInput(entry.uri).use { input -> dest.outputStream().use { input.copyTo(it, 128 * 1024) } }
            return
        }
        dest.mkdirs()
        val childLocation = fs.childLocation(parent, entry)
        fs.list(childLocation).forEach { child -> copyNode(child, childLocation, File(dest, child.name)) }
    }
}
