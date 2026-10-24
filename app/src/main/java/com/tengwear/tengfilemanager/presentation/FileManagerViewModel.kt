package com.tengwear.tengfilemanager.presentation

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tengwear.tengfilemanager.core.apk.ApkInstallReceiver
import com.tengwear.tengfilemanager.core.apk.ApkTool
import com.tengwear.tengfilemanager.core.archive.ArchiveEngine
import com.tengwear.tengfilemanager.core.fs.FileOperator
import com.tengwear.tengfilemanager.core.fs.StorageGateway
import com.tengwear.tengfilemanager.core.fs.TrashRepository
import com.tengwear.tengfilemanager.core.lan.LanDiscovery
import com.tengwear.tengfilemanager.core.lan.LanTransfer
import com.tengwear.tengfilemanager.core.model.ArchiveFormat
import com.tengwear.tengfilemanager.core.model.ClipMode
import com.tengwear.tengfilemanager.core.model.ClipboardState
import com.tengwear.tengfilemanager.core.model.ConfirmKind
import com.tengwear.tengfilemanager.core.model.ConfirmSpec
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.model.FileKind
import com.tengwear.tengfilemanager.core.model.KindFilter
import com.tengwear.tengfilemanager.core.model.Location
import com.tengwear.tengfilemanager.core.model.OpProgress
import com.tengwear.tengfilemanager.core.model.OpResult
import com.tengwear.tengfilemanager.core.model.SelectionState
import com.tengwear.tengfilemanager.core.model.SortKey
import com.tengwear.tengfilemanager.core.model.SortSpec
import com.tengwear.tengfilemanager.core.model.TrashItem
import com.tengwear.tengfilemanager.core.model.UndoRecord
import com.tengwear.tengfilemanager.core.prefs.UserPreferences
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.core.util.uniqueName
import com.tengwear.tengfilemanager.data.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 页面栈元素。用 sealed class 而不是字符串路由：类型安全，且能携带 FileEntry 这类上下文。 */
sealed interface Screen {
    data object Home : Screen
    data class Browse(val location: Location) : Screen
    data object Search : Screen
    data object Trash : Screen
    data class Collection(val type: CollectionType) : Screen
    data object Devices : Screen
    data object Settings : Screen
    data object QuickMenu : Screen
    data object PathInput : Screen
    data object TransferProgress : Screen
    data class Viewer(val entry: FileEntry, val siblings: List<FileEntry>) : Screen
    data class ArchiveBrowse(val entry: FileEntry) : Screen
    data class ApkDetails(val entry: FileEntry) : Screen
}

enum class CollectionType(val title: String) { RECENTS("最近"), FAVORITES("收藏") }

/**
 * 唯一的状态持有者。
 *
 * 分工原则：
 *  · ViewModel 只做"状态 + 编排"，不碰 UI，也不直接读写字节；
 *  · 所有耗时动作都进 viewModelScope + IO，UI 只订阅 progress；
 *  · 任何破坏性动作先产出 [ConfirmSpec]，由 UI 弹出确认后再 [confirmAction]。
 */
class FileManagerViewModel(app: Application) : AndroidViewModel(app) {

    // ---------------------------------------------------------------- 依赖

    private val fs = StorageGateway(app)
    private val trashRepo = TrashRepository(app, fs)
    private val operator = FileOperator(fs, trashRepo)
    private val archiveEngine = ArchiveEngine(app, fs)
    private val apkTool = ApkTool(app, fs)
    private val repo = FileRepository(fs)
    private val prefs = UserPreferences(app)
    private val discovery = LanDiscovery(app)
    private val lan = LanTransfer(app, fs, "", "")

    // ---------------------------------------------------------------- 导航

    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    val current: Screen get() = _stack.value.last()

    fun navigate(screen: Screen) {
        _stack.update { it + screen }
    }

