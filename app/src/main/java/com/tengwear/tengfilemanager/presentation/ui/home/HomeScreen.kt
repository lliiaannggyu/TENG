package com.tengwear.tengfilemanager.presentation.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
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
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.presentation.CollectionType
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.Screen
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.common.ClockGap
import com.tengwear.tengfilemanager.presentation.ui.common.MenuCard

/**
 * 首页。
 *
 * 全部由官方 M3E 组件搭成：骨架是 [ScreenScaffold]（时钟槽、滚动指示器、底部边缘按钮位），
 * 列表是 `ScalingLazyColumn`，每一行是 M3E [Card]。
 * 时钟由外层的 `AppScaffold` 统一提供，这里不再自己画——重复声明会和列表内容重叠。
 *
 * 相比自绘版本去掉了三样东西：
 *  · 背景的 264° 存储进度环 —— 换成卡片里一条 [LinearProgressIndicator]；
 *  · 顶部的 "TENG 文件" 文字 —— 时钟本身就是最合适的顶部标题；
 *  · 自绘胶囊行（Box + background + clickable）—— 换成 M3E Card。
 */
@Composable
fun HomeScreen(vm: FileManagerViewModel) {
    val palette = LocalTengPalette.current
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
            contentPadding = PaddingValues(top = 6.dp, bottom = 44.dp),
            // 关掉自动居中：否则首次滚动会把第一项重新拽到屏幕中心，视觉上跳一下；
            // 同时它还会把 contentPadding.top 抵消掉（实测 46dp 与 120dp 渲染完全相同）。
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // 给顶部时钟带让位的真实占位项（contentPadding.top 会被锚定逻辑抵消）
            item(key = "clockGap") { ClockGap(34.dp) }

            item(key = "storage") {
                Card(
                    onClick = { vm.openLocation(roots.firstOrNull() ?: return@Card) },
                    enabled = roots.isNotEmpty(),
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.fillMaxWidth(0.86f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "存储",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                            Text(
                                text = if (total > 0) {
                                    "${formatBytes(used)} / ${formatBytes(total)}"
                                } else {
                                    "存储不可用"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurface,
                                textAlign = TextAlign.End,
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                        LinearProgressIndicator(
                            progress = { ratio },
                            colors = ProgressIndicatorDefaults.colors(
                                indicatorColor = palette.document,
                                trackColor = scheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item(key = "browse") {
                MenuCard(
                    icon = Icons.Filled.List,
                    title = "浏览文件",
                    subtitle = roots.firstOrNull()?.name ?: "应用内部存储",
                    accent = palette.folder,
                ) { roots.firstOrNull()?.let { vm.openLocation(it) } }
            }

            item(key = "recent") {
                MenuCard(
                    icon = Icons.Filled.DateRange,
                    title = "最近",
                    subtitle = "刚打开过的文件",
                    accent = palette.document,
                ) { vm.navigate(Screen.Collection(CollectionType.RECENTS)) }
            }

            item(key = "fav") {
                MenuCard(
                    icon = Icons.Filled.Star,
                    title = "收藏",
                    subtitle = "常用文件与目录",
                    accent = palette.warning,
                ) { vm.navigate(Screen.Collection(CollectionType.FAVORITES)) }
            }

            item(key = "search") {
                MenuCard(
                    icon = Icons.Filled.Search,
                    title = "搜索",
                    subtitle = "按名称查找",
                    accent = palette.image,
                ) { vm.navigate(Screen.Search) }
            }

            item(key = "devices") {
                MenuCard(
                    icon = Icons.Filled.Send,
                    title = "设备",
                    subtitle = "局域网互传",
                    accent = palette.video,
                ) { vm.navigate(Screen.Devices) }
            }

            item(key = "trash") {
                MenuCard(
                    icon = Icons.Filled.Delete,
                    title = "回收站",
                    subtitle = if (trashItems.isEmpty()) "空" else "${trashItems.size} 项可恢复",
                    accent = palette.danger,
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
                    accent = palette.muted,
                ) { vm.navigate(Screen.Settings) }
            }
        }
    }
}
