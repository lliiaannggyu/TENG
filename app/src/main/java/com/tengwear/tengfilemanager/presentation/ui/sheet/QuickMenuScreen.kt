package com.tengwear.tengfilemanager.presentation.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextToggleButton
import com.tengwear.tengfilemanager.R
import com.tengwear.tengfilemanager.core.model.ClipMode
import com.tengwear.tengfilemanager.core.model.KindFilter
import com.tengwear.tengfilemanager.core.model.SortKey
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.Screen
import com.tengwear.tengfilemanager.presentation.theme.LocalKindColors
import com.tengwear.tengfilemanager.presentation.ui.common.MenuCard
import com.tengwear.tengfilemanager.presentation.ui.common.SectionHeader

private enum class CreateMode { FOLDER, FILE }

/**
 * 快捷菜单（浏览页底部边缘按钮打开）。
 *
 * 它不是"设置页"，而是**当前上下文的最短操作路径**：
 *  · 有剪贴板内容时，"粘贴"永远排在第一位；
 *  · 处于选择态时，整页换成批量操作（复制/剪切/删除/压缩/分享/发送）；
 *  · 排序/筛选/路径/位置都在这里，不需要用户回首页绕一圈。
 *
 * 全部用官方 M3E 组件：动作是 [MenuCard]（官方默认度量的 Card），排序是 [RadioButton]，
 * 筛选是 [TextToggleButton]（官方 toggle chip）装进官方 [ButtonGroup]
 * （官方指南：一组不超过 3 个按钮），新建/打开是 [Button]。
 */
