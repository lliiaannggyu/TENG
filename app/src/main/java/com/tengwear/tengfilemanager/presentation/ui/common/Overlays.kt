package com.tengwear.tengfilemanager.presentation.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Dialog
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.tengwear.tengfilemanager.core.model.ConfirmSpec
import com.tengwear.tengfilemanager.core.model.OpProgress
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import kotlinx.coroutines.delay

/**
 * 模态覆盖层。
 *
 * 全部用官方 M3E 弹层：[AlertDialog] 做确认，[Dialog] 做进度与轻提示。
 * 官方弹层自带滑动手势关闭、进出场动画和无障碍语义。
 */

@Composable
fun ConfirmOverlay(
    spec: ConfirmSpec,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = spec.confirmLabel, maxLines = 1)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", maxLines = 1)
            }
        },
        icon = if (spec.danger) {
            {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            null
        },
        title = {
            Text(
                text = spec.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (spec.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Text(
                text = spec.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
    )
}

@Composable
fun ProgressOverlay(progress: OpProgress, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Dialog(visible = true, onDismissRequest = onCancel) {
        Column(
            modifier = Modifier.widthIn(max = 180.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (progress.indeterminate) {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 6.dp))
            } else {
                CircularProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            Text(
                text = progress.title,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (progress.indeterminate) {
                    "处理中…"
                } else {
                    "${(progress.fraction * 100).toInt()}%"
                },
                style = MaterialTheme.typography.titleLarge,
                color = scheme.primary,
                maxLines = 1,
            )
            if (progress.currentName.isNotBlank()) {
                Text(
                    text = progress.currentName,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
            if (progress.bytesTotal > 0) {
                Text(
                    text = "${formatBytes(progress.bytesDone)} / ${formatBytes(progress.bytesTotal)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onCancel) {
                Text(text = "取消", maxLines = 1)
            }
        }
    }
}

/** 轻提示。用 M3E Dialog 承载——Wear 上没有 Snackbar，Dialog 是官方的浮层容器。 */
@Composable
fun MessageOverlay(text: String, onDismiss: () -> Unit) {
    LaunchedEffect(text) {
        delay(2400)
        onDismiss()
    }
    Dialog(visible = true, onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .widthIn(max = 180.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
        }
    }
}

@Composable
fun UndoBar(label: String, onUndo: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(label) {
        delay(6000)
        onDismiss()
    }
    Dialog(visible = true, onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 14.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            TextButton(onClick = onUndo) {
                Text(text = "撤销", maxLines = 1)
            }
        }
    }
}

/**
 * 重命名弹层。
 *
 * 默认把原文件名（不含扩展名）填进输入框——手表上改名的典型场景是
 * "改前缀/改后缀"，把扩展名一起选中会导致用户误删掉 .jpg 这种低级错误。
 * 输入框用 foundation 的 [BasicTextField]：M3E 手表端没有 TextField 原语，
 * 外层套 M3E 的容器色，观感与官方一致。
 */
@Composable
fun RenameOverlay(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val stem = currentName.substringBeforeLast('.', currentName)
    val ext = currentName.substringAfterLast('.', "")
    var text by remember(currentName) { mutableStateOf(stem) }

    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val finalName = if (ext.isNotEmpty()) "$text.$ext" else text
                    onConfirm(finalName)
                },
                enabled = text.isNotBlank(),
            ) {
                Text(text = "确定", maxLines = 1)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", maxLines = 1)
            }
        },
        title = { Text(text = "重命名", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(scheme.surfaceContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                if (ext.isNotEmpty()) {
                    Text(
                        text = ".$ext",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/**
 * 手动输入路径。
 *
 * 手表上打字体验很差，所以这一页的定位是"补位"：
 *  · 常用根目录直接给入口卡，一点即达；
 *  · 只有确实需要精确路径时才用输入框（Wear 键盘或语音输入）。
 */
@Composable
fun PathInputScreen(vm: FileManagerViewModel) {
    val scheme = MaterialTheme.colorScheme
    val roots by vm.roots.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(scrollState = state, edgeButton = {}) { padding ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "title") { SectionHeader("输入路径") }
            item(key = "input") {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(scheme.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
            hint?.let { text ->
                item(key = "hint") {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "open") {
                Button(
                    onClick = {
                        when (val result = vm.resolvePath(input)) {
                            is com.tengwear.tengfilemanager.core.fs.StorageGateway.PathResult.Ok -> {
                                hint = null
                                vm.openLocationFromOverlay(result.location)
                            }
                            is com.tengwear.tengfilemanager.core.fs.StorageGateway.PathResult.NeedsGrant ->
                                hint = result.message
                            is com.tengwear.tengfilemanager.core.fs.StorageGateway.PathResult.Invalid ->
                                hint = result.message
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.6f),
                ) {
                    Text(text = "打开", maxLines = 1)
                }
            }
            item(key = "rootsHeader") { SectionHeader("常用位置") }
            items(roots.size, key = { roots[it].id }) { index ->
                val location = roots[index]
                MenuCard(
                    icon = Icons.Filled.Place,
                    title = location.name,
                    accent = scheme.primary,
                ) { vm.openLocationFromOverlay(location) }
            }
        }
    }
}