    fun back(): Boolean {
        val browse = _browse.value
        if (browse.selection.active) {
            _browse.update { it.copy(selection = SelectionState()) }
            return true
        }
        if (_confirm.value != null) {
            _confirm.value = null
            return true
        }
        val stack = _stack.value
        if (stack.size <= 1) return false
        val popped = stack.dropLast(1)
        _stack.value = popped
        // 离开设备页即停止广播/监听，避免待机时仍在收组播包
        if (stack.last() is Screen.Devices && popped.lastOrNull() !is Screen.Devices) {
            discovery.stopAll()
            lan.stopServer()
        }
        // 回到某个目录页时重新加载（走缓存，通常 < 20ms）
        (popped.lastOrNull() as? Screen.Browse)?.let { loadLocation(it.location, pushStack = false) }
        return true
    }

    // ---------------------------------------------------------------- 浏览状态

    data class BrowseUiState(
        val location: Location? = null,
        val ancestry: List<Location> = emptyList(),
        val entries: List<FileEntry> = emptyList(),
        val rawCount: Int = 0,
        val loading: Boolean = false,
        val error: String? = null,
        val sort: SortSpec = SortSpec(),
        val filter: KindFilter = KindFilter.ALL,
        val selection: SelectionState = SelectionState(),
        val canPaste: Boolean = false,
        val writable: Boolean = true,
        val swiping: Boolean = false,
        /** 首屏只渲染 60 项，滚到底再追加；避免上万项目录一次性进入组合树。 */
        val visibleCount: Int = PAGE_SIZE,
    ) {
        val visibleEntries: List<FileEntry> get() = entries.take(visibleCount)
        val hasMore: Boolean get() = visibleCount < entries.size

        companion object {
            const val PAGE_SIZE = 60
        }
    }

    private val _browse = MutableStateFlow(BrowseUiState())
    val browse: StateFlow<BrowseUiState> = _browse.asStateFlow()

    /** 偏好快照：UI 需要读收藏/最近/设备名，统一从这里取，避免多处 collect。 */
    private val _prefsState = MutableStateFlow(UserPreferences.Prefs())
    val prefsState: StateFlow<UserPreferences.Prefs> = _prefsState.asStateFlow()

    private val _roots = MutableStateFlow<List<Location>>(emptyList())
    val roots: StateFlow<List<Location>> = _roots.asStateFlow()

    private val _volume = MutableStateFlow(0L to 0L)
    val volume: StateFlow<Pair<Long, Long>> = _volume.asStateFlow()

    private val _clipboard = MutableStateFlow<ClipboardState?>(null)
    val clipboard: StateFlow<ClipboardState?> = _clipboard.asStateFlow()

    // ---------------------------------------------------------------- 通用提示 / 进度 / 确认

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _progress = MutableStateFlow<OpProgress?>(null)
    val progress: StateFlow<OpProgress?> = _progress.asStateFlow()

    private val _confirm = MutableStateFlow<ConfirmSpec?>(null)
    val confirm: StateFlow<ConfirmSpec?> = _confirm.asStateFlow()

    private val _undo = MutableStateFlow<UndoRecord?>(null)
    val undo: StateFlow<UndoRecord?> = _undo.asStateFlow()

    private var opJob: Job? = null
    private var cancelledFlag = false

    fun consumeMessage() {
        _message.value = null
    }

    fun dismissConfirm() {
        _confirm.value = null
    }

    fun dismissUndo() {
        _undo.value = null
    }

    fun cancelOperation() {
        cancelledFlag = true
        opJob?.cancel()
    }

    // ---------------------------------------------------------------- 初始化

    init {
        viewModelScope.launch {
            val id = prefs.ensureDeviceId()
            val name = prefs.flow.first().deviceName
            lan.setIdentity(id, name)
            withContext(Dispatchers.IO) {
                _roots.value = repo.roots()
                _volume.value = repo.volumeUsage()
            }
            prefs.flow.collect { p ->
                _prefsState.value = p
                if (_roots.value.isEmpty()) _roots.value = repo.roots()
            }
        }
    }

