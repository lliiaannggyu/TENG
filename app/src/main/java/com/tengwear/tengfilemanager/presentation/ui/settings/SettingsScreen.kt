package com.tengwear.tengfilemanager.presentation.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.RadioButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.R
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.Screen
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.common.ClockGap
import com.tengwear.tengfilemanager.presentation.ui.common.MenuCard
import com.tengwear.tengfilemanager.core.theme.SeedTheme
import com.tengwear.tengfilemanager.presentation.ui.common.SectionHeader
import com.tengwear.tengfilemanager.presentation.ui.common.SeedSwatch

/**
 * 设置页。
 *
 * 这里只放"必须让用户知道/能改"的东西：
 *  · 设备名（局域网里显示给别人看）；
 *  · 已授权目录（SAF 授权是本应用唯一的文件访问来源，必须可见、可撤销）；
 *  · 交互偏好；
 *  · 权限与隐私说明（写清楚"不申请全盘权限""不上传任何文件"）。
 *
 * 两个开关用官方 M3E [SwitchButton]——它自带按下形变和触感反馈，
 * 之前用"列表行 + 右侧文字开/关"的写法既不可点区域不明确，也没有状态语义。
 */
@Composable
fun SettingsScreen(vm: FileManagerViewModel) {
    val palette = LocalTengPalette.current
    val scheme = MaterialTheme.colorScheme
    val prefs by vm.prefsState.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val trashSummary by vm.trashSummary.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()
    var nameInput by remember(prefs.deviceName) { mutableStateOf(prefs.deviceName) }

    LaunchedEffect(Unit) { vm.loadTrash() }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.addTreeLocation(it) }
    }

    ScreenScaffold(scrollState = state, edgeButton = {}) { _ ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 44.dp),
            autoCentering = null,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "gap") { ClockGap(26.dp) }
            item(key = "title") { SectionHeader("设置") }

            item(key = "nameLabel") { SectionHeader("设备名（局域网可见）") }
            item(key = "nameField") {
                BasicTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .clip(RoundedCornerShape(50))
                        .background(scheme.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
            item(key = "nameSave") {
                Button(
                    onClick = { vm.setDeviceName(nameInput.ifBlank { "TENG 手表" }) },
                    enabled = nameInput.isNotBlank() && nameInput != prefs.deviceName,
                    modifier = Modifier.fillMaxWidth(0.5f),
                ) {
                    Text(text = "保存", maxLines = 1)
                }
            }

            // ---------------- 主题色（种子色）----------------
            item(key = "themeTitle") { SectionHeader("主题色") }
            item(key = "themeHint") {
                Text(
                    text = "Material 3 色阶：每个种子色推导出完整的主/次/中性色阶",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.86f),
                )
            }
            items(SeedTheme.entries.size, key = { "seed_${SeedTheme.entries[it].id}" }) { index ->
                val theme = SeedTheme.entries[index]
                RadioButton(
                    selected = prefs.seedTheme == theme,
                    onSelect = { vm.setSeedTheme(theme) },
                    modifier = Modifier.fillMaxWidth(0.86f),
                    icon = {
                        // SYSTEM 用系统主色，其余用该种子色推导出的 tone 30/80 双色圆点
                        if (theme == SeedTheme.SYSTEM) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_content_paste),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        } else {
                            SeedSwatch(seedArgb = theme.seedArgb)
                        }
                    },
                ) {
                    Text(
                        text = theme.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                }
            }

            item(key = "prefsTitle") { SectionHeader("交互偏好") }
            item(key = "rail") {
                SwitchButton(
                    checked = prefs.showScrollRail,
                    onCheckedChange = { vm.setShowScrollRail(it) },
                    modifier = Modifier.fillMaxWidth(0.86f),
                ) {
                    Text(
                        text = "滚动指示器",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
            }
            item(key = "lan") {
                SwitchButton(
                    checked = prefs.lanDiscoveryEnabled,
                    onCheckedChange = { vm.setLanDiscoveryDefault(it) },
                    modifier = Modifier.fillMaxWidth(0.86f),
                ) {
                    Text(
                        text = "进入设备页自动发现",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
            }

            item(key = "rootsTitle") { SectionHeader("已授权目录") }
            items(roots.size, key = { "root_${roots[it].id}" }) { index ->
                val location = roots[index]
                MenuCard(
                    icon = if (location.isSandbox) {
                        Icons.Filled.Home
                    } else {
                        Icons.Filled.Lock
                    },
                    title = location.name,
                    subtitle = if (location.isSandbox) "应用沙盒（无需权限）" else "SAF 授权目录 · 点按移除",
                    accent = if (location.isSandbox) palette.folder else palette.document,
                ) {
                    if (location.isSandbox) {
                        vm.openLocationFromOverlay(location)
                    } else {
                        vm.removeLocation(location)
                    }
                }
            }
            item(key = "addRoot") {
                MenuCard(
                    icon = Icons.Filled.Add,
                    title = "添加文件夹授权",
                    subtitle = "系统文件选择器，仅授权所选目录",
                    accent = palette.success,
                ) { treePicker.launch(null) }
            }

            item(key = "trash") {
                MenuCard(
                    icon = Icons.Filled.Delete,
                    title = "回收站",
                    subtitle = trashSummary,
                    accent = palette.danger,
                ) { vm.navigate(Screen.Trash) }
            }

            item(key = "aboutTitle") { SectionHeader("关于与隐私") }
            item(key = "privacy") {
                Text(
                    text = "· 不申请全盘存储权限\n" +
                        "· 只读写应用沙盒与您主动授权的目录\n" +
                        "· 文件不经过任何服务器\n" +
                        "· 局域网传输仅在同一 Wi-Fi 内直连",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(0.86f),
                )
            }
            item(key = "version") {
                Text(
                    text = "TENG 文件管理器 1.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted.copy(alpha = 0.6f),
                )
            }
        }
    }
}
