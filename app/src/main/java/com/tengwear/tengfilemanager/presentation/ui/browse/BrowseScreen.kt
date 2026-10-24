package com.tengwear.tengfilemanager.presentation.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.model.Location
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.core.util.formatRelativeTime
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.Screen
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.round.EdgeScrollRail
import com.tengwear.tengfilemanager.presentation.ui.round.EmptyState
import com.tengwear.tengfilemanager.presentation.ui.round.KindBadge
import com.tengwear.tengfilemanager.presentation.ui.round.RoundIconAction
import kotlinx.coroutines.launch

/**
 * 文件浏览页 —— 全应用的核心界面。
 *
 * 手势契约（与需求逐条对应）：
 *  ┌ 点按        进入文件夹 / 打开文件；选择态下切换该项选中
 *  ├ 长按        进入选择态并选中该项
 *  ├ 右滑跨行    区间多选（从按下项向右拖，越过一行就多选一行）
 *  ├ 上下滑      滚动列表（ScalingLazyColumn 内置，越靠边越小）
 *  ├ 表冠旋转    滚动（Wear foundation 内置 rotary 支持）
 *  ├ 底部边缘上滑 打开快捷菜单（粘贴/新建/排序/筛选/路径）
 *  └ 返回键/手势 逐级出栈；选择态下先退出选择
 *
 * 无表冠设备：右侧 [EdgeScrollRail] 可点按/拖动直接跳转到任意位置，
 * 相当于把"旋转表冠"换成了"点弧线上的刻度"。
 */
