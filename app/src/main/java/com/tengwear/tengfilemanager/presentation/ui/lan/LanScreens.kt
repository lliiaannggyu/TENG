package com.tengwear.tengfilemanager.presentation.ui.lan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.tengwear.tengfilemanager.core.lan.LanTransfer
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.common.ClockGap
import com.tengwear.tengfilemanager.presentation.ui.common.EmptyState
import com.tengwear.tengfilemanager.presentation.ui.common.MenuCard
import com.tengwear.tengfilemanager.presentation.ui.common.SectionHeader

/**
 * 局域网设备页。
 *
 * 隐私与安全提示直接写在页面上（不藏在二级页）：
 *  · 只显示同一 Wi-Fi 内的同应用设备；
 *  · 接收前必须由本机用户点「接受」，并核对 4 位配对码；
 *  · 文件不经过任何服务器。
 */
@Composable
fun DevicesScreen(vm: FileManagerViewModel) {
    val palette = LocalTengPalette.current
    val scheme = MaterialTheme.colorScheme
    val peers by vm.peers.collectAsStateWithLifecycle()
    val lan by vm.lanState.collectAsStateWithLifecycle()
    val status by vm.discoveryStatus.collectAsStateWithLifecycle()
    val prefs by vm.prefsState.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()

    LaunchedEffect(Unit) { vm.startLan() }

    ScreenScaffold(scrollState = state, edgeButton = {}) { _ ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 44.dp),
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            item(key = "gap") { ClockGap(28.dp) }
            item(key = "title") { SectionHeader("附近设备") }
            item(key = "me") {
                Text(
                    text = "${prefs.deviceName} · ${status}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }

            // 收到传输请求：配对码 + 接受/拒绝
            lan.pending?.let { request ->
                item(key = "pending") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${request.peerName} 想发送 ${request.items.size} 个文件",
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                        Text(
                            text = formatBytes(request.totalBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        // 配对码用 M3E 的展示级字号：这是本页唯一需要"凑近看"的数字
                        Text(
                            text = request.code,
                            style = MaterialTheme.typography.displayMedium,
                            color = scheme.primary,
                            maxLines = 1,
                        )
                        Text(
                            text = "请与对方屏幕上的数字核对",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            maxLines = 1,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.rejectIncoming() }) {
                                Text(text = "拒绝", maxLines = 1)
                            }
                            Button(onClick = { vm.acceptIncoming() }) {
                                Text(text = "接受", maxLines = 1)
                            }
                        }
                    }
                }
            }

            if (peers.isEmpty()) {
                item(key = "empty") {
                    EmptyState("未发现设备", "请确认两台手表连接同一 Wi-Fi，且都已打开本应用")
                }
            }

            items(peers.size, key = { peers[it].id }) { index ->
                val peer = peers[index]
                MenuCard(
                    icon = Icons.Filled.Send,
                    title = peer.name,
                    subtitle = "${peer.host.hostAddress}:${peer.port}",
                    accent = palette.video,
                ) { vm.sendTo(peer) }
            }

            item(key = "privacy") {
                Text(
                    text = "仅局域网直连，文件不经过服务器；接收前需本机确认。",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 160.dp)
                        .padding(top = 6.dp),
                )
            }
            item(key = "stop") {
                TextButton(onClick = { vm.stopLan() }) {
                    Text(text = "关闭发现", maxLines = 1)
                }
            }
        }
    }
}

/**
 * 传输进度页。
 *
 * 大文件传输必须能锁住用户预期：M3E [CircularProgressIndicator] + 当前文件 + 已传/总量 + 取消。
 * 传输由前台服务保活，所以即使息屏、退出这一页，传输也不会中断。
 */
@Composable
fun TransferScreen(vm: FileManagerViewModel) {
    val palette = LocalTengPalette.current
    val scheme = MaterialTheme.colorScheme
    val lan by vm.lanState.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()

    ScreenScaffold(scrollState = state, edgeButton = {}) { _ ->
            ScalingLazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 44.dp),
                autoCentering = null,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                item(key = "gap") { ClockGap(28.dp) }
                item(key = "phase") {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = when (lan.phase) {
                                LanTransfer.Phase.IDLE -> "空闲"
                                LanTransfer.Phase.LISTENING -> "等待连接"
                                LanTransfer.Phase.CONNECTING -> "连接中"
                                LanTransfer.Phase.WAITING_ACCEPT -> "等待对方确认"
                                LanTransfer.Phase.TRANSFERRING ->
                                    if (lan.direction == LanTransfer.Direction.SEND) "发送中" else "接收中"
                                LanTransfer.Phase.VERIFYING -> "校验中"
                                LanTransfer.Phase.DONE -> "完成"
                                LanTransfer.Phase.FAILED -> "失败"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurface,
                        )
                        if (lan.peerName.isNotBlank()) {
                            Text(
                                text = lan.peerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.muted,
                                maxLines = 1,
                            )
                        }
                        if (lan.phase == LanTransfer.Phase.TRANSFERRING ||
                            lan.phase == LanTransfer.Phase.VERIFYING
                        ) {
                            Text(
                                text = "${(lan.fraction * 100).toInt()}%",
                                style = MaterialTheme.typography.displaySmall,
                                color = scheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                }

                if (lan.currentFile.isNotBlank()) {
                    item(key = "file") {
                        Text(
                            text = lan.currentFile,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 150.dp),
                        )
                    }
                }

                if (lan.bytesTotal > 0) {
                    item(key = "bytes") {
                        Text(
                            text = "${formatBytes(lan.bytesDone)} / ${formatBytes(lan.bytesTotal)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            maxLines = 1,
                        )
                    }
                }

                if (lan.message.isNotBlank()) {
                    item(key = "message") {
                        Text(
                            text = lan.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (lan.phase == LanTransfer.Phase.FAILED) {
                                palette.danger
                            } else {
                                palette.success
                            },
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            modifier = Modifier.widthIn(max = 156.dp),
                        )
                    }
                }

                item(key = "actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (lan.phase == LanTransfer.Phase.TRANSFERRING ||
                            lan.phase == LanTransfer.Phase.CONNECTING ||
                            lan.phase == LanTransfer.Phase.VERIFYING
                        ) {
                            TextButton(onClick = { vm.cancelTransfer() }) {
                                Text(text = "取消传输", maxLines = 1)
                            }
                        }
                        if (lan.phase == LanTransfer.Phase.DONE || lan.phase == LanTransfer.Phase.FAILED) {
                            Button(onClick = { vm.openLocationFromOverlay(vm.inboxLocation()) }) {
                                Text(text = "查看接收目录", maxLines = 1)
                            }
                            TextButton(
                                onClick = {
                                    vm.resetTransfer()
                                    vm.back()
                                },
                            ) {
                                Text(text = "关闭", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
