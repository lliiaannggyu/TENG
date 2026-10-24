package com.tengwear.tengfilemanager.presentation.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.round.EmptyState
import com.tengwear.tengfilemanager.presentation.ui.round.RoundListRow

/**
 * 搜索页。
 *
 * 关键设计：**输入即搜**（≥2 个字符触发），没有"搜索按钮"。
 * 手表上多一次点按就是多一次误触，而搜索本身是低频重操作，
 * 让它"边打边出结果"比"打完再确认"更省时间。
 *
 * 搜索范围 = 沙盒 + 已授权目录，深度上限 6 层，结果上限 200 条（见 FileRepository.search）。
 */
@Composable
fun SearchScreen(vm: FileManagerViewModel) {
    val palette = LocalTengPalette.current
    val search by vm.search.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()
    var query by remember { mutableStateOf(search.query) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    ScalingLazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "field") {
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.setQuery(it)
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .focusRequester(focus)
                    .clip(RoundedCornerShape(50))
                    .background(palette.card)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        item(key = "status") {
            Text(
                text = when {
                    search.running -> "搜索中… 已扫描 ${search.scanned} 个目录"
                    search.query.length < 2 -> "输入至少 2 个字符"
                    else -> "${search.results.size} 个结果"
                },
                fontSize = 9.sp,
                color = palette.muted,
                maxLines = 1,
            )
        }

        if (search.results.isEmpty() && !search.running && search.query.length >= 2) {
            item(key = "empty") { EmptyState("没有匹配项", "只搜索已授权的目录") }
        }

        items(search.results.size, key = { search.results[it].key }) { index ->
            val entry = search.results[index]
            RoundListRow(
                glyph = entry.kind.badge,
                title = entry.name,
                color = palette.of(entry.kind),
                subtitle = if (entry.isDirectory) "文件夹" else formatBytes(entry.size),
            ) { vm.openEntryAnywhere(entry) }
        }
    }
}
