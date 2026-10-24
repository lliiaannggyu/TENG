package com.tengwear.tengfilemanager.presentation.ui.browse

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.model.Location
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.Screen
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.common.ClockGap
import com.tengwear.tengfilemanager.presentation.ui.common.EmptyState
import com.tengwear.tengfilemanager.presentation.ui.common.FileCard
import com.tengwear.tengfilemanager.presentation.ui.common.SelectionActionBar
import com.tengwear.tengfilemanager.presentation.ui.common.SelectionScrim

/**
 * 文件浏览页 —— 全应用的核心界面。
 *
 * 骨架是官方 M3E [ScreenScaffold]：时钟槽、滚动指示器、底部边缘按钮位都由它负责，
 * 列表是 `ScalingLazyColumn`，每一行是 M3E `Card`（见 [FileCard]）。
 * 无表冠设备的跳转由 M3E 的 `ScrollIndicator` 承担——自绘的"边缘滚动轨"已删除。
 *
 * 手势契约：
 *  ┌ 点按        进入文件夹 / 打开文件；选择态下切换该项选中
 *  ├ 长按        进入选择态并选中该项（Card 的 onLongClick）
 *  ├ 右滑跨行    区间多选（从按下项向右拖，越过一行就多选一行）
 *  ├ 上下滑      滚动列表（ScalingLazyColumn 内置，越靠边越小）
 *  ├ 表冠旋转    滚动（Wear foundation 内置 rotary 支持）
 *  ├ 底部边缘上滑 打开快捷菜单（粘贴/新建/排序/筛选/路径）
 *  └ 返回键/手势 逐级出栈；选择态下先退出选择
 */
@Composable
fun BrowseScreen(vm: FileManagerViewModel, location: Location) {
    val palette = LocalTengPalette.current
    val browse by vm.browse.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()
    val haptic = LocalHapticFeedback.current
    val entries = browse.visibleEntries
    val selection = browse.selection

    // 接近底部时追加下一页（懒加载），配合 ScalingLazyColumn 的懒组合
    LaunchedEffect(state.centerItemIndex, browse.visibleCount) {
        if (browse.hasMore && state.centerItemIndex >= browse.visibleCount - 8) vm.loadMore()
    }

    // 浮层必须放在 ScreenScaffold 外层：它的 content Box 不是全屏的，
    // 在里面 align(BottomCenter) 会把工具条顶到页面顶部（实测过）。
    Box(modifier = Modifier.fillMaxSize()) {
        ScreenScaffold(scrollState = state, edgeButton = {}) { _ ->
            ScalingLazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 6.dp,
                    // 选择态底部有操作条，列表要让出 96dp，否则末行会被压在按钮下面
                    bottom = if (selection.active) 96.dp else 52.dp,
                ),
                // 关掉自动居中：否则首次滚动会把首项重新拽到屏幕中心，
                // 同时它还会把 contentPadding.top 抵消掉。
                autoCentering = null,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 给顶部时钟带让位的真实占位项
                item(key = "clockGap") { ClockGap(28.dp) }

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
                            hint = if (browse.error == null) "上滑打开菜单可新建文件夹" else "可尝试重新授权该目录",
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
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
        }

        // ④ 选择态操作条
        if (selection.active) {
            SelectionScrim(modifier = Modifier.align(Alignment.BottomCenter))
            SelectionActionBar(
                onCopy = { vm.copySelection(cut = false) },
                onDelete = { vm.requestDelete(vm.selectedEntries()) },
                onMore = { vm.navigate(Screen.QuickMenu) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 圆屏底部弦宽很窄，工具条必须离底边 26dp，否则两端会被表壳切掉
                    .padding(bottom = 26.dp),
            )
        }

        // ⑤ 底部边缘上滑热区：打开快捷菜单
        if (!selection.active) {
            SwipeUpHandle(
                onOpen = { vm.navigate(Screen.QuickMenu) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(30.dp),
            )
        }
    }
}

/**
 * 面包屑。
 *
 * 用 M3E [TextButton] 而不是自绘胶囊：官方组件自带按下形变与无障碍语义。
 * 圆屏上不做"整行铺满"，深路径时前面的层级被横向推走，只保留最近两三层可见，
 * 符合"当前在哪"的认知。
 */
@Composable
private fun Breadcrumbs(
    ancestry: List<Location>,
    itemCount: Int,
    selectedCount: Int,
    onJump: (Location) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = LocalTengPalette.current
    val scroll = rememberScrollState()
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth(0.90f)
            .horizontalScroll(scroll)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ancestry.forEachIndexed { index, loc ->
            if (index > 0) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted.copy(alpha = 0.6f),
                )
            }
            if (index == ancestry.lastIndex) {
                Text(
                    text = loc.name.take(12),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
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
            color = if (selectedCount > 0) scheme.primary else palette.muted,
            maxLines = 1,
        )
    }
}

/**
 * 底部边缘上滑热区。
 * 高度只有 30dp 且贴着圆底，不与列表的可点击项重叠；
 * 同时提供一个可见的小横条作为"这里可以上滑"的暗示。
 */
@Composable
private fun SwipeUpHandle(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalTengPalette.current
    var dragged by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { dragged = 0f },
                onDragEnd = {
                    if (dragged < -20f) onOpen()
                    dragged = 0f
                },
                onDragCancel = { dragged = 0f },
            ) { change, dragAmount ->
                change.consume()
                dragged += dragAmount
            }
        },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 4.dp)
                .width(28.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(palette.muted.copy(alpha = 0.45f)),
        )
    }
}