@Composable
fun QuickMenuScreen(vm: FileManagerViewModel) {
    val kinds = LocalKindColors.current
    val scheme = MaterialTheme.colorScheme
    val browse by vm.browse.collectAsStateWithLifecycle()
    val clipboard by vm.clipboard.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()
    val selection = browse.selection
    val selected = vm.selectedEntries()

    var createMode by remember { mutableStateOf<CreateMode?>(null) }
    var input by remember { mutableStateOf("") }

    /** 执行动作并关闭菜单：菜单是"覆盖层"，动作完成后不该留在返回栈里。 */
    fun closeWith(action: () -> Unit) {
        action()
        vm.back()
    }

    ScreenScaffold(scrollState = state, edgeButton = {}) { padding ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "title") {
                SectionHeader(
                    if (selection.active) "已选 ${selection.count} 项" else (browse.location?.name ?: "菜单"),
                )
            }

            // ---------------- 新建输入态 ----------------
            if (createMode != null) {
                item(key = "input") {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(scheme.surfaceContainer)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
                item(key = "create") {
                    Button(
                        onClick = {
                            val name = input.ifBlank {
                                if (createMode == CreateMode.FOLDER) "新建文件夹" else "新建文件.txt"
                            }
                            if (createMode == CreateMode.FOLDER) vm.createFolder(name) else vm.createFile(name)
                            vm.back()
                        },
                        modifier = Modifier.fillMaxWidth(0.6f),
                    ) {
                        Text(
                            text = if (createMode == CreateMode.FOLDER) "创建文件夹" else "创建文件",
                            maxLines = 1,
                        )
                    }
                }
                item(key = "cancel") {
                    TextButton(
                        onClick = {
                            createMode = null
                            input = ""
                        },
                    ) {
                        Text(text = "取消", maxLines = 1)
                    }
                }
                return@ScalingLazyColumn
            }

            // ---------------- 选择态：批量操作 ----------------
            if (selection.active) {
                item(key = "copy") {
                    MenuCard(
                        icon = R.drawable.ic_content_copy,
                        title = "复制",
                        subtitle = "${selected.size} 项",
                        accent = kinds.document,
                    ) { closeWith { vm.copySelection(cut = false) } }
                }
                item(key = "cut") {
                    MenuCard(
                        icon = R.drawable.ic_content_cut,
                        title = "剪切",
                        subtitle = "移动后原位置不再保留",
                        accent = kinds.warning,
                    ) { closeWith { vm.copySelection(cut = true) } }
                }
                item(key = "delete") {
                    MenuCard(
                        icon = Icons.Filled.Delete,
                        title = "删除",
                        subtitle = "移到回收站，可恢复",
                        accent = scheme.error,
                    ) { closeWith { vm.requestDelete(selected) } }
                }
                if (selected.size == 1) {
                    item(key = "rename") {
                        MenuCard(
                            icon = Icons.Filled.Edit,
                            title = "重命名",
                            subtitle = selected.first().name,
                            accent = scheme.onSurfaceVariant,
                        ) { closeWith { vm.requestRename(selected.first()) } }
                    }
                }
                item(key = "compress") {
                    MenuCard(
                        icon = R.drawable.ic_kind_archive,
                        title = "压缩为 ZIP",
                        subtitle = "支持加密，稍后可在详情中设置",
                        accent = kinds.archive,
                    ) { closeWith { vm.requestCompress(selected) } }
                }
                item(key = "share") {
                    MenuCard(
                        icon = Icons.Filled.Share,
                        title = "分享到其他应用",
                        subtitle = null,
                        accent = kinds.image,
                    ) { closeWith { vm.share(selected) } }
                }
                item(key = "send") {
                    MenuCard(
                        icon = Icons.AutoMirrored.Filled.Send,
                        title = "发送到附近设备",
                        subtitle = "局域网直连",
                        accent = kinds.video,
                    ) { closeWith { vm.navigate(Screen.Devices) } }
                }
                item(key = "all") {
                    MenuCard(
                        icon = Icons.Filled.Done,
                        title = "全选",
                        subtitle = "共 ${browse.entries.size} 项",
                        accent = scheme.onSurfaceVariant,
                    ) { vm.selectAll() }
                }
                item(key = "exit") {
                    MenuCard(
                        icon = Icons.Filled.Clear,
                        title = "退出选择",
                        subtitle = null,
                        accent = scheme.onSurfaceVariant,
                    ) { closeWith { vm.clearSelection() } }
                }
                return@ScalingLazyColumn
            }

            // ---------------- 普通态 ----------------
            clipboard?.let { clip ->
                item(key = "paste") {
                    MenuCard(
                        icon = R.drawable.ic_content_paste,
                        title = "粘贴",
                        subtitle = "${if (clip.mode == ClipMode.CUT) "剪切" else "复制"} ${clip.count} 项 · ${formatBytes(clip.totalBytes)}",
                        accent = kinds.success,
                    ) { closeWith { vm.paste() } }
                }
            }

            item(key = "newFolder") {
                MenuCard(
                    icon = R.drawable.ic_kind_folder,
                    title = "新建文件夹",
                    subtitle = null,
                    accent = kinds.folder,
                ) {
                    createMode = CreateMode.FOLDER
                    input = "新建文件夹"
                }
            }
            item(key = "newFile") {
                MenuCard(
                    icon = Icons.Filled.Create,
                    title = "新建文件",
                    subtitle = null,
                    accent = kinds.document,
                ) {
                    createMode = CreateMode.FILE
                    input = "新建文件.txt"
                }
            }

            item(key = "sortTitle") { SectionHeader("排序") }
            SortKey.entries.forEach { key ->
                item(key = "sort_${key.name}") {
                    RadioButton(
                        selected = browse.sort.key == key,
                        onSelect = { vm.setSortKey(key) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = key.label,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
            item(key = "sortDir") {
                CompactButton(
                    onClick = { vm.applySort(browse.sort.toggleDirection()) },
                    modifier = Modifier.fillMaxWidth(0.6f),
                ) {
                    Text(
                        text = if (browse.sort.descending) "当前：降序" else "当前：升序",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }

            item(key = "filterTitle") { SectionHeader("筛选") }
            val filters = listOf(
                KindFilter.ALL, KindFilter.IMAGE, KindFilter.VIDEO, KindFilter.AUDIO,
                KindFilter.DOCUMENT, KindFilter.ARCHIVE, KindFilter.APK,
            )
            // 官方 ButtonGroup（指南：一组 ≤3 个）：7 个筛选项排成 3/3/1
            items(filters.chunked(3).size, key = { "frow_$it" }) { rowIndex ->
                val row = filters.chunked(3)[rowIndex]
                ButtonGroup(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { filter ->
                        TextToggleButton(
                            checked = browse.filter == filter,
                            onCheckedChange = { vm.setFilter(filter) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = filter.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            item(key = "path") {
                MenuCard(
                    icon = Icons.Filled.Place,
                    title = "输入路径",
                    subtitle = "精确跳转 / 访问已授权目录",
                    accent = scheme.onSurfaceVariant,
                ) { closeWith { vm.navigate(Screen.PathInput) } }
            }

            item(key = "switchTitle") { SectionHeader("切换位置") }
            items(roots.size, key = { "root_${roots[it].id}" }) { index ->
                val location = roots[index]
                MenuCard(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = location.name,
                    subtitle = null,
                    accent = kinds.folder,
                ) { closeWith { vm.openLocationFromOverlay(location) } }
            }

            item(key = "refresh") {
                MenuCard(
                    icon = Icons.Filled.Refresh,
                    title = "刷新目录",
                    subtitle = null,
                    accent = scheme.onSurfaceVariant,
                ) { closeWith { vm.refresh() } }
            }
            item(key = "settings") {
                MenuCard(
                    icon = Icons.Filled.Settings,
                    title = "设置",
                    subtitle = null,
                    accent = scheme.onSurfaceVariant,
                ) { closeWith { vm.navigate(Screen.Settings) } }
            }
        }
    }
}
