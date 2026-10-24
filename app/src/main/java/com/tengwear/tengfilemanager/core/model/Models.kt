package com.tengwear.tengfilemanager.core.model

import android.net.Uri

/**
 * 文件类型。手表端只区分「用户能一眼认出」的 8 类，
 * 而不是手机端那种几十种 MIME 分组——圆形小屏上筛选入口超过 7 个就必须二次翻页。
 */
enum class FileKind {
    FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, OTHER;

    val label: String
        get() = when (this) {
            FOLDER -> "文件夹"
            IMAGE -> "图片"
            VIDEO -> "视频"
            AUDIO -> "音频"
            DOCUMENT -> "文档"
            ARCHIVE -> "压缩包"
            APK -> "安装包"
            OTHER -> "其他"
        }

    /** 类型徽标上的单字，圆屏 12sp 下仍可辨认，比图标更省像素也更省内存。 */
    val badge: String
        get() = when (this) {
            FOLDER -> "夹"
            IMAGE -> "图"
            VIDEO -> "视"
            AUDIO -> "音"
            DOCUMENT -> "文"
            ARCHIVE -> "包"
            APK -> "装"
            OTHER -> "件"
        }

    companion object {
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "dng", "tif", "tiff")
        private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v", "ts")
        private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr", "mid")
        private val DOC_EXT = setOf("txt", "md", "markdown", "log", "json", "xml", "csv", "yml", "yaml", "ini", "conf", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub", "rtf")
        private val CODE_EXT = setOf("kt", "java", "c", "h", "cpp", "hpp", "py", "js", "ts", "tsx", "jsx", "go", "rs", "swift", "sh", "gradle", "kts", "sql", "html", "css")
        private val ARCHIVE_EXT = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "jar", "apks", "xapk")
        private val APK_EXT = setOf("apk", "apkm", "apks", "xapk")

        fun of(name: String, mime: String?, isDirectory: Boolean): FileKind {
            if (isDirectory) return FOLDER
            val ext = name.substringAfterLast('.', "").lowercase()
            // 安装包优先于压缩包：.apks / .xapk 本质是 zip，但用户心智里是"安装包"
            if (ext in APK_EXT) return APK
            if (ext in ARCHIVE_EXT) return ARCHIVE
            if (ext in IMAGE_EXT) return IMAGE
            if (ext in VIDEO_EXT) return VIDEO
            if (ext in AUDIO_EXT) return AUDIO
            if (ext in DOC_EXT || ext in CODE_EXT) return DOCUMENT
            return when {
                mime == null -> OTHER
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                mime.startsWith("text/") -> DOCUMENT
                mime == "application/pdf" -> DOCUMENT
                else -> OTHER
            }
        }
    }
}

/**
 * 列表项。故意不持有 DocumentFile / File：
 * 那两种对象都会缓存 provider 状态，长时间列表持有会显著抬高内存并让 DiffUtil 比较变慢。
 */
data class FileEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
    val kind: FileKind,
) {
    val key: String get() = uri.toString()

    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    val isHidden: Boolean get() = name.startsWith(".")

    companion object {
        fun of(
            uri: Uri,
            name: String,
            isDirectory: Boolean,
            size: Long,
            lastModified: Long,
            mimeType: String?,
        ) = FileEntry(uri, name, isDirectory, size, lastModified, mimeType, FileKind.of(name, mimeType, isDirectory))
    }
}

/** 排序维度。递增/递减对三个维度都有效，且"文件夹永远在前"是硬规则。 */
enum class SortKey(val label: String) {
    NAME("名称"), DATE("日期"), SIZE("大小")
}

data class SortSpec(val key: SortKey = SortKey.NAME, val descending: Boolean = false) {
    fun toggleDirection() = copy(descending = !descending)

    /** 返回一个可复用的比较器；folders-first 在比较器内保证，调用方不需要二次排序。 */
    fun comparator(): Comparator<FileEntry> {
        val base: Comparator<FileEntry> = when (key) {
            SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortKey.DATE -> compareBy { it.lastModified }
            SortKey.SIZE -> compareBy { it.size }
        }
        val directed = if (descending) base.reversed() else base
        return Comparator { a, b ->
            when {
                a.isDirectory && !b.isDirectory -> -1
                !a.isDirectory && b.isDirectory -> 1
                else -> directed.compare(a, b)
            }
        }
    }
}

/** 类型筛选。FOLDER 不参与筛选，文件夹始终展示。 */
enum class KindFilter(val label: String) {
    ALL("全部"), IMAGE("图片"), VIDEO("视频"), AUDIO("音频"),
    DOCUMENT("文档"), ARCHIVE("压缩包"), APK("安装包");

    fun matches(entry: FileEntry): Boolean =
        this == ALL || entry.isDirectory || entry.kind.name == name
}

/**
 * 多选状态机。
 *
 * 三种进入方式（长按 / 区间滑动 / 全选）共用同一个 anchor，
 * 因为"右滑跨过另一个文件"必须知道从哪一项开始算区间。
 */
