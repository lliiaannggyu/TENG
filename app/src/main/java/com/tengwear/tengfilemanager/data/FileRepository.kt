package com.tengwear.tengfilemanager.data

import android.util.LruCache
import com.tengwear.tengfilemanager.core.fs.StorageGateway
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.model.KindFilter
import com.tengwear.tengfilemanager.core.model.Location
import com.tengwear.tengfilemanager.core.model.SortSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 目录数据仓库：负责「读盘 + 排序 + 过滤 + 缓存」，ViewModel 只关心状态。
 *
 * 缓存策略：
 *  · 只缓存最近 24 个目录的**原始列表**（不含排序结果），排序/过滤在内存里做，
 *    这样切换排序方式不会触发二次读盘；
 *  · 写入型操作（复制/删除/重命名/解压）统一调用 [invalidate]，
 *    不做基于 mtime 的智能失效——SAF 的 lastModified 粒度只有秒，靠它判断会漏。
 */
class FileRepository(
    private val fs: StorageGateway,
) {

    private data class Cached(val entries: List<FileEntry>, val at: Long)

    private val cache = object : LruCache<String, Cached>(24) {
        override fun sizeOf(key: String, value: Cached): Int = 1
    }

    suspend fun list(
        location: Location,
        sort: SortSpec,
        filter: KindFilter,
        forceRefresh: Boolean = false,
    ): List<FileEntry> = withContext(Dispatchers.IO) {
        val raw = if (!forceRefresh) {
            cache.get(location.id)?.entries
        } else null
        val entries = raw ?: fs.list(location).also { cache.put(location.id, Cached(it, System.currentTimeMillis())) }
        applySortAndFilter(entries, sort, filter)
    }

    fun applySortAndFilter(
        entries: List<FileEntry>,
        sort: SortSpec,
        filter: KindFilter,
    ): List<FileEntry> = entries
        .asSequence()
        .filter { filter.matches(it) }
        .sortedWith(sort.comparator())
        .toList()

    /** 任一变更加上它的上级目录都要失效，避免"删完返回上一级还在"。 */
    fun invalidate(vararg uris: String?) {
        uris.filterNotNull().forEach { cache.remove(it) }
    }

    fun invalidateAll() = cache.evictAll()

    /**
     * 递归搜索。约束：
     *  · 深度上限默认 6 层、结果上限 200 条——手表上没人会翻第 201 个结果；
     *  · 每处理一个目录检查一次取消，长搜索可随时打断；
     *  · 跳过隐藏目录（.git、.thumbnails 这类会让搜索卡死的元数据目录）。
     */
    suspend fun search(
        roots: List<Location>,
        query: String,
        maxDepth: Int = 6,
        limit: Int = 200,
        onProgress: (scanned: Int, found: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): List<FileEntry> = withContext(Dispatchers.IO) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return@withContext emptyList()
        val results = ArrayList<FileEntry>()
        var scanned = 0
        val queue = ArrayDeque<Pair<Location, Int>>()
        roots.forEach { queue.addLast(it to 0) }

        while (queue.isNotEmpty() && results.size < limit) {
            coroutineContext.ensureActive()
            if (isCancelled()) break
            val (location, depth) = queue.removeFirst()
            if (depth > maxDepth) continue
            val children = fs.list(location)
            scanned++
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.name.contains(keyword, ignoreCase = true)) {
                    results += child
                    if (results.size >= limit) break
                }
                if (child.isDirectory) queue.addLast(fs.childLocation(location, child) to depth + 1)
            }
            if (scanned % 4 == 0) onProgress(scanned, results.size)
        }
        onProgress(scanned, results.size)
        results.sortedWith(SortSpec().comparator())
    }

    fun roots(): List<Location> = fs.allRoots()

    fun volumeUsage(): Pair<Long, Long> = fs.volumeUsage()
}
