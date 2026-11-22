package com.tengwear.tengfilemanager.presentation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
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
 * 骨架是官方 [AppScaffold]：它负责全屏背景、[TimeText] 的显示与滚动隐藏、
 * 页面切换的缩放转场。时钟用官方 [TimeText] 的**默认参数**——
 * 字号、弧长、背景都是 M3E 官方 token，不做任何压缩。
 * 列表与时钟的避让由 [androidx.wear.compose.material3.ScreenScaffold]
 * 提供的 contentPadding 完成，那是官方的避让机制。
 *
 * 三层结构：
 *  1. 底层：当前页面（[AnimatedContent] 带方向感的滑入滑出 + 缩放 + 淡入淡出，
 *     与官方 SwipeDismissableNavHost 的默认转场一致）；
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

    AppScaffold(timeText = { TimeText() }) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                // 进页向右滑入、退页向左滑入，配合官方 AppScaffold 的缩放转场
                val forward = targetState.depthOf() >= initialState.depthOf()
                val dir = if (forward) 1 else -1
                (
                    slideInHorizontally(tween(260)) { w -> dir * w / 3 } +
                        fadeIn(tween(220)) +
                        scaleIn(tween(260), initialScale = 0.92f)
                    ) togetherWith
                    (
                        slideOutHorizontally(tween(260)) { w -> -dir * w / 3 } +
                            fadeOut(tween(200)) +
                            scaleOut(tween(260), targetScale = 0.92f)
                        )
            },
            label = "screen",
        ) { screen ->
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

/** 屏幕在返回栈里的深度，用于判断转场方向。 */
private fun Screen.depthOf(): Int = when (this) {
    is Screen.Home -> 0
    is Screen.Browse -> 1
    else -> 2
}
