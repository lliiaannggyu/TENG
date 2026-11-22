package com.tengwear.tengfilemanager.presentation.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil.compose.AsyncImage
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.model.FileKind
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.core.util.formatDuration
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.ui.common.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置查看器分发。
 *
 * 统一原则：**能预览，不修改**。
 *  · 图片：支持双指缩放/拖动，但没有任何"编辑/裁剪/旋转"入口；
 *  · 文本：只读，最多预览 200KB；
 *  · 音视频：只播放，不写回文件；
 *  · PDF：逐页渲染为位图，不依赖第三方库（PdfRenderer 是系统能力，零依赖）；
 *  · 压缩包 / 安装包：只读信息，写操作（解压/安装）必须回到显式按钮。
 *
 * 排版全部用 M3E 官方档位（最小 labelSmall），不再手填 7~9sp 的私设字号。
 */
@Composable
fun ViewerScreen(vm: FileManagerViewModel, entry: FileEntry, siblings: List<FileEntry>) {
    when (entry.kind) {
        FileKind.IMAGE -> ImageViewer(entry)
        FileKind.VIDEO -> MediaViewer(entry, isVideo = true)
        FileKind.AUDIO -> MediaViewer(entry, isVideo = false)
        FileKind.DOCUMENT -> if (entry.extension == "pdf") PdfViewer(vm, entry) else TextViewer(vm, entry)
        else -> TextViewer(vm, entry)
    }
}

// ---------------------------------------------------------------- 图片

@Composable
private fun ImageViewer(entry: FileEntry) {
    var scale by remember(entry.key) { mutableFloatStateOf(1f) }
    var offset by remember(entry.key) { mutableStateOf(Offset.Zero) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = entry.uri,
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .pointerInput(entry.key) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        // 放大后才允许拖动，缩小状态下的拖动会与"返回"手势打架
                        if (scale > 1.02f) offset += pan
                        else offset = Offset.Zero
                    }
                },
        )
        Text(
            text = "${entry.name} · ${formatBytes(entry.size)}",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp)
                .widthIn(max = 150.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

// ---------------------------------------------------------------- 音视频

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun MediaViewer(entry: FileEntry, isVideo: Boolean) {
    val context = LocalContext.current
    val player = remember(entry.key) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(entry.uri))
            prepare()
            playWhenReady = true
        }
    }
    var duration by remember { mutableStateOf(0L) }
    var position by remember { mutableStateOf(0L) }

    DisposableEffect(entry.key) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                duration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(entry.key) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            kotlinx.coroutines.delay(500)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isVideo) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(0.92f),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        controllerShowTimeoutMs = 0
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    }
                },
            )
        } else {
            Text(
                text = entry.name,
                modifier = Modifier.widthIn(max = 156.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
            Text(
                text = "${formatDuration(position)} / ${formatDuration(duration)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactButton(
                    onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) },
                ) {
                    Text(text = "−10s", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                FilledTonalButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                ) {
                    Text(
                        text = if (player.isPlaying) "暂停" else "播放",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
                CompactButton(
                    onClick = { player.seekTo(player.currentPosition + 10_000) },
                ) {
                    Text(text = "+10s", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 文本

@Composable
private fun TextViewer(vm: FileManagerViewModel, entry: FileEntry) {
    val text by produceState(initialValue = "读取中…", entry.key) {
        value = vm.readTextPreview(entry)
    }
    val lines = remember(text) { text.split('\n') }
    val state = rememberScalingLazyListState()

    ScalingLazyColumn(
        state = state,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "head") {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
        items(lines.size.coerceAtMost(400), key = { "line_$it" }) { index ->
            Text(
                text = lines[index].ifBlank { " " },
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 3,
                softWrap = true,
            )
        }
        if (lines.size > 400) {
            item(key = "more") {
                Text(
                    text = "… 仅显示前 400 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- PDF

@Composable
private fun PdfViewer(vm: FileManagerViewModel, entry: FileEntry) {
    val pfd = remember(entry.key) { vm.openFileDescriptor(entry.uri) }
    val renderer = remember(entry.key) { pfd?.let { runCatching { PdfRenderer(it) }.getOrNull() } }
    val pageCount = renderer?.pageCount ?: 0

    DisposableEffect(entry.key) {
        onDispose {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }

    if (renderer == null || pageCount == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState("无法打开 PDF", "文件可能已损坏或受密码保护")
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { pageCount })
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val bitmap by produceState<Bitmap?>(initialValue = null, page, entry.key) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        // PdfRenderer 不是线程安全的：同一时刻只允许一个页面在渲染
                        synchronized(renderer) {
                            renderer.openPage(page).use { pdfPage ->
                                val width = 320
                                val height = (width * pdfPage.height.toFloat() / pdfPage.width).toInt().coerceAtLeast(1)
                                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp
                            }
                        }
                    }.getOrNull()
                }
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                bitmap?.let {
                    androidx.compose.foundation.Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "第 ${page + 1} 页",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(0.9f),
                    )
                } ?: Text(
                    text = "渲染中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "${pagerState.currentPage + 1} / $pageCount · 左右滑动翻页",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 22.dp),
        )
    }
}
