package com.tengwear.tengfilemanager.presentation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.Screen
import com.tengwear.tengfilemanager.presentation.ui.browse.BrowseScreen
import com.tengwear.tengfilemanager.presentation.ui.common.ConfirmOverlay
import com.tengwear.tengfilemanager.presentation.ui.common.MessageOverlay
import com.tengwear.tengfilemanager.presentation.ui.common.PathInputScreen
import com.tengwear.tengfilemanager.presentation.ui.common.ProgressOverlay
import com.tengwear.tengfilemanager.presentation.ui.common.RenameOverlay
import com.tengwear.tengfilemanager.presentation.ui.common.UndoBar
import com.tengwear.tengfilemanager.presentation.ui.home.HomeScreen
import com.tengwear.tengfilemanager.presentation.ui.lan.DevicesScreen
import com.tengwear.tengfilemanager.presentation.ui.lan.TransferScreen
import com.tengwear.tengfilemanager.presentation.ui.search.SearchScreen
import com.tengwear.tengfilemanager.presentation.ui.settings.SettingsScreen
import com.tengwear.tengfilemanager.presentation.ui.sheet.QuickMenuScreen
import com.tengwear.tengfilemanager.presentation.ui.trash.CollectionScreen
import com.tengwear.tengfilemanager.presentation.ui.trash.TrashScreen
import com.tengwear.tengfilemanager.presentation.ui.viewer.ApkScreen
import com.tengwear.tengfilemanager.presentation.ui.viewer.ArchiveScreen
import com.tengwear.tengfilemanager.presentation.ui.viewer.ViewerScreen

/**
 * 应用外壳。
 *
 * 骨架是官方 [AppScaffold]：它负责全屏背景、时钟的唯一来源（[TimeText]）
 * 以及页面切换时的缩放转场。时钟只在这一处声明一次，所有页面共享——
 * 之前每页各写一遍时钟的写法既重复又容易和列表内容重叠。
 *
 * 三层结构：
 *  1. 底层：当前页面（Crossfade 切换，200ms，避免圆屏上的滑动转场造成眩晕）；
 *  2. 中层：模态覆盖（确认 / 进度 / 消息 / 撤销条），它们不进入页面栈，
 *     因此"返回"不会误把进度条当成一页；
 *  3. 顶层：全局返回拦截——先退出选择态，再关弹层，最后逐级出栈。
 */
@Composable
fun FileManagerApp(vm: FileManagerViewModel, onExit: () -> Unit) {
    val stack by vm.stack.collectAsStateWithLifecycle()
    val confirm by vm.confirm.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val renameTarget by vm.renameTarget.collectAsStateWithLifecycle()
    val current = stack.last()

    BackHandler {
        if (!vm.back()) onExit()
    }

    AppScaffold {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Crossfade(targetState = current, label = "screen") { screen ->
                when (screen) {
                    is Screen.Home -> HomeScreen(vm)
                    is Screen.Browse -> BrowseScreen(vm, screen.location)
                    is Screen.Search -> SearchScreen(vm)
                    is Screen.Trash -> TrashScreen(vm)
                    is Screen.Collection -> CollectionScreen(vm, screen.type)
                    is Screen.Devices -> DevicesScreen(vm)
                    is Screen.Settings -> SettingsScreen(vm)
                    is Screen.QuickMenu -> QuickMenuScreen(vm)
                    is Screen.PathInput -> PathInputScreen(vm)
                    is Screen.TransferProgress -> TransferScreen(vm)
                    is Screen.Viewer -> ViewerScreen(vm, screen.entry, screen.siblings)
                    is Screen.ArchiveBrowse -> ArchiveScreen(vm, screen.entry)
                    is Screen.ApkDetails -> ApkScreen(vm, screen.entry)
                }
            }

            // 模态层：不参与页面栈，关闭后回到原页面
            progress?.let { ProgressOverlay(progress = it, onCancel = vm::cancelOperation) }
            confirm?.let {
                ConfirmOverlay(spec = it, onConfirm = vm::confirmAction, onDismiss = vm::dismissConfirm)
            }
            message?.let { MessageOverlay(text = it, onDismiss = vm::consumeMessage) }
            undo?.let { UndoBar(label = it.label, onUndo = vm::performUndo, onDismiss = vm::dismissUndo) }
            renameTarget?.let { entry ->
                RenameOverlay(
                    currentName = entry.name,
                    onConfirm = { newName -> vm.performRename(entry, newName) },
                    onDismiss = vm::dismissRename,
                )
            }
        }
    }
}
