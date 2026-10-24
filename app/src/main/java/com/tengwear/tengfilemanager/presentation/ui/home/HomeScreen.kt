package com.tengwear.tengfilemanager.presentation.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.basicCurvedText
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.presentation.CollectionType
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.Screen
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.round.RoundListRow

/**
 * 首页。
 *
 * 顶部是 M3E 的时钟组件 [TimeText]（官方 Material 3 Expressive 时钟），
 * 通过自定义 content 放大到 20sp / 100° 弧长，充当表盘式主视觉。
 *
 * 时钟用 M3E 默认档位附近的大小（16sp / 84° 弧）：它是状态信息而不是主视觉，
 * 再大就会和下面的存储数字、列表项抢注意力。
 *
 * 相比上一版去掉了两样东西：
 *  · 背景的 264° 存储进度环 —— 圆屏上环会和列表边缘抢视觉；
 *  · 顶部的 "TENG 文件" 文字 —— 时钟本身就是最合适的顶部标题，
 *    再叠一行品牌名只会把主视觉往下挤。
 *
 * 存储占比仍以数字保留（时钟正下方），信息量不变但不占视觉重心。
 */
@Composable
fun HomeScreen(vm: FileManagerViewModel) {
    val palette = LocalTengPalette.current
    val volume by vm.volume.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val trashItems by vm.trashItems.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()

    val used = volume.first
    val total = volume.second
    val ratio = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f

    Box(modifier = Modifier.fillMaxSize()) {
        // M3E 时钟：贴顶部弧线排布，自带一条背景带，列表从它下面滚过
        HeroClock(modifier = Modifier.align(Alignment.TopCenter))

        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            // 顶部留出时钟带的高度；底部留白让最后一项能完整滚进圆内
            contentPadding = PaddingValues(top = 46.dp, bottom = 48.dp),
            // 关掉自动居中：否则首次滚动会把"存储"那一项重新拽到屏幕中心，视觉上跳一下
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "storage") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (total > 0) "已用 ${(ratio * 100).toInt()}%" else "—",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        text = if (total > 0) "${formatBytes(used)} / ${formatBytes(total)}" else "存储不可用",
                        fontSize = 9.sp,
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
            }

            item(key = "browse") {
                RoundListRow(
                    glyph = "浏",
                    title = "浏览文件",
                    subtitle = roots.firstOrNull()?.name ?: "应用内部存储",
                    color = palette.folder,
                ) { roots.firstOrNull()?.let { vm.openLocation(it) } }
            }

            item(key = "recent") {
                RoundListRow(
                    glyph = "近",
                    title = "最近",
                    subtitle = "刚打开过的文件",
                    color = palette.document,
                ) { vm.navigate(Screen.Collection(CollectionType.RECENTS)) }
            }

            item(key = "fav") {
                RoundListRow(
                    glyph = "藏",
                    title = "收藏",
                    subtitle = "常用文件与目录",
                    color = palette.warning,
                ) { vm.navigate(Screen.Collection(CollectionType.FAVORITES)) }
            }

            item(key = "trash") {
                RoundListRow(
                    glyph = "删",
                    title = "回收站",
                    subtitle = if (trashItems.isEmpty()) "空" else "${trashItems.size} 项可恢复",
                    color = palette.danger,
                ) {
                    vm.loadTrash()
                    vm.navigate(Screen.Trash)
                }
            }

            item(key = "search") {
                RoundListRow(
                    glyph = "搜",
                    title = "搜索",
                    subtitle = "按名称查找",
                    color = palette.image,
                ) { vm.navigate(Screen.Search) }
            }

            item(key = "devices") {
                RoundListRow(
                    glyph = "传",
                    title = "设备",
                    subtitle = "局域网互传",
                    color = palette.video,
                ) { vm.navigate(Screen.Devices) }
            }

            item(key = "settings") {
                RoundListRow(
                    glyph = "设",
                    title = "设置",
                    subtitle = "排序偏好 / 设备名",
                    color = palette.muted,
                ) { vm.navigate(Screen.Settings) }
            }
        }
    }
}

/**
 * M3E 时钟组件。
 *
 * 注意：`TimeText` 的 content 是 `CurvedScope.(String) -> Unit`，**不是 @Composable**，
 * 所以主题色必须在外面读好再捕获进 lambda，不能在 content 里直接读 MaterialTheme。
 */
@Composable
private fun HeroClock(modifier: Modifier = Modifier) {
    val clockColor = MaterialTheme.colorScheme.onBackground
    // backgroundColor 必须是实色：列表会从时钟下面滚过，
    // 透明背景会让文件行直接穿到时钟文字上（实测很糊）。
    val clockBand = MaterialTheme.colorScheme.background
    TimeText(
        modifier = modifier,
        maxSweepAngle = 84f,
        backgroundColor = clockBand,
        content = { time ->
            basicCurvedText(
                text = time,
                style = CurvedTextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = clockColor,
                ),
            )
        },
    )
}
