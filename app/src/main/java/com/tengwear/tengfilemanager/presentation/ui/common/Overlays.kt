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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.model.ConfirmSpec
import com.tengwear.tengfilemanager.core.model.OpProgress
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.round.RingArc
import com.tengwear.tengfilemanager.presentation.ui.round.RoundListRow
import com.tengwear.tengfilemanager.presentation.ui.round.RoundTextAction
import kotlinx.coroutines.delay

/**
 * 模态覆盖层。
 *
 * 全部用"圆形友好"的排版：内容居中、最大宽度 168dp、不贴边、按钮不小于 40dp 高。
 * 不做卡片阴影/浮层——圆屏上多层半透明叠在一起会糊成一片，只用一个压暗的整圆底。
 */

@Composable
fun ConfirmOverlay(
    spec: ConfirmSpec,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalTengPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 172.dp)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = spec.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (spec.danger) palette.danger else MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Text(
                text = spec.message,
                fontSize = 10.sp,
                color = palette.muted,
                textAlign = TextAlign.Center,
                maxLines = 5,
                lineHeight = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundTextAction(text = "取消", onClick = onDismiss, container = palette.card)
                RoundTextAction(
                    text = spec.confirmLabel,
                    onClick = onConfirm,
                    container = if (spec.danger) palette.danger.copy(alpha = 0.24f) else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (spec.danger) palette.danger else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
fun ProgressOverlay(progress: OpProgress, onCancel: () -> Unit) {
    val palette = LocalTengPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        RingArc(
            progress = progress.fraction,
            indeterminate = progress.indeterminate,
            radiusRatio = 0.72f,
            strokeWidth = 3.dp,
        )
        Column(
            modifier = Modifier.widthIn(max = 150.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = progress.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
            Text(
                text = if (progress.indeterminate) "处理中…" else "${(progress.fraction * 100).toInt()}%",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            if (progress.currentName.isNotBlank()) {
                Text(
                    text = progress.currentName,
                    fontSize = 9.sp,
                    color = palette.muted,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
            if (progress.bytesTotal > 0) {
                Text(
                    text = "${formatBytes(progress.bytesDone)} / ${formatBytes(progress.bytesTotal)}",
                    fontSize = 9.sp,
                    color = palette.muted,
                    maxLines = 1,
                )
            }
            RoundTextAction(text = "取消", onClick = onCancel, container = palette.card)
        }
    }
}

@Composable
fun MessageOverlay(text: String, onDismiss: () -> Unit) {
    LaunchedEffect(text) {
        delay(2400)
        onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 34.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .widthIn(max = 176.dp)
                .clip(RoundedCornerShape(50))
                .background(LocalTengPalette.current.card)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
    }
}

@Composable
fun UndoBar(label: String, onUndo: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(label) {
        delay(6000)
        onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 30.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(LocalTengPalette.current.cardHighlight)
                .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = label, fontSize = 9.sp, color = LocalTengPalette.current.muted, maxLines = 1)
            RoundTextAction(
                text = "撤销",
                onClick = onUndo,
                container = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * 重命名弹层。
 *
 * 默认把原文件名（不含扩展名）填进输入框并选中——手表上改名的典型场景是
 * "改前缀/改后缀"，把扩展名一起选中会导致用户误删掉 .jpg 这种低级错误。
 */
@Composable
fun RenameOverlay(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalTengPalette.current
    val stem = currentName.substringBeforeLast('.', currentName)
    val ext = currentName.substringAfterLast('.', "")
    var text by remember(currentName) { mutableStateOf(stem) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 168.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "重命名", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(50))
                    .background(palette.card)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
            if (ext.isNotEmpty()) {
                Text(text = ".$ext", fontSize = 8.sp, color = palette.muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundTextAction(text = "取消", onClick = onDismiss, container = palette.card)
                RoundTextAction(
                    text = "确定",
                    onClick = {
                        val finalName = if (ext.isNotEmpty()) "$text.$ext" else text
                        onConfirm(finalName)
                    },
                    container = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    enabled = text.isNotBlank(),
                )
            }
        }
    }
}

/**
 * 手动输入路径。
 *
 * 手表上打字体验很差，所以这一页的定位是"补位"：
 *  · 常用根目录直接给快捷胶囊，一点即达；
 *  · 只有确实需要精确路径时才用输入框（Wear 键盘或语音输入）。
 */
@Composable
fun PathInputScreen(vm: FileManagerViewModel) {
    val palette = LocalTengPalette.current
    val roots by vm.roots.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    ScalingLazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(text = "输入路径", fontSize = 11.sp, color = palette.muted)
        }
        item {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .clip(RoundedCornerShape(50))
                    .background(palette.card)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        hint?.let { text ->
            item {
                Text(text = text, fontSize = 9.sp, color = palette.warning, textAlign = TextAlign.Center, maxLines = 3)
            }
        }
        item {
            RoundTextAction(
                text = "打开",
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
                container = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        item {
            Text(text = "常用位置", fontSize = 9.sp, color = palette.muted)
        }
        items(roots.size, key = { roots[it].id }) { index ->
            val location = roots[index]
            RoundListRow(
                glyph = "位",
                title = location.name,
                color = palette.folder,
            ) { vm.openLocationFromOverlay(location) }
        }
    }
}
