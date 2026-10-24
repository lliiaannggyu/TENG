package com.tengwear.tengfilemanager.presentation.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.round.RoundListRow
import com.tengwear.tengfilemanager.presentation.ui.round.RoundTextAction

/**
 * 设置页。
 *
 * 这里只放"必须让用户知道/能改"的东西：
 *  · 设备名（局域网里显示给别人看）；
 *  · 已授权目录（SAF 授权是本应用唯一的文件访问来源，必须可见、可撤销）；
 *  · 交互偏好（边缘滚动轨 = 无表冠设备的替代交互，可关）；
 *  · 权限与隐私说明（写清楚"不申请全盘权限""不上传任何文件"）。
 */
@Composable
fun SettingsScreen(vm: FileManagerViewModel) {
    val palette = LocalTengPalette.current
    val prefs by vm.prefsState.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val trashSummary by vm.trashSummary.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()
    var nameInput by remember(prefs.deviceName) { mutableStateOf(prefs.deviceName) }

    LaunchedEffect(Unit) { vm.loadTrash() }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.addTreeLocation(it) }
    }

    ScalingLazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "title") {
            Text(text = "设置", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground)
        }

        item(key = "nameLabel") {
            Text(text = "设备名（局域网可见）", fontSize = 9.sp, color = palette.muted)
        }
        item(key = "nameField") {
            BasicTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .clip(RoundedCornerShape(50))
                    .background(palette.card)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        item(key = "nameSave") {
            RoundTextAction(
                text = "保存",
                onClick = { vm.setDeviceName(nameInput.ifBlank { "TENG 手表" }) },
                container = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                enabled = nameInput.isNotBlank() && nameInput != prefs.deviceName,
            )
        }

        item(key = "rail") {
            RoundListRow(
                glyph = "轨",
                title = "边缘滚动轨",
                color = palette.image,
                subtitle = "无表冠设备可点按弧线快速定位",
                trailing = if (prefs.showScrollRail) "开" else "关",
                selected = prefs.showScrollRail,
            ) { vm.setShowScrollRail(!prefs.showScrollRail) }
        }

        item(key = "lan") {
            RoundListRow(
                glyph = "局",
                title = "进入设备页自动发现",
                color = palette.video,
                subtitle = "关闭后需手动开启局域网搜索",
                trailing = if (prefs.lanDiscoveryEnabled) "开" else "关",
                selected = prefs.lanDiscoveryEnabled,
            ) { vm.setLanDiscoveryDefault(!prefs.lanDiscoveryEnabled) }
        }

        item(key = "rootsTitle") {
            Text(text = "已授权目录", fontSize = 9.sp, color = palette.muted, modifier = Modifier.padding(top = 4.dp))
        }
        items(roots.size, key = { "root_${roots[it].id}" }) { index ->
            val location = roots[index]
            RoundListRow(
                glyph = if (location.isSandbox) "沙" else "授",
                title = location.name,
                color = if (location.isSandbox) palette.folder else palette.document,
                subtitle = if (location.isSandbox) "应用沙盒（无需权限）" else "SAF 授权目录",
                trailing = if (location.isSandbox) null else "移除",
            ) {
                if (location.isSandbox) {
                    vm.openLocationFromOverlay(location)
                } else {
                    vm.removeLocation(location)
                }
            }
        }
        item(key = "addRoot") {
            RoundListRow("加", "添加文件夹授权", palette.success, subtitle = "系统文件选择器，仅授权所选目录") {
                treePicker.launch(null)
            }
        }

        item(key = "trash") {
            RoundListRow("删", "回收站", palette.danger, subtitle = trashSummary) {
                vm.navigate(com.tengwear.tengfilemanager.presentation.Screen.Trash)
            }
        }

        item(key = "aboutTitle") {
            Text(text = "关于与隐私", fontSize = 9.sp, color = palette.muted, modifier = Modifier.padding(top = 6.dp))
        }
        item(key = "privacy") {
            Text(
                text = "· 不申请全盘存储权限\n" +
                    "· 只读写应用沙盒与您主动授权的目录\n" +
                    "· 文件不经过任何服务器\n" +
                    "· 局域网传输仅在同一 Wi-Fi 内直连",
                fontSize = 8.sp,
                color = palette.muted,
                textAlign = TextAlign.Start,
                lineHeight = 11.sp,
                modifier = Modifier.fillMaxWidth(0.84f),
            )
        }
        item(key = "version") {
            Text(text = "TENG 文件管理器 1.0", fontSize = 8.sp, color = palette.muted.copy(alpha = 0.6f))
        }
    }
}