@Composable
fun BrowseScreen(vm: FileManagerViewModel, location: Location) {
    val palette = LocalTengPalette.current
    val browse by vm.browse.collectAsStateWithLifecycle()
    val prefs by vm.prefsState.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 44.dp.toPx() }
    val entries = browse.visibleEntries
    val selection = browse.selection

    // 接近底部时追加下一页（懒加载），配合 ScalingLazyColumn 的懒组合
    LaunchedEffect(state.centerItemIndex, browse.visibleCount) {
        if (browse.hasMore && state.centerItemIndex >= browse.visibleCount - 8) vm.loadMore()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 34.dp,
                // 选择态底部有操作条，列表要让出 96dp，否则末行会被压在按钮下面
                bottom = if (selection.active) 96.dp else 52.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
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
                        subtitle = if (browse.error == null) "上滑打开菜单可新建文件夹" else "可尝试重新授权该目录",
                    )
                }
            }

            // ③ 文件行
            items(count = entries.size, key = { index -> entries[index].key }) { index ->
                val entry = entries[index]
                FileRow(
                    entry = entry,
                    selected = entry.key in selection.keys,
                    selectionMode = selection.active,
                    rowHeightPx = rowHeightPx,
                    onClick = { vm.onEntryClick(entry) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.startSelection(entry)
                    },
                    onSwipeStart = { vm.beginRangeSelect(index) },
                    onSwipeRows = { rows ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.extendRangeSelect((index + rows).coerceIn(0, entries.lastIndex.coerceAtLeast(0)))
                    },
                    onSwipeEnd = { vm.endRangeSelect() },
                )
            }
        }

        // ④ 边缘滚动轨：无表冠设备的跳转入口
        EdgeScrollRail(
            itemCount = entries.size,
            currentIndex = state.centerItemIndex,
            onSeek = { index -> scope.launch { state.animateScrollToItem(index) } },
            modifier = Modifier.fillMaxSize(),
            visible = prefs.showScrollRail && entries.size > 4,
        )

        // ⑤ 选择态操作条
        if (selection.active) {
            SelectionBar(
                count = selection.count,
                onCopy = { vm.copySelection(cut = false) },
                onDelete = { vm.requestDelete(vm.selectedEntries()) },
                onMore = { vm.navigate(Screen.QuickMenu) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
            )
        }

        // ⑥ 底部边缘上滑热区：打开快捷菜单
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
 * 面包屑。圆屏上不做"整行铺满"，而是可横向滚动的小胶囊序列：
 * 深路径时前面的层级被推走，只保留最近两三层可见，符合"当前在哪"的认知。
 */
@Composable
private fun Breadcrumbs(
    ancestry: List<Location>,
    itemCount: Int,
    selectedCount: Int,
    onJump: (Location) -> Unit,
) {
    val palette = LocalTengPalette.current
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth(0.84f)
            .horizontalScroll(scroll)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ancestry.forEachIndexed { index, loc ->
            if (index > 0) {
                Text(text = "/", fontSize = 8.sp, color = palette.muted.copy(alpha = 0.6f))
            }
            Text(
                text = loc.name.take(10),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (index == ancestry.lastIndex) palette.cardHighlight else Color.Transparent)
                    .combinedClickable(
                        role = Role.Button,
                        onClick = { if (index != ancestry.lastIndex) onJump(loc) },
                        onLongClick = { onJump(loc) },
                    )
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                fontSize = 8.5.sp,
                color = if (index == ancestry.lastIndex) MaterialTheme.colorScheme.onBackground else palette.muted,
                maxLines = 1,
            )
        }
        Text(
            text = if (selectedCount > 0) "· 已选 $selectedCount" else "· $itemCount 项",
            fontSize = 8.sp,
            color = if (selectedCount > 0) MaterialTheme.colorScheme.primary else palette.muted.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

/**
 * 文件行。
 *
 * 三个交互共用同一块区域，靠"方向"区分而不是靠长按菜单：
 *  · 竖直 → 交给父级列表滚动（本行不消费）
 *  · 水平 → 本行消费，映射成"跨了几行"发给 ViewModel 做区间多选
 *  · 点按/长按 → combinedClickable
 * 行高 44dp 是实测折中：再矮则 8.5sp 副标题不可读，再高则一屏只能看 2 项。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    rowHeightPx: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeStart: () -> Unit,
    onSwipeRows: (Int) -> Unit,
    onSwipeEnd: () -> Unit,
) {
    val palette = LocalTengPalette.current
    var accumulated by remember(entry.key) { mutableFloatStateOf(0f) }
    var lastRows by remember(entry.key) { mutableFloatStateOf(0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth(0.80f)
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) palette.cardHighlight else palette.card.copy(alpha = 0.72f))
            .pointerInput(entry.key) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        accumulated = 0f
                        lastRows = 0f
                        onSwipeStart()
                    },
                    onDragEnd = {
                        accumulated = 0f
                        lastRows = 0f
                        onSwipeEnd()
                    },
                    onDragCancel = {
                        accumulated = 0f
                        lastRows = 0f
                        onSwipeEnd()
                    },
                ) { change, dragAmount ->
                    change.consume()
                    accumulated += dragAmount
                    // 只有"向右"才触发区间选择；向左滑动不参与，避免与返回手势冲突
                    if (accumulated > rowHeightPx * 0.6f) {
                        val rows = (accumulated / rowHeightPx).toInt()
                        if (rows != lastRows.toInt()) {
                            lastRows = rows.toFloat()
                            onSwipeRows(rows)
                        }
                    }
                }
            }
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        KindBadge(kind = entry.kind, size = 26.dp, dimmed = selectionMode && !selected)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontSize = 11.5.sp,
                color = if (selectionMode && !selected) palette.muted else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text = buildString {
                    if (entry.isDirectory) {
                        append("文件夹")
                    } else {
                        append(formatBytes(entry.size))
                        append(" · ")
                        append(formatRelativeTime(entry.lastModified))
                    }
                },
                fontSize = 8.sp,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✓", fontSize = 8.sp, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1)
            }
        }
    }
}

/** 选择态底部操作条：3 个圆按钮，总宽 118dp，在半径 0.7 处的弦宽内安全。 */
@Composable
private fun SelectionBar(
    count: Int,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTengPalette.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconAction(contentDescription = "复制", onClick = onCopy, size = 34.dp) {
                Text("复", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground)
            }
            RoundIconAction(
                contentDescription = "删除",
                onClick = onDelete,
                size = 34.dp,
                container = palette.danger.copy(alpha = 0.22f),
            ) {
                Text("删", fontSize = 12.sp, color = palette.danger)
            }
            RoundIconAction(contentDescription = "更多操作", onClick = onMore, size = 34.dp) {
                Text("⋯", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
            }
        }
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
        Row(
            modifier = Modifier
                .padding(bottom = 4.dp)
                .width(28.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(palette.muted.copy(alpha = 0.45f)),
        ) {}
    }
}
