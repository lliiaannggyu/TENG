package com.tengwear.tengfilemanager.presentation.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
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
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.Screen
import com.tengwear.tengfilemanager.core.theme.SeedTheme
import com.tengwear.tengfilemanager.presentation.ui.common.MenuCard
import com.tengwear.tengfilemanager.presentation.ui.common.SectionHeader
import com.tengwear.tengfilemanager.presentation.ui.common.SeedSwatch

/**
 * 设置页。
 *
 * 这里只放"必须让用户知道/能改"的东西：
 *  · 设备名（局域网里显示给别人看）；
 *  · 设备存储访问状态（默认浏览位置，必须可见、可开启）；
 *  · 已授权目录（SAF 是「只授权某个文件夹」的兜底来源，可见、可撤销）；
 *  · 交互偏好；
 *  · 权限与隐私说明（写清楚全盘权限由用户主动开启、不上传任何文件）。
 *
 * 开关用官方 M3E [SwitchButton]，主题色选择用 [RadioButton]——
 * 都自带按下形变、涟漪和无障碍语义。
 */
@Composable
fun SettingsScreen(vm: FileManagerViewModel) {
    val scheme = MaterialTheme.colorScheme
    val prefs by vm.prefsState.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val storageAccess by vm.storageAccess.collectAsStateWithLifecycle()
    val trashSummary by vm.trashSummary.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()
    var nameInput by remember(prefs.deviceName) { mutableStateOf(prefs.deviceName) }

    LaunchedEffect(Unit) { vm.loadTrash() }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.addTreeLocation(it) }
    }

    ScreenScaffold(scrollState = state, edgeButton = {}) { padding ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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
                        .fillMaxWidth()
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(SeedTheme.entries.size, key = { "seed_${SeedTheme.entries[it].id}" }) { index ->
                val theme = SeedTheme.entries[index]
                RadioButton(
                    selected = prefs.seedTheme == theme,
                    onSelect = { vm.setSeedTheme(theme) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        if (theme == SeedTheme.SYSTEM) {
                            // 跟随系统：直接展示当前生效的主色
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(scheme.primary),
                            )
                        } else {
                            // 其余用该种子色推导出的 tone 30/80 双色圆点
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
                    modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "进入设备页自动发现",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
            }

            item(key = "storageTitle") { SectionHeader("设备存储访问") }
            item(key = "storageState") {
                MenuCard(
                    icon = if (storageAccess) Icons.Filled.Home else Icons.Filled.Lock,
                    title = if (storageAccess) "已开启 · 可浏览全部文件" else "未开启 · 只能看应用私有文件",
                    subtitle = if (storageAccess) "可在系统设置中随时关闭" else "点按前往系统设置开启「所有文件访问权限」",
                    accent = if (storageAccess) MaterialTheme.colorScheme.primary else scheme.error,
                ) {
                    if (storageAccess) vm.openDeviceStorage() else vm.requestAllFilesAccess()
                }
            }

            item(key = "rootsTitle") { SectionHeader("可访问的位置") }
            items(roots.size, key = { "root_${roots[it].id}" }) { index ->
                val location = roots[index]
                when {
                    location.isDeviceStorage -> MenuCard(
                        icon = Icons.Filled.Home,
                        title = "设备存储",
                        subtitle = "全部文件 · 点按进入",
                        accent = MaterialTheme.colorScheme.primary,
                    ) { vm.openDeviceStorage() }

                    location.isSandbox -> MenuCard(
                        icon = Icons.Filled.Lock,
                        title = location.name,
                        subtitle = "应用私有文件（无需权限）",
                        accent = scheme.onSurfaceVariant,
                    ) { vm.openLocationFromOverlay(location) }

                    else -> MenuCard(
                        icon = Icons.Filled.Lock,
                        title = location.name,
                        subtitle = "SAF 授权目录 · 点按移除",
                        accent = scheme.onSurfaceVariant,
                    ) { vm.removeLocation(location) }
                }
            }
            item(key = "addRoot") {
                MenuCard(
                    icon = Icons.Filled.Add,
                    title = "只授权某个文件夹",
                    subtitle = "不想开全盘权限时用，系统选择器仅授权所选目录",
                    accent = MaterialTheme.colorScheme.primary,
                ) { treePicker.launch(null) }
            }

            item(key = "trash") {
                MenuCard(
                    icon = Icons.Filled.Delete,
                    title = "回收站",
                    subtitle = trashSummary,
                    accent = scheme.error,
                ) { vm.navigate(Screen.Trash) }
            }

            item(key = "aboutTitle") { SectionHeader("关于与隐私") }
            item(key = "privacy") {
                Text(
                    text = "· 设备存储需您主动开启「所有文件访问权限」，可随时关闭\n" +
                        "· 不开启时只读写应用私有文件与您主动授权（SAF）的目录\n" +
                        "· 文件不经过任何服务器\n" +
                        "· 局域网传输仅在同一 Wi-Fi 内直连",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "version") {
                Text(
                    text = "TENG 文件管理器 1.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
