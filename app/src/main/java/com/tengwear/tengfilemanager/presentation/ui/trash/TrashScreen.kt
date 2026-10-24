package com.tengwear.tengfilemanager.presentation.ui.trash

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.model.FileKind
import com.tengwear.tengfilemanager.core.util.daysBetween
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.core.util.formatRelativeTime
import com.tengwear.tengfilemanager.presentation.CollectionType
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.round.EmptyState
import com.tengwear.tengfilemanager.presentation.ui.round.RoundListRow
import com.tengwear.tengfilemanager.presentation.ui.round.RoundTextAction

/**
 * 回收站。
 *
 * 交互：点按 = 恢复（最高频动作），长按 = 彻底删除（不可逆，需二次确认）。
 * 不做"选中后底部操作条"——回收站里批量操作的价值远低于误操作风险。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(vm: FileManagerViewModel) {
    val palette = LocalTengPalette.current
    val items by vm.trashItems.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()

    LaunchedEffect(Unit) { vm.loadTrash() }

    ScalingLazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "header") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "回收站", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium)
                Text(
                    text = if (items.isEmpty()) "空" else "${items.size} 项 · ${formatBytes(items.sumOf { it.size })}",
                    fontSize = 9.sp,
                    color = palette.muted,
                )
            }
        }

        if (items.isEmpty()) {
            item(key = "empty") { EmptyState("回收站是空的", "删除的文件会先放到这里，30 天后自动清理") }
        }

        items(items.size, key = { items[it].id }) { index ->
            val item = items[index]
            val days = daysBetween(item.deletedAt)
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .clip(RoundedCornerShape(50))
                    .background(palette.card)
                    .combinedClickable(
                        role = Role.Button,
                        onClick = { vm.restoreTrash(item) },
                        onLongClick = { vm.purgeTrash(item) },
                    )
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(palette.danger.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "删", fontSize = 11.sp, color = palette.danger, maxLines = 1)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatBytes(item.size)} · ${formatRelativeTime(item.deletedAt)} · 剩 ${(30 - days).coerceAtLeast(0)} 天",
                        fontSize = 8.sp,
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
            }
        }

        if (items.isNotEmpty()) {
            item(key = "emptyAll") {
                RoundTextAction(
                    text = "清空回收站",
                    onClick = { vm.emptyTrash() },
                    container = palette.danger.copy(alpha = 0.22f),
                    contentColor = palette.danger,
                )
            }
            item(key = "hint") {
                Text(
                    text = "点按恢复 · 长按彻底删除",
                    fontSize = 8.sp,
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * 最近 / 收藏。
 *
 * 两者数据源不同（最近带时间与类型，收藏只有 Uri），但展示形态一致，
 * 所以共用一页；"收藏"的 Uri 反查失败时给出可读提示而不是空白。
 */
@Composable
fun CollectionScreen(vm: FileManagerViewModel, type: CollectionType) {
    val palette = LocalTengPalette.current
    val prefs by vm.prefsState.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()

    val recents = prefs.recents
    val favorites = prefs.favorites

    ScalingLazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "title") {
            Text(text = type.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium)
        }

        when (type) {
            CollectionType.RECENTS -> {
                if (recents.isEmpty()) {
                    item(key = "empty") { EmptyState("还没有打开过文件", "打开过的文件会出现在这里") }
                }
                items(recents.size, key = { recents[it].uri }) { index ->
                    val item = recents[index]
                    RoundListRow(
                        glyph = item.kind.badge,
                        title = item.name,
                        color = palette.of(item.kind),
                        subtitle = formatRelativeTime(item.openedAt),
                    ) {
                        vm.openEntryAnywhere(
                            FileEntry.of(
                                uri = android.net.Uri.parse(item.uri),
                                name = item.name,
                                isDirectory = item.isDirectory,
                                size = 0L,
                                lastModified = item.openedAt,
                                mimeType = null,
                            )
                        )
                    }
                }
            }

            CollectionType.FAVORITES -> {
                val list = favorites.toList()
                if (list.isEmpty()) {
                    item(key = "empty") { EmptyState("还没有收藏", "在选择菜单里点「收藏」即可加入") }
                }
                items(list.size, key = { list[it] }) { index ->
                    val uri = list[index]
                    val name = android.net.Uri.decode(uri).substringAfterLast('/').ifBlank { "已收藏项" }
                    val kind = FileKind.of(name, null, false)
                    RoundListRow(glyph = kind.badge, title = name, color = palette.of(kind), subtitle = "收藏") {
                        vm.openEntryAnywhere(
                            FileEntry.of(
                                uri = android.net.Uri.parse(uri),
                                name = name,
                                isDirectory = false,
                                size = 0L,
                                lastModified = 0L,
                                mimeType = null,
                            )
                        )
                    }
                }
            }
        }
    }
}
