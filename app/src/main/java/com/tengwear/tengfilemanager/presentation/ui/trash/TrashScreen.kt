package com.tengwear.tengfilemanager.presentation.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.model.FileKind
import com.tengwear.tengfilemanager.core.util.daysBetween
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.core.util.formatRelativeTime
import com.tengwear.tengfilemanager.presentation.CollectionType
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.common.ClockGap
import com.tengwear.tengfilemanager.presentation.ui.common.EmptyState
import com.tengwear.tengfilemanager.presentation.ui.common.FileCard
import com.tengwear.tengfilemanager.presentation.ui.common.SectionHeader

/**
 * 回收站。
 *
 * 交互：点按 = 恢复（最高频动作），长按 = 彻底删除（不可逆，需二次确认）。
 * 点按/长按由 M3E [Card] 的 `onClick` / `onLongClick` 承担——官方组件自带
 * 按下形变、水波纹和无障碍语义，自绘的 `combinedClickable` 版本三样都要自己补。
 * 不做"选中后底部操作条"——回收站里批量操作的价值远低于误操作风险。
 */
@Composable
fun TrashScreen(vm: FileManagerViewModel) {
    val palette = LocalTengPalette.current
    val scheme = MaterialTheme.colorScheme
    val items by vm.trashItems.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()

    LaunchedEffect(Unit) { vm.loadTrash() }

    ScreenScaffold(scrollState = state, edgeButton = {}) { _ ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 44.dp),
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "gap") { ClockGap(26.dp) }
            item(key = "header") { SectionHeader("回收站") }
            item(key = "summary") {
                Text(
                    text = if (items.isEmpty()) {
                        "空"
                    } else {
                        "${items.size} 项 · ${formatBytes(items.sumOf { it.size })}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            if (items.isEmpty()) {
                item(key = "empty") {
                    EmptyState("回收站是空的", "删除的文件会先放到这里，30 天后自动清理")
                }
            }

            items(items.size, key = { items[it].id }) { index ->
                val item = items[index]
                val days = daysBetween(item.deletedAt)
                Card(
                    onClick = { vm.restoreTrash(item) },
                    onLongClick = { vm.purgeTrash(item) },
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth(0.86f),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = "${formatBytes(item.size)} · ${formatRelativeTime(item.deletedAt)} · 剩 ${(30 - days).coerceAtLeast(0)} 天",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            if (items.isNotEmpty()) {
                item(key = "emptyAll") {
                    Button(
                        onClick = { vm.emptyTrash() },
                        modifier = Modifier.fillMaxWidth(0.7f),
                    ) {
                        Text(text = "清空回收站", maxLines = 1)
                    }
                }
                item(key = "hint") {
                    Text(
                        text = "点按恢复 · 长按彻底删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted,
                        maxLines = 2,
                    )
                }
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
    val prefs by vm.prefsState.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()

    val recents = prefs.recents
    val favorites = prefs.favorites

    ScreenScaffold(scrollState = state, edgeButton = {}) { _ ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 44.dp),
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "gap") { ClockGap(26.dp) }
            item(key = "title") { SectionHeader(type.title) }

            when (type) {
                CollectionType.RECENTS -> {
                    if (recents.isEmpty()) {
                        item(key = "empty") { EmptyState("还没有打开过文件", "打开过的文件会出现在这里") }
                    }
                    items(recents.size, key = { recents[it].uri }) { index ->
                        val item = recents[index]
                        FileCard(
                            entry = FileEntry.of(
                                uri = android.net.Uri.parse(item.uri),
                                name = item.name,
                                isDirectory = item.isDirectory,
                                size = 0L,
                                lastModified = item.openedAt,
                                mimeType = null,
                            ),
                            onClick = {
                                vm.openEntryAnywhere(
                                    FileEntry.of(
                                        uri = android.net.Uri.parse(item.uri),
                                        name = item.name,
                                        isDirectory = item.isDirectory,
                                        size = 0L,
                                        lastModified = item.openedAt,
                                        mimeType = null,
                                    ),
                                )
                            },
                            secondary = formatRelativeTime(item.openedAt),
                        )
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
                        FileCard(
                            entry = FileEntry.of(
                                uri = android.net.Uri.parse(uri),
                                name = name,
                                isDirectory = false,
                                size = 0L,
                                lastModified = 0L,
                                mimeType = null,
                            ),
                            onClick = {
                                vm.openEntryAnywhere(
                                    FileEntry.of(
                                        uri = android.net.Uri.parse(uri),
                                        name = name,
                                        isDirectory = false,
                                        size = 0L,
                                        lastModified = 0L,
                                        mimeType = null,
                                    ),
                                )
                            },
                            secondary = "收藏",
                        )
                    }
                }
            }
        }
    }
}