    fun refreshRoots() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _roots.value = repo.roots()
                _volume.value = repo.volumeUsage()
            }
        }
    }

    // ---------------------------------------------------------------- 目录加载

    fun openLocation(location: Location, pushStack: Boolean = true) {
        if (pushStack) navigate(Screen.Browse(location))
        loadLocation(location, pushStack = false)
    }

    private fun loadLocation(location: Location, pushStack: Boolean) {
        viewModelScope.launch {
            _browse.update {
                it.copy(location = location, loading = true, error = null, selection = SelectionState())
            }
            val sort = prefs.flow.first().sortByPath[location.id] ?: SortSpec()
            val filter = _browse.value.filter
            // 读目录 + 判可写都是跨进程调用，必须在 IO 线程完成
            val (entries, writable) = withContext(Dispatchers.IO) {
                val list = runCatching { repo.list(location, sort, filter) }.getOrDefault(emptyList())
                list to fs.isWritable(location)
            }
            val error = fs.lastError
            _browse.update {
                it.copy(
                    location = location,
                    // 面包屑直接由页面栈推导：栈里有几层 Browse，就显示几节。
                    // 这样"跳转/回退"天然一致，不需要额外维护一份 ancestry 列表。
                    ancestry = _stack.value.filterIsInstance<Screen.Browse>().map { b -> b.location }
                        .ifEmpty { listOf(location) },
                    entries = entries,
                    rawCount = entries.size,
                    loading = false,
                    error = if (entries.isEmpty() && error != null) error else null,
                    sort = sort,
                    writable = writable,
                    canPaste = _clipboard.value != null,
                    visibleCount = BrowseUiState.PAGE_SIZE,
                )
            }
            prefs.setLastLocation(location.uri)
        }
    }

    /** 面包屑跳转：栈里已有该目录就回退到它，否则新开一层。 */
    fun jumpTo(location: Location) {
        val stack = _stack.value
        val index = stack.indexOfLast { it is Screen.Browse && it.location.id == location.id }
        if (index >= 0) {
            _stack.value = stack.subList(0, index + 1).toList()
            loadLocation(location, pushStack = false)
        } else {
            openLocation(location)
        }
    }

    /** 滚动接近底部时追加一页。重复调用是安全的（幂等）。 */
    fun loadMore() {
        _browse.update {
            if (!it.hasMore) it else it.copy(visibleCount = it.visibleCount + BrowseUiState.PAGE_SIZE)
        }
    }

    fun refresh() {
        val location = _browse.value.location ?: return
        repo.invalidate(location.id)
        loadLocation(location, pushStack = false)
    }

    // ---------------------------------------------------------------- 打开 / 进入

    fun onEntryClick(entry: FileEntry) {
        val state = _browse.value
        if (state.selection.active) {
            toggleSelection(entry)
            return
        }
        if (entry.isDirectory) {
            val parent = state.location ?: return
            openLocation(fs.childLocation(parent, entry))
        } else {
            openFile(entry, state.entries)
        }
    }

    fun openFile(entry: FileEntry, siblings: List<FileEntry>) {
        when (entry.kind) {
            FileKind.ARCHIVE -> navigate(Screen.ArchiveBrowse(entry))
            FileKind.APK -> navigate(Screen.ApkDetails(entry))
            FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO, FileKind.DOCUMENT, FileKind.OTHER ->
                navigate(Screen.Viewer(entry, siblings))
            FileKind.FOLDER -> Unit
        }
        viewModelScope.launch {
            prefs.pushRecent(
                UserPreferences.RecentItem(
                    uri = entry.uri.toString(),
                    treeUri = _browse.value.location?.treeUri?.toString(),
                    name = entry.name,
                    kind = entry.kind,
                    openedAt = System.currentTimeMillis(),
                    isDirectory = entry.isDirectory,
                )
            )
        }
    }

    /** 从搜索结果 / 收藏 / 最近里打开任意条目：目录要先反查出它所属的授权根。 */
    fun openEntryAnywhere(entry: FileEntry) {
        if (!entry.isDirectory) {
            openFile(entry, emptyList())
            return
        }
        val location = fs.locationFor(entry.uri)
        if (location != null) {
            openLocationFromOverlay(location)
        } else {
            _message.value = "该目录已失效或未授权，请重新授权后访问"
        }
    }

    /** 外部 ACTION_VIEW 进来：不复制文件，直接按原 Uri 打开。 */
    fun openExternalUri(uri: Uri, mime: String?) {
        val name = fs.displayName(uri) ?: uri.lastPathSegment ?: "文件"
        val entry = FileEntry.of(uri, name, false, fs.sizeOf(uri), System.currentTimeMillis(), mime)
        openFile(entry, emptyList())
    }

    // ---------------------------------------------------------------- 选择

    fun startSelection(entry: FileEntry) {
        val index = _browse.value.entries.indexOfFirst { it.key == entry.key }
        _browse.update { it.copy(selection = SelectionState().start(entry, index)) }
    }

    fun toggleSelection(entry: FileEntry) {
        val index = _browse.value.entries.indexOfFirst { it.key == entry.key }
        _browse.update { it.copy(selection = it.selection.toggle(entry.key, index)) }
    }

    fun clearSelection() {
        _browse.update { it.copy(selection = SelectionState(), swiping = false) }
    }

    fun selectAll() {
        val entries = _browse.value.entries
        _browse.update {
            it.copy(selection = SelectionState(active = true, keys = entries.map { e -> e.key }.toSet(), anchorIndex = 0))
        }
    }

    /** 「右滑跨过另一个文件」的起点。 */
    fun beginRangeSelect(index: Int) {
        val entries = _browse.value.entries
        val entry = entries.getOrNull(index) ?: return
        _browse.update {
            it.copy(
                selection = SelectionState(active = true, keys = it.selection.keys + entry.key, anchorIndex = index),
                swiping = true,
            )
        }
    }

    /** 拖过 N 行 → 把 anchor..anchor+N 整段并入选区（累加，不清除已有选择）。 */
    fun extendRangeSelect(targetIndex: Int) {
        val entries = _browse.value.entries
        _browse.update { it.copy(selection = it.selection.range(entries, targetIndex)) }
    }

    fun endRangeSelect() {
        _browse.update { it.copy(swiping = false) }
    }

    fun selectedEntries(): List<FileEntry> {
        val keys = _browse.value.selection.keys
        return _browse.value.entries.filter { it.key in keys }
    }

    // ---------------------------------------------------------------- 排序 / 筛选

    fun setSortKey(key: SortKey) {
        val location = _browse.value.location ?: return
        val next = _browse.value.sort.let { if (it.key == key) it.toggleDirection() else SortSpec(key) }
        applySort(next)
        viewModelScope.launch { prefs.setSort(location.id, next) }
    }

    fun applySort(spec: SortSpec) {
        _browse.update {
            it.copy(
                sort = spec,
                entries = repo.applySortAndFilter(it.entries, spec, it.filter),
                visibleCount = BrowseUiState.PAGE_SIZE,
            )
        }
    }

    fun setFilter(filter: KindFilter) {
        val location = _browse.value.location ?: return
        viewModelScope.launch {
            val entries = repo.list(location, _browse.value.sort, filter)
            _browse.update {
                it.copy(filter = filter, entries = entries, visibleCount = BrowseUiState.PAGE_SIZE)
            }
        }
    }

    // ---------------------------------------------------------------- 剪贴板

    fun copySelection(cut: Boolean) {
        val entries = selectedEntries()
        val parent = _browse.value.location ?: return
        if (entries.isEmpty()) return
        _clipboard.value = ClipboardState(if (cut) ClipMode.CUT else ClipMode.COPY, entries, parent)
        _browse.update { it.copy(selection = SelectionState(), canPaste = true) }
        _message.value = "${if (cut) "已剪切" else "已复制"} ${entries.size} 项"
    }

    fun paste() {
        val clip = _clipboard.value ?: return
        val dest = _browse.value.location ?: return
        val srcParent = clip.sourceLocation
        runOperation(if (clip.mode == ClipMode.CUT) "移动" else "复制") {
            val result = if (clip.mode == ClipMode.CUT) {
                operator.move(clip.entries, srcParent, dest, ::publishProgress) { cancelledFlag }
            } else {
                operator.copy(clip.entries, srcParent, dest, ::publishProgress) { cancelledFlag }
            }
            if (clip.mode == ClipMode.CUT && result is OpResult.Success) _clipboard.value = null
            // 只有"复制"可以撤销成"删除副本"；移动的撤销必须是把文件搬回去，
            // 直接删掉新位置等于丢数据，所以这里只对 COPY 提供撤销。
            if (clip.mode == ClipMode.COPY && result is OpResult.Success && result.created.isNotEmpty()) {
                _undo.value = UndoRecord.DeleteCreated("已复制 ${result.created.size} 项", result.created)
            }
            result
        }
    }

    // ---------------------------------------------------------------- 删除

    fun requestDelete(entries: List<FileEntry>) {
        if (entries.isEmpty()) return
        val total = entries.sumOf { if (it.isDirectory) 0L else it.size }
        _confirm.value = ConfirmSpec(
            kind = ConfirmKind.DELETE,
            title = "移到回收站",
            message = "${entries.size} 项 · ${formatBytes(total)}\n可在回收站恢复，30 天后自动清理",
            confirmLabel = "移到回收站",
            danger = true,
            uris = entries.map { it.uri },
        )
    }

    fun requestRename(entry: FileEntry) {
        _renameTarget.value = entry
    }

    private val _renameTarget = MutableStateFlow<FileEntry?>(null)
    val renameTarget: StateFlow<FileEntry?> = _renameTarget.asStateFlow()

    fun dismissRename() {
        _renameTarget.value = null
    }

    fun performRename(entry: FileEntry, newName: String) {
        viewModelScope.launch {
            val result = operator.rename(entry, newName)
            _message.value = when (result) {
                is OpResult.Success -> "已重命名为「${newName}」"
                is OpResult.Failed -> result.reason
                else -> "重命名失败"
            }
            _renameTarget.value = null
            refresh()
        }
    }

    fun requestCompress(entries: List<FileEntry>) {
        if (entries.isEmpty()) return
        val total = entries.sumOf { if (it.isDirectory) 0L else it.size }
        _confirm.value = ConfirmSpec(
            kind = ConfirmKind.COMPRESS,
            title = "压缩为 ZIP",
            message = "${entries.size} 项 · 原始 ${formatBytes(total)}\n压缩期间请勿退出应用",
            confirmLabel = "开始压缩",
            uris = entries.map { it.uri },
            archiveFormat = ArchiveFormat.ZIP,
        )
    }

    fun requestExtract(entry: FileEntry) {
        val format = ArchiveFormat.ofExtension(entry.extension)
        if (format == null) {
            _message.value = "暂不支持 .${entry.extension} 格式"
            return
        }
        _confirm.value = ConfirmSpec(
            kind = ConfirmKind.EXTRACT,
            title = "解压到当前目录",
            message = "${entry.name}\n${formatBytes(entry.size)} → 将在当前位置新建同名文件夹",
            confirmLabel = "开始解压",
            uris = listOf(entry.uri),
            archiveFormat = format,
        )
    }

    // ---------------------------------------------------------------- 确认后执行

    fun confirmAction() {
        val spec = _confirm.value ?: return
        _confirm.value = null
        when (spec.kind) {
            ConfirmKind.DELETE -> performDelete(spec.uris)
            ConfirmKind.COMPRESS -> performCompress(spec.uris, spec.archiveFormat ?: ArchiveFormat.ZIP)
            ConfirmKind.EXTRACT -> performExtract(spec.uris.firstOrNull(), spec.archiveFormat)
            ConfirmKind.INSTALL -> {
                val info = _apkInfo.value ?: return
                val error = apkTool.launchInstaller(info)
                if (error != null) _message.value = error
            }
            ConfirmKind.OVERWRITE, ConfirmKind.UNINSTALL -> Unit
        }
    }

    private fun performDelete(uris: List<Uri>) {
        val parent = _browse.value.location ?: return
        val entries = _browse.value.entries.filter { it.uri in uris }
        runOperation("删除") {
            val (result, items) = operator.delete(entries, parent, ::publishProgress) { cancelledFlag }
            if (items.isNotEmpty()) {
                _undo.value = UndoRecord.RestoreFromTrash("已移到回收站 ${items.size} 项", items)
            }
            result
        }
        clearSelection()
    }

    private fun performCompress(uris: List<Uri>, format: ArchiveFormat) {
        val state = _browse.value
        val parent = state.location ?: return
        val entries = state.entries.filter { it.uri in uris }
        val baseName = if (entries.size == 1) entries.first().name.substringBeforeLast('.') else "压缩包"
        runOperation("压缩") {
            val (result, uri) = archiveEngine.create(
                entries, parent, parent, baseName, format, null, ::publishProgress,
            ) { cancelledFlag }
            if (uri != null) _message.value = "已创建 ${fs.displayName(uri) ?: "压缩包"}"
            result
        }
        clearSelection()
    }

    private fun performExtract(uri: Uri?, format: ArchiveFormat?) {
        val entry = _browse.value.entries.firstOrNull { it.uri == uri } ?: return
        val parent = _browse.value.location ?: return
        val target = fs.createDirectory(parent, entry.name.substringBeforeLast('.'))
            ?.let { Location(it, parent.treeUri, entry.name.substringBeforeLast('.'), parent.isSandbox) }
            ?: parent
        runOperation("解压") {
            archiveEngine.extract(entry, target, null, ::publishProgress) { cancelledFlag }
        }
    }

    private fun runOperation(title: String, block: suspend () -> OpResult) {
        opJob?.cancel()
        cancelledFlag = false
        _progress.value = OpProgress(title = title, indeterminate = true)
        opJob = viewModelScope.launch {
            val result = runCatching { block() }.getOrElse { OpResult.Failed(it.message?.take(60) ?: "操作失败") }
            _progress.value = null
            _message.value = describe(result)
            repo.invalidate(_browse.value.location?.id)
            refresh()
            _volume.value = repo.volumeUsage()
        }
    }

    private fun publishProgress(progress: OpProgress) {
        _progress.value = progress
    }

    private fun describe(result: OpResult): String = when (result) {
        is OpResult.Success -> "完成（${result.affected} 项）"
        is OpResult.Partial -> "部分完成：${result.succeeded} 项成功，${result.failed.size} 项失败（${result.failed.first().reason}）"
        is OpResult.Failed -> result.reason
        OpResult.Cancelled -> "已取消"
    }

    // ---------------------------------------------------------------- 撤销

    fun performUndo() {
        val record = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch {
            val ok = operator.undo(record)
            _message.value = if (ok) "已撤销" else "撤销失败（目标位置可能已被修改）"
            refresh()
        }
    }

    // ---------------------------------------------------------------- 新建

    fun createFolder(name: String) {
        val location = _browse.value.location ?: return
        viewModelScope.launch {
            val result = operator.createFolder(location, name)
            _message.value = describe(result)
            refresh()
        }
    }

    fun createFile(name: String) {
        val location = _browse.value.location ?: return
        viewModelScope.launch {
            val result = operator.createFile(location, name)
            _message.value = describe(result)
            refresh()
        }
    }

    // ---------------------------------------------------------------- 分享

    @SuppressLint("WearRecents") // 分享面板是外部 Activity，必须 NEW_TASK
    fun share(entries: List<FileEntry>) {
        if (entries.isEmpty()) return
        val files = entries.filterNot { it.isDirectory }
        if (files.isEmpty()) {
            _message.value = "文件夹请先压缩再分享"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val shareDir = File(app.cacheDir, "share").apply { mkdirs() }
            val uris = files.mapNotNull { entry ->
                runCatching {
                    val taken = shareDir.list()?.toSet() ?: emptySet()
                    val target = File(shareDir, uniqueName(entry.name) { it in taken })
                    fs.openInput(entry.uri).use { input -> target.outputStream().use { input.copyTo(it) } }
                    FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", target)
                }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                if (uris.isEmpty()) {
                    _message.value = "无法读取所选文件"
                    return@withContext
                }
                val intent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = files.first().mimeType ?: "*/*"
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { app.startActivity(Intent.createChooser(intent, "分享到").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { _message.value = "没有可用的分享目标" }
            }
        }
    }

    // ---------------------------------------------------------------- 回收站

    private val _trashItems = MutableStateFlow<List<TrashItem>>(emptyList())
    val trashItems: StateFlow<List<TrashItem>> = _trashItems.asStateFlow()

    fun loadTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            _trashItems.value = trashRepo.list()
            _trashSummary.value = trashRepo.summary()
        }
    }

    fun restoreTrash(item: TrashItem) {
        viewModelScope.launch {
            val uri = trashRepo.restore(item)
            _message.value = if (uri != null) "已恢复到原目录" else "恢复失败"
            loadTrash()
            refresh()
        }
    }

    fun purgeTrash(item: TrashItem) {
        viewModelScope.launch {
            trashRepo.purge(item)
            loadTrash()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            trashRepo.emptyAll()
            loadTrash()
            _message.value = "回收站已清空"
        }
    }

    // ---------------------------------------------------------------- 安装包

    private val _apkInfo = MutableStateFlow<ApkTool.ApkInfo?>(null)
    val apkInfo: StateFlow<ApkTool.ApkInfo?> = _apkInfo.asStateFlow()

    private val _apkLoading = MutableStateFlow(false)
    val apkLoading: StateFlow<Boolean> = _apkLoading.asStateFlow()

    fun inspectApk(entry: FileEntry) {
        _apkLoading.value = true
        viewModelScope.launch {
            val siblings = _browse.value.entries
            val info = apkTool.inspect(entry, siblings)
            _apkInfo.value = info
            _apkLoading.value = false
        }
    }

    fun requestInstall() {
        val info = _apkInfo.value ?: return
        if (!info.canInstall) {
            if (!apkTool.canInstallPackages()) {
                apkTool.openUnknownSourcesSettings()
                _message.value = "请允许本应用安装未知来源应用"
            } else {
                _message.value = info.blockReason ?: "无法安装"
            }
            return
        }
        _confirm.value = ConfirmSpec(
            kind = ConfirmKind.INSTALL,
            title = "安装 ${info.label ?: info.packageName}",
            message = buildString {
                append("版本 ${info.versionName}（${info.versionCode}）\n")
                append("签名 ${info.signatureShort}\n")
                info.warnings.firstOrNull()?.let { append(it) }
            },
            confirmLabel = "调起安装器",
            danger = info.warnings.isNotEmpty(),
        )
    }

    // ---------------------------------------------------------------- 压缩包

    private val _archiveInfo = MutableStateFlow<ArchiveEngine.InspectResult?>(null)
    val archiveInfo: StateFlow<ArchiveEngine.InspectResult?> = _archiveInfo.asStateFlow()

    fun inspectArchive(entry: FileEntry, password: String? = null) {
        viewModelScope.launch {
            _archiveInfo.value = archiveEngine.inspect(entry, password)
        }
    }

    // ---------------------------------------------------------------- 搜索

    data class SearchUiState(
        val query: String = "",
        val results: List<FileEntry> = emptyList(),
        val running: Boolean = false,
        val scanned: Int = 0,
    )

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private var searchJob: Job? = null

    fun setQuery(query: String) {
        _search.update { it.copy(query = query) }
        if (query.trim().length < 2) {
            searchJob?.cancel()
            _search.update { it.copy(results = emptyList(), running = false) }
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _search.update { it.copy(running = true, results = emptyList()) }
            val results = repo.search(
                roots = repo.roots(),
                query = query,
                onProgress = { scanned, _ -> _search.update { s -> s.copy(scanned = scanned) } },
            )
            _search.update { it.copy(results = results, running = false) }
        }
    }

    // ---------------------------------------------------------------- 局域网

    val peers: StateFlow<List<LanDiscovery.Peer>> = discovery.peers
    val lanState: StateFlow<LanTransfer.State> = lan.state
    val discoveryStatus: StateFlow<String> = discovery.status

    fun startLan() {
        viewModelScope.launch {
            val p = prefs.flow.first()
            lan.setIdentity(p.deviceId, p.deviceName)
            val port = lan.startServer()
            if (port > 0) discovery.startAdvertising(p.deviceId, p.deviceName, port)
            discovery.startDiscovery()
        }
    }

    fun stopLan() {
        discovery.stopAll()
        lan.stopServer()
    }

    fun sendTo(peer: LanDiscovery.Peer) {
        val entries = selectedEntries().ifEmpty {
            _browse.value.entries.filter { it.kind != FileKind.FOLDER }
        }
        val parent = _browse.value.location ?: return
        if (entries.isEmpty()) {
            _message.value = "没有可发送的文件"
            return
        }
        lan.send(peer, entries, parent)
        navigate(Screen.TransferProgress)
    }

    fun inboxLocation(): Location = lan.inboxLocation()

    fun acceptIncoming() = lan.acceptIncoming()
    fun rejectIncoming() = lan.rejectIncoming()
    fun cancelTransfer() = lan.cancel()
    fun resetTransfer() = lan.reset()

    // ---------------------------------------------------------------- 路径输入

    fun resolvePath(input: String): StorageGateway.PathResult =
        fs.resolveManualPath(input, _browse.value.location)

    /**
     * 从快捷菜单 / 路径输入这类"覆盖层"跳到某个目录。
     * 覆盖层不应该留在返回栈里，否则用户按返回会回到"已经用完的菜单"。
     */
    fun openLocationFromOverlay(location: Location) {
        _stack.update { stack ->
            val trimmed = stack.dropLastWhile {
                it is Screen.QuickMenu || it is Screen.PathInput || it is Screen.Settings
            }.ifEmpty { listOf(Screen.Home) }
            trimmed + Screen.Browse(location)
        }
        loadLocation(location, pushStack = false)
    }

    fun addTreeLocation(uri: Uri) {
        val location = fs.locationFromTreeUri(uri) ?: run {
            _message.value = "无法授权该目录"
            return
        }
        refreshRoots()
        openLocation(location)
    }

    // ---------------------------------------------------------------- 设置

    fun setDeviceName(name: String) {
        viewModelScope.launch {
            prefs.setDeviceName(name)
            val id = prefs.ensureDeviceId()
            lan.setIdentity(id, name)
            _message.value = "设备名已更新"
        }
    }

    fun setShowScrollRail(show: Boolean) {
        viewModelScope.launch { prefs.setShowScrollRail(show) }
    }

    fun setLanDiscoveryDefault(enabled: Boolean) {
        viewModelScope.launch { prefs.setLanDiscoveryEnabled(enabled) }
    }

    /** 撤销某个 SAF 授权（只解除本应用的访问权，不动用户文件）。 */
    fun removeLocation(location: Location) {
        fs.releaseLocation(location)
        refreshRoots()
        _message.value = "已移除授权：${location.name}"
    }

    private val _trashSummary = MutableStateFlow("—")
    val trashSummary: StateFlow<String> = _trashSummary.asStateFlow()

    // ---------------------------------------------------------------- 查看器支撑

    /** 文本预览：只读前 200KB，超过则截断并提示——手表上没人会读 5MB 的日志。 */
    suspend fun readTextPreview(entry: FileEntry, maxBytes: Int = 200_000): String = withContext(Dispatchers.IO) {
        runCatching {
            fs.openInput(entry.uri).use { input ->
                val buffer = ByteArray(maxBytes)
                var total = 0
                while (total < maxBytes) {
                    val read = input.read(buffer, total, maxBytes - total)
                    if (read <= 0) break
                    total += read
                }
                val text = String(buffer, 0, total, Charsets.UTF_8)
                if (total >= maxBytes) "$text\n\n…（已截断，仅预览前 ${maxBytes / 1024}KB）" else text
            }
        }.getOrElse { "无法读取文件：${it.message}" }
    }

    /** PDF / 音视频需要文件描述符或可直接播放的 Uri。 */
    fun openFileDescriptor(uri: Uri): android.os.ParcelFileDescriptor? = runCatching {
        getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")
    }.getOrNull()

    fun openStream(uri: Uri): java.io.InputStream = fs.openInput(uri)

    // ---------------------------------------------------------------- 收藏

    fun toggleFavorite(entry: FileEntry) {
        viewModelScope.launch {
            val current = prefs.flow.first().favorites
            val isFav = entry.key in current
            prefs.setFavorite(entry.uri, !isFav)
            _message.value = if (isFav) "已取消收藏" else "已收藏"
        }
    }

    override fun onCleared() {
        super.onCleared()
        discovery.stopAll()
        lan.shutdown()
    }

}
