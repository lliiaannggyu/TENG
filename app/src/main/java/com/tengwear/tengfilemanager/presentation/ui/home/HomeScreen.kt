package com.tengwear.tengfilemanager.presentation.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.presentation.CollectionType
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.Screen
import com.tengwear.tengfilemanager.presentation.theme.LocalKindColors
import com.tengwear.tengfilemanager.presentation.ui.common.ListRowHeight
import com.tengwear.tengfilemanager.presentation.ui.common.ListRowPadding
import com.tengwear.tengfilemanager.presentation.ui.common.MenuCard

/**
 * 首页。
 *
 * 全部由官方 M3E 组件搭成：骨架是 [ScreenScaffold]（时钟槽、滚动指示器、底部边缘按钮位），
 * 列表是 `ScalingLazyColumn`（默认自动居中，官方弧形缩放），每一行是 M3E `Card`
 * 的官方默认度量。时钟由外层 `AppScaffold` 统一提供，列表避让时钟用的是
 * `ScreenScaffold` 交给 content 的 contentPadding——那是官方避让机制。
 */
@Composable
fun HomeScreen(vm: FileManagerViewModel) {
    val kinds = LocalKindColors.current
    val scheme = MaterialTheme.colorScheme
    val volume by vm.volume.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val trashItems by vm.trashItems.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()

    val used = volume.first
    val total = volume.second
    val ratio = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f

    ScreenScaffold(scrollState = state, edgeButton = {}) { padding ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "storage") {
                Card(
                    onClick = { vm.openLocation(roots.firstOrNull() ?: return@Card) },
                    enabled = roots.isNotEmpty(),
                    contentPadding = ListRowPadding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ListRowHeight),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "存储",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                            Text(
                                text = if (total > 0) {
                                    "${formatBytes(used)} / ${formatBytes(total)}"
                                } else {
                                    "存储不可用"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurface,
                                textAlign = TextAlign.End,
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                        // 官方默认配色：primary 指示条 + 默认轨道色
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item(key = "browse") {
                MenuCard(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "浏览文件",
                    subtitle = roots.firstOrNull()?.name ?: "应用内部存储",
                    accent = kinds.folder,
                ) { roots.firstOrNull()?.let { vm.openLocation(it) } }
            }

            item(key = "recent") {
                MenuCard(
                    icon = Icons.Filled.DateRange,
                    title = "最近",
                    subtitle = "刚打开过的文件",
                    accent = kinds.document,
                ) { vm.navigate(Screen.Collection(CollectionType.RECENTS)) }
            }

            item(key = "fav") {
                MenuCard(
                    icon = Icons.Filled.Star,
                    title = "收藏",
                    subtitle = "常用文件与目录",
                    accent = kinds.warning,
                ) { vm.navigate(Screen.Collection(CollectionType.FAVORITES)) }
            }

            item(key = "search") {
                MenuCard(
                    icon = Icons.Filled.Search,
                    title = "搜索",
                    subtitle = "按名称查找",
                    accent = kinds.image,
                ) { vm.navigate(Screen.Search) }
            }

            item(key = "devices") {
                MenuCard(
                    icon = Icons.AutoMirrored.Filled.Send,
                    title = "设备",
                    subtitle = "局域网互传",
                    accent = kinds.video,
                ) { vm.navigate(Screen.Devices) }
            }

            item(key = "trash") {
                MenuCard(
                    icon = Icons.Filled.Delete,
                    title = "回收站",
                    subtitle = if (trashItems.isEmpty()) "空" else "${trashItems.size} 项可恢复",
                    accent = scheme.error,
                ) {
                    vm.loadTrash()
                    vm.navigate(Screen.Trash)
                }
            }

            item(key = "settings") {
                MenuCard(
                    icon = Icons.Filled.Settings,
                    title = "设置",
                    subtitle = "排序偏好 / 设备名",
                    accent = scheme.onSurfaceVariant,
                ) { vm.navigate(Screen.Settings) }
            }
        }
    }
}
