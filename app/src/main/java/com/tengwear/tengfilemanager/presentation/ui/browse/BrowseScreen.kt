package com.tengwear.tengfilemanager.presentation.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.tengwear.tengfilemanager.core.model.Location
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.Screen
import com.tengwear.tengfilemanager.presentation.ui.common.EmptyState
import com.tengwear.tengfilemanager.presentation.ui.common.FileCard
import com.tengwear.tengfilemanager.presentation.ui.common.SelectionActionBar
import com.tengwear.tengfilemanager.presentation.ui.common.SelectionScrim

/**
 * 文件浏览页 —— 全应用的核心界面。
 *
 * 骨架是官方 M3E [ScreenScaffold]：时钟避让、滚动指示器、底部边缘按钮位都由它负责；
 * 列表是 `ScalingLazyColumn`，每一行是官方默认度量的 M3E `Card`（见 [FileCard]）。
 * 快捷菜单入口用官方 [EdgeButton]（底部边缘按钮位，随滚动到底自动放大）——
 * 这是 M3E 里"列表底部的更多操作"的官方原语。
 *
 * 手势契约：
 *  ┌ 点按        进入文件夹 / 打开文件；选择态下切换该项选中
 *  ├ 长按        进入选择态并选中该项（Card 的 onLongClick）
 *  ├ 右滑跨行    区间多选（从按下项向右拖，越过一行就多选一行）
 *  ├ 上下滑      滚动列表（ScalingLazyColumn 内置，越靠边越小）
 *  ├ 表冠旋转    滚动（Wear foundation 内置 rotary 支持）
 *  ├ 底部边缘按钮 打开快捷菜单（粘贴/新建/排序/筛选/路径/批量操作）
 *  └ 返回键/手势 逐级出栈；选择态下先退出选择
 */
@Composable
fun BrowseScreen(vm: FileManagerViewModel, location: Location) {
    val browse by vm.browse.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()
    val haptic = LocalHapticFeedback.current
    val entries = browse.visibleEntries
    val selection = browse.selection

    // 接近底部时追加下一页（懒加载），配合 ScalingLazyColumn 的懒组合
    LaunchedEffect(state.centerItemIndex, browse.visibleCount) {
        if (browse.hasMore && state.centerItemIndex >= browse.visibleCount - 8) vm.loadMore()
    }

    ScreenScaffold(
        scrollState = state,
        edgeButton = {
            EdgeButton(
                onClick = { vm.navigate(Screen.QuickMenu) },
                buttonSize = EdgeButtonSize.Small,
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "快捷菜单",
                )
            }
        },
    ) { padding ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                // 选择态底部有操作条，列表要让出一截，否则末行会被压在按钮下面
                bottom = padding.calculateBottomPadding() + if (selection.active) 64.dp else 0.dp,
            ),
            // 官方文档：ScreenScaffold 与 EdgeButton 配套时应关闭自动居中
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ① 面包屑：作为列表首项随滚动缩放，避免固定在圆顶被裁切
            item(key = "crumbs") {
                Breadcrumbs(
                    ancestry = browse.ancestry.ifEmpty { listOf(location) },
                    itemCount = entries.size,
                    selectedCount = selection.count,
                    onJump = { vm.jumpTo(it) },
                )
            }

            // ② 空态 / 加载态 / 错误态都放在列表里，保证"圆心"始终有信息
            if (browse.loading) {
                item(key = "loading") { EmptyState("读取中…") }
            } else if (entries.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = browse.error ?: "此文件夹为空",
                        hint = if (browse.error == null) "底部按钮可新建文件夹" else "可尝试重新授权该目录",
                    )
                }
            }

            // ③ 文件行
            items(count = entries.size, key = { index -> entries[index].key }) { index ->
                val entry = entries[index]
                FileCard(
                    entry = entry,
                    selected = entry.key in selection.keys,
                    selecting = selection.active,
                    onClick = { vm.onEntryClick(entry) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.startSelection(entry)
                    },
                    onRangeSelect = if (selection.active) {
                        { rows ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.extendRangeSelect(
                                (index + rows).coerceIn(0, entries.lastIndex.coerceAtLeast(0)),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        // ④ 选择态操作条：滑入动画，浮在 EdgeButton 上方
        AnimatedVisibility(
            visible = selection.active,
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
            // 让出底部 EdgeButton 的高度，操作条浮在它上方
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp),
        ) {
            Box {
                SelectionScrim()
                SelectionActionBar(
                    onCopy = { vm.copySelection(cut = false) },
                    onDelete = { vm.requestDelete(vm.selectedEntries()) },
                    onCancel = { vm.clearSelection() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/**
 * 面包屑。
 *
 * 用 M3E [TextButton] 而不是自绘胶囊：官方组件自带按下形变与无障碍语义。
 * 深路径时整行可横滑，只保留最近几层可见，符合"当前在哪"的认知。
 */
@Composable
private fun Breadcrumbs(
    ancestry: List<Location>,
    itemCount: Int,
    selectedCount: Int,
    onJump: (Location) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ancestry.forEachIndexed { index, loc ->
            if (index > 0) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (index == ancestry.lastIndex) {
                Text(
                    text = loc.name.take(12),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                )
            } else {
                TextButton(onClick = { onJump(loc) }) {
                    Text(
                        text = loc.name.take(12),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            text = if (selectedCount > 0) "· 已选 $selectedCount" else "· $itemCount 项",
            style = MaterialTheme.typography.labelSmall,
            color = if (selectedCount > 0) scheme.primary else scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
