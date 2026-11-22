package com.tengwear.tengfilemanager.presentation.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.ui.common.EmptyState
import com.tengwear.tengfilemanager.presentation.ui.common.FileCard
import com.tengwear.tengfilemanager.presentation.ui.common.SectionHeader

/**
 * 搜索页。
 *
 * 关键设计：**输入即搜**（≥2 个字符触发），没有"搜索按钮"。
 * 手表上多一次点按就是多一次误触，而搜索本身是低频重操作，
 * 让它"边打边出结果"比"打完再确认"更省时间。
 *
 * 搜索范围 = 沙盒 + 已授权目录，深度上限 6 层，结果上限 200 条（见 FileRepository.search）。
 * 输入框是 foundation 的 `BasicTextField`（M3E 手表端没有 TextField 原语），
 * 结果行复用浏览页的 M3E `Card`。
 */
@Composable
fun SearchScreen(vm: FileManagerViewModel) {
    val scheme = MaterialTheme.colorScheme
    val search by vm.search.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()
    var query by remember { mutableStateOf(search.query) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    ScreenScaffold(scrollState = state, edgeButton = {}) { padding ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "title") { SectionHeader("搜索") }

            item(key = "field") {
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        vm.setQuery(it)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .clip(RoundedCornerShape(50))
                        .background(scheme.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }

            item(key = "status") {
                Text(
                    text = when {
                        search.running -> "搜索中… 已扫描 ${search.scanned} 个目录"
                        search.query.length < 2 -> "输入至少 2 个字符"
                        else -> "${search.results.size} 个结果"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            if (search.results.isEmpty() && !search.running && search.query.length >= 2) {
                item(key = "empty") { EmptyState("没有匹配项", "只搜索已授权的目录") }
            }

            items(search.results.size, key = { search.results[it].key }) { index ->
                val entry = search.results[index]
                FileCard(
                    entry = entry,
                    onClick = { vm.openEntryAnywhere(entry) },
                    secondary = if (entry.isDirectory) "文件夹" else formatBytes(entry.size),
                )
            }
        }
    }
}
