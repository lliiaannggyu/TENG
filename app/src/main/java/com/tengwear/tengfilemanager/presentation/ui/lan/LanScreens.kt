package com.tengwear.tengfilemanager.presentation.ui.lan

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.lan.LanTransfer
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.round.EmptyState
import com.tengwear.tengfilemanager.presentation.ui.round.RingArc
import com.tengwear.tengfilemanager.presentation.ui.round.RoundListRow
import com.tengwear.tengfilemanager.presentation.ui.round.RoundTextAction

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
    val peers by vm.peers.collectAsStateWithLifecycle()
    val lan by vm.lanState.collectAsStateWithLifecycle()
    val status by vm.discoveryStatus.collectAsStateWithLifecycle()
    val prefs by vm.prefsState.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()

    LaunchedEffect(Unit) { vm.startLan() }

    ScalingLazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "title") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "附近设备", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium)
                Text(text = prefs.deviceName, fontSize = 9.sp, color = palette.muted, maxLines = 1)
                Text(text = status, fontSize = 8.sp, color = palette.muted.copy(alpha = 0.75f), maxLines = 2, textAlign = TextAlign.Center)
            }
        }

        // 收到传输请求：配对码 + 接受/拒绝
        lan.pending?.let { request ->
            item(key = "pending") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.cardHighlight)
                        .padding(vertical = 8.dp, horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = "${request.peerName} 想发送 ${request.items.size} 个文件",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                    Text(text = formatBytes(request.totalBytes), fontSize = 9.sp, color = palette.muted)
                    Text(
                        text = request.code,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    Text(text = "请与对方屏幕上的数字核对", fontSize = 8.sp, color = palette.muted, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoundTextAction(text = "拒绝", onClick = { vm.rejectIncoming() }, container = palette.card)
                        RoundTextAction(
                            text = "接受",
                            onClick = { vm.acceptIncoming() },
                            container = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
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
            RoundListRow(
                glyph = "机",
                title = peer.name,
                color = palette.video,
                subtitle = "${peer.host.hostAddress}:${peer.port}",
                trailing = "发送",
            ) { vm.sendTo(peer) }
        }

        item(key = "privacy") {
            Text(
                text = "仅局域网直连，文件不经过服务器；接收前需本机确认。",
                fontSize = 8.sp,
                color = palette.muted.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                lineHeight = 11.sp,
                modifier = Modifier.widthIn(max = 150.dp).padding(top = 6.dp),
            )
        }
        item(key = "stop") {
            RoundTextAction(text = "关闭发现", onClick = { vm.stopLan() }, container = palette.card)
        }
    }
}

/**
 * 传输进度页。
 *
 * 大文件传输必须能锁住用户预期：环形进度 + 当前文件 + 已传/总量 + 取消。
 * 传输由前台服务保活，所以即使息屏、退出这一页，传输也不会中断。
 */
@Composable
fun TransferScreen(vm: FileManagerViewModel) {
    val palette = LocalTengPalette.current
    val lan by vm.lanState.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (lan.phase == LanTransfer.Phase.TRANSFERRING || lan.phase == LanTransfer.Phase.VERIFYING) {
            RingArc(progress = lan.fraction, radiusRatio = 0.82f, strokeWidth = 3.dp)
        }
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "phase") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when (lan.phase) {
                            LanTransfer.Phase.IDLE -> "空闲"
                            LanTransfer.Phase.LISTENING -> "等待连接"
                            LanTransfer.Phase.CONNECTING -> "连接中"
                            LanTransfer.Phase.WAITING_ACCEPT -> "等待对方确认"
                            LanTransfer.Phase.TRANSFERRING -> if (lan.direction == LanTransfer.Direction.SEND) "发送中" else "接收中"
                            LanTransfer.Phase.VERIFYING -> "校验中"
                            LanTransfer.Phase.DONE -> "完成"
                            LanTransfer.Phase.FAILED -> "失败"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Medium,
                    )
                    if (lan.peerName.isNotBlank()) {
                        Text(text = lan.peerName, fontSize = 9.sp, color = palette.muted, maxLines = 1)
                    }
                    if (lan.phase == LanTransfer.Phase.TRANSFERRING || lan.phase == LanTransfer.Phase.VERIFYING) {
                        Text(
                            text = "${(lan.fraction * 100).toInt()}%",
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (lan.currentFile.isNotBlank()) {
                item(key = "file") {
                    Text(
                        text = lan.currentFile,
                        fontSize = 9.sp,
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
                        fontSize = 8.5.sp,
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
            }

            if (lan.message.isNotBlank()) {
                item(key = "message") {
                    Text(
                        text = lan.message,
                        fontSize = 9.sp,
                        color = if (lan.phase == LanTransfer.Phase.FAILED) palette.danger else palette.success,
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
                        RoundTextAction(
                            text = "取消传输",
                            onClick = { vm.cancelTransfer() },
                            container = palette.danger.copy(alpha = 0.22f),
                            contentColor = palette.danger,
                        )
                    }
                    if (lan.phase == LanTransfer.Phase.DONE || lan.phase == LanTransfer.Phase.FAILED) {
                        RoundTextAction(
                            text = "查看接收目录",
                            onClick = { vm.openLocationFromOverlay(vm.inboxLocation()) },
                            container = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        RoundTextAction(text = "关闭", onClick = { vm.resetTransfer(); vm.back() }, container = palette.card)
                    }
                }
            }
        }
    }
}
