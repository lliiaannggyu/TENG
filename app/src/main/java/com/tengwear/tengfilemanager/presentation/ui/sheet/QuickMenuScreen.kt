package com.tengwear.tengfilemanager.presentation.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.model.ClipMode
import com.tengwear.tengfilemanager.core.model.KindFilter
import com.tengwear.tengfilemanager.core.model.SortKey
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.Screen
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.round.RoundListRow
import com.tengwear.tengfilemanager.presentation.ui.round.RoundTextAction

private enum class CreateMode { FOLDER, FILE }

/**
 * 快捷菜单（底部边缘上滑打开）。
 *
 * 它不是"设置页"，而是**当前上下文的最短操作路径**：
 *  · 有剪贴板内容时，"粘贴"永远排在第一位；
 *  · 处于选择态时，整页换成批量操作（复制/剪切/删除/压缩/分享/发送）；
 *  · 排序/筛选/路径/位置都在这里，不需要用户回首页绕一圈。
 *
 * 为什么用整页而不是 BottomSheet：圆屏上弹层高度一旦超过 60%，顶部的弧形留白会被切掉；
 * 整页 + ScalingLazyColumn 的缩放能保证所有内容都落在安全圆内。
 */
@Composable
fun QuickMenuScreen(vm: FileManagerViewModel) {
    val palette = LocalTengPalette.current
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

    ScalingLazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "title") {
            Text(
                text = if (selection.active) "已选 ${selection.count} 项" else (browse.location?.name ?: "菜单"),
                fontSize = 11.sp,
                color = palette.muted,
                maxLines = 1,
            )
        }

        // ---------------- 新建输入态 ----------------
        if (createMode != null) {
            item(key = "input") {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .clip(RoundedCornerShape(50))
                        .background(palette.card)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            item(key = "create") {
                RoundTextAction(
                    text = if (createMode == CreateMode.FOLDER) "创建文件夹" else "创建文件",
                    onClick = {
                        val name = input.ifBlank { if (createMode == CreateMode.FOLDER) "新建文件夹" else "新建文件.txt" }
                        if (createMode == CreateMode.FOLDER) vm.createFolder(name) else vm.createFile(name)
                        vm.back()
                    },
                    container = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            item(key = "cancel") {
                RoundTextAction(text = "取消", onClick = { createMode = null; input = "" }, container = palette.card)
            }
            return@ScalingLazyColumn
        }

        // ---------------- 选择态：批量操作 ----------------
        if (selection.active) {
            item(key = "copy") {
                RoundListRow("复", "复制", palette.document, subtitle = "${selected.size} 项") {
                    closeWith { vm.copySelection(cut = false) }
                }
            }
            item(key = "cut") {
                RoundListRow("剪", "剪切", palette.warning, subtitle = "移动后原位置不再保留") {
                    closeWith { vm.copySelection(cut = true) }
                }
            }
            item(key = "delete") {
                RoundListRow(
                    "删", "删除", palette.danger,
                    subtitle = "移到回收站，可恢复",
                ) { closeWith { vm.requestDelete(selected) } }
            }
            if (selected.size == 1) {
                item(key = "rename") {
                    RoundListRow("名", "重命名", palette.muted, subtitle = selected.first().name) {
                        closeWith { vm.requestRename(selected.first()) }
                    }
                }
            }
            item(key = "compress") {
                RoundListRow("压", "压缩为 ZIP", palette.archive, subtitle = "支持加密，稍后可在详情中设置") {
                    closeWith { vm.requestCompress(selected) }
                }
            }
            item(key = "share") {
                RoundListRow("享", "分享到其他应用", palette.image) {
                    closeWith { vm.share(selected) }
                }
            }
            item(key = "send") {
                RoundListRow("传", "发送到附近设备", palette.video, subtitle = "局域网直连") {
                    closeWith { vm.navigate(Screen.Devices) }
                }
            }
            item(key = "fav") {
                RoundListRow("藏", "收藏", palette.warning) {
                    selected.firstOrNull()?.let { vm.toggleFavorite(it) }
                    vm.back()
                }
            }
            item(key = "all") {
                RoundListRow("全", "全选", palette.muted, subtitle = "共 ${browse.entries.size} 项") {
                    vm.selectAll()
                }
            }
            item(key = "exit") {
                RoundListRow("退", "退出选择", palette.muted) { closeWith { vm.clearSelection() } }
            }
            return@ScalingLazyColumn
        }

        // ---------------- 普通态 ----------------
        clipboard?.let { clip ->
            item(key = "paste") {
                RoundListRow(
                    "粘", "粘贴", palette.success,
                    subtitle = "${if (clip.mode == ClipMode.CUT) "剪切" else "复制"} ${clip.count} 项 · ${formatBytes(clip.totalBytes)}",
                ) { closeWith { vm.paste() } }
            }
        }

        item(key = "newFolder") {
            RoundListRow("夹", "新建文件夹", palette.folder) {
                createMode = CreateMode.FOLDER
                input = "新建文件夹"
            }
        }
        item(key = "newFile") {
            RoundListRow("文", "新建文件", palette.document) {
                createMode = CreateMode.FILE
                input = "新建文件.txt"
            }
        }

        item(key = "sortTitle") {
            Text(text = "排序", fontSize = 9.sp, color = palette.muted, modifier = Modifier.padding(top = 4.dp))
        }
        SortKey.entries.forEach { key ->
            item(key = "sort_${key.name}") {
                val active = browse.sort.key == key
                RoundListRow(
                    glyph = when (key) {
                        SortKey.NAME -> "名"
                        SortKey.DATE -> "日"
                        SortKey.SIZE -> "大"
                    },
                    title = key.label,
                    color = if (active) MaterialTheme.colorScheme.primary else palette.muted,
                    selected = active,
                    trailing = if (active) (if (browse.sort.descending) "↓" else "↑") else null,
                ) { vm.setSortKey(key) }
            }
        }

        item(key = "filterTitle") {
            Text(text = "筛选", fontSize = 9.sp, color = palette.muted, modifier = Modifier.padding(top = 4.dp))
        }
        item(key = "filterRow1") {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(KindFilter.ALL, KindFilter.IMAGE, KindFilter.VIDEO).forEach { filter ->
                    RoundTextAction(
                        text = filter.label,
                        onClick = { vm.setFilter(filter) },
                        container = if (browse.filter == filter) MaterialTheme.colorScheme.primaryContainer else palette.card,
                        contentColor = if (browse.filter == filter) MaterialTheme.colorScheme.onPrimaryContainer else palette.muted,
                    )
                }
            }
        }
        item(key = "filterRow2") {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(KindFilter.AUDIO, KindFilter.DOCUMENT, KindFilter.ARCHIVE, KindFilter.APK).forEach { filter ->
                    RoundTextAction(
                        text = filter.label,
                        onClick = { vm.setFilter(filter) },
                        container = if (browse.filter == filter) MaterialTheme.colorScheme.primaryContainer else palette.card,
                        contentColor = if (browse.filter == filter) MaterialTheme.colorScheme.onPrimaryContainer else palette.muted,
                    )
                }
            }
        }

        item(key = "path") {
            RoundListRow("径", "输入路径", palette.muted, subtitle = "精确跳转 / 访问已授权目录") {
                closeWith { vm.navigate(Screen.PathInput) }
            }
        }

        item(key = "switchTitle") {
            Text(text = "切换位置", fontSize = 9.sp, color = palette.muted, modifier = Modifier.padding(top = 4.dp))
        }
        items(roots.size, key = { "root_${roots[it].id}" }) { index ->
            val location = roots[index]
            RoundListRow(
                glyph = "位",
                title = location.name,
                color = palette.folder,
                selected = location.id == browse.location?.id,
            ) { closeWith { vm.openLocationFromOverlay(location) } }
        }

        item(key = "refresh") {
            RoundListRow("刷", "刷新目录", palette.muted) { closeWith { vm.refresh() } }
        }
        item(key = "settings") {
            RoundListRow("设", "设置", palette.muted) { closeWith { vm.navigate(Screen.Settings) } }
        }
    }
}