data class SelectionState(
    val active: Boolean = false,
    val keys: Set<String> = emptySet(),
    val anchorIndex: Int = -1,
) {
    val count: Int get() = keys.size

    fun toggle(key: String, index: Int): SelectionState {
        val next = if (key in keys) keys - key else keys + key
        return copy(active = true, keys = next, anchorIndex = if (anchorIndex < 0) index else anchorIndex)
    }

    fun start(entry: FileEntry, index: Int) = SelectionState(active = true, keys = setOf(entry.key), anchorIndex = index)

    /** 区间选择：anchor → index 闭区间全选（不取消已选项，符合"拖动累加"的直觉）。 */
    fun range(ordered: List<FileEntry>, index: Int): SelectionState {
        if (anchorIndex < 0 || index < 0) return this
        val from = minOf(anchorIndex, index).coerceIn(0, ordered.lastIndex.coerceAtLeast(0))
        val to = maxOf(anchorIndex, index).coerceIn(0, ordered.lastIndex.coerceAtLeast(0))
        if (from > to) return this
        val added = ordered.subList(from, to + 1).map { it.key }
        return copy(active = true, keys = keys + added)
    }

    fun clear() = SelectionState()
}

/** 剪贴板：只在进程内保存 Uri 引用，不复制文件内容，粘贴时才真正落盘。 */
enum class ClipMode { COPY, CUT }

data class ClipboardState(
    val mode: ClipMode,
    val entries: List<FileEntry>,
    /**
     * 记录完整 Location 而不是裸 Uri：
     * 复制目录时必须能重新列出源目录的子项，而 SAF 子文档的遍历依赖 treeUri。
     */
    val sourceLocation: Location,
) {
    val count: Int get() = entries.size
    val totalBytes: Long get() = entries.sumOf { if (it.isDirectory) 0L else it.size }
}

/** 压缩格式。ZIP 走 zip4j（支持 AES / 分卷 / 流式），7z 走 commons-compress。 */
enum class ArchiveFormat(val extension: String, val label: String) {
    ZIP("zip", "ZIP"), SEVEN_ZIP("7z", "7z");

    companion object {
        fun ofExtension(ext: String): ArchiveFormat? = when (ext.lowercase()) {
            "zip", "apks", "xapk", "jar" -> ZIP
            "7z" -> SEVEN_ZIP
            else -> null
        }
    }
}

/** 回收站条目：payload 落在应用私有目录，restore 时按原 parent 放回。 */
data class TrashItem(
    val id: String,
    val name: String,
    val originalUri: Uri,
    val originalParentUri: Uri,
    val payloadUri: Uri,
    val size: Long,
    val isDirectory: Boolean,
    val deletedAt: Long,
)

/** 确认弹层。所有破坏性/长耗时操作都必须先过这一层。 */
enum class ConfirmKind { DELETE, COMPRESS, EXTRACT, INSTALL, OVERWRITE, UNINSTALL }

data class ConfirmSpec(
    val kind: ConfirmKind,
    val title: String,
    val message: String,
    val confirmLabel: String = "确定",
    val danger: Boolean = false,
    val uris: List<Uri> = emptyList(),
    val archiveFormat: ArchiveFormat? = null,
    val payload: String? = null,
)

/** 长任务进度。UI 只读这一个对象，不需要订阅多个 Flow。 */
data class OpProgress(
    val title: String,
    val currentName: String = "",
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val indeterminate: Boolean = false,
) {
    val fraction: Float
        get() = when {
            bytesTotal > 0L -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            filesTotal > 0 -> (filesDone.toFloat() / filesTotal).coerceIn(0f, 1f)
            else -> 0f
        }
}

/** 一次文件操作的结局：成功 / 部分失败 / 被取消。失败原因必须可读，不能只给异常栈。 */
sealed interface OpResult {
    /** created 用于"撤销复制"：记录本次新建的 Uri，撤销时直接删除。 */
    data class Success(
        val affected: Int,
        val bytes: Long = 0L,
        val created: List<Uri> = emptyList(),
    ) : OpResult

    data class Partial(val succeeded: Int, val failed: List<Failure>, val cancelled: Boolean = false) : OpResult
    data class Failed(val reason: String) : OpResult
    data object Cancelled : OpResult

    data class Failure(val name: String, val reason: String)
}

/** 撤销记录：只保留最近一次操作，手表上做多级撤销收益极低但内存代价高。 */
sealed interface UndoRecord {
    val label: String

    data class RestoreFromTrash(override val label: String, val items: List<TrashItem>) : UndoRecord
    data class DeleteCreated(override val label: String, val uris: List<Uri>) : UndoRecord
    data class MoveBack(override val label: String, val moved: List<Pair<Uri, Uri>>) : UndoRecord
}

/** 位置（一个可浏览的目录）。SAF 的 treeUri 必须一路带着，否则无法构造子文档 Uri。 */
data class Location(
    val uri: Uri,
    val treeUri: Uri?,
    val name: String,
    val isSandbox: Boolean = false,
) {
    val id: String get() = uri.toString()
}

/** 面包屑的一节。path 是"到达这里"的完整位置链，点按即回退到该层。 */
data class Crumb(val label: String, val location: Location)
