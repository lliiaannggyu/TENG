package com.tengwear.tengfilemanager.presentation.ui.viewer

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.archive.ArchiveEngine
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.presentation.FileManagerViewModel
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import com.tengwear.tengfilemanager.presentation.ui.common.EmptyState
import com.tengwear.tengfilemanager.presentation.ui.common.InfoRow
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TextButton

/**
 * 压缩包浏览。
 *
 * 只读：可以看内容、可以解压，但不能"在包内改名/删除"——
 * 手表上做增量修改需要整包重写，一次中断就会毁掉原包，风险收益完全不划算。
 */
@Composable
fun ArchiveScreen(vm: FileManagerViewModel, entry: FileEntry) {
    val palette = LocalTengPalette.current
    val info by vm.archiveInfo.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()
    var password by remember { mutableStateOf("") }
    var passwordRequested by remember { mutableStateOf(false) }

    LaunchedEffect(entry.key) { vm.inspectArchive(entry) }

    ScreenScaffold(scrollState = state, edgeButton = {}) { _ ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 44.dp),
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
        item(key = "gap") { com.tengwear.tengfilemanager.presentation.ui.common.ClockGap(28.dp) }
        item(key = "head") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
                Text(text = formatBytes(entry.size), fontSize = 8.sp, color = palette.muted)
            }
        }

        when (val result = info) {
            null -> item(key = "loading") { EmptyState("读取压缩包…") }

            is ArchiveEngine.InspectResult.NeedsPassword -> {
                item(key = "pwdHint") {
                    Text(
                        text = "该压缩包已加密，请输入密码",
                        fontSize = 9.sp,
                        color = palette.warning,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
                item(key = "pwdField") {
                    BasicTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .clip(RoundedCornerShape(50))
                            .background(palette.card)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                item(key = "pwdOk") {
                    Button(
                        onClick = {
                            passwordRequested = true
                            vm.inspectArchive(entry, password)
                        },
                        modifier = Modifier.fillMaxWidth(0.5f),
                    ) {
                        Text(text = "解锁", maxLines = 1)
                    }
                }
                if (passwordRequested) {
                    item(key = "pwdFail") {
                        Text(text = "密码错误或包已损坏，请重试", fontSize = 8.sp, color = palette.danger, maxLines = 2)
                    }
                }
            }

            is ArchiveEngine.InspectResult.Unsupported -> {
                item(key = "unsupported") {
                    EmptyState("无法预览", result.reason)
                }
                item(key = "extractAnyway") {
                    TextButton(onClick = { vm.requestExtract(entry) }) {
                        Text(text = "尝试解压", maxLines = 1)
                    }
                }
            }

            is ArchiveEngine.InspectResult.Ok -> {
                val archive = result.info
                item(key = "summary") {
                    Text(
                        text = "${archive.fileCount} 个文件 · 解压后 ${formatBytes(archive.totalUncompressed)}" +
                            if (archive.encrypted) " · 已加密" else "",
                        fontSize = 8.sp,
                        color = palette.muted,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.widthIn(max = 156.dp),
                    )
                }
                item(key = "extract") {
                    Button(
                        onClick = { vm.requestExtract(entry) },
                        modifier = Modifier.fillMaxWidth(0.7f),
                    ) {
                        Text(text = "解压到当前位置", maxLines = 1)
                    }
                }
                items(archive.entries.size, key = { "e_${archive.entries[it].path}" }) { index ->
                    val item = archive.entries[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (item.isDirectory) palette.cardHighlight else palette.card)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (item.isDirectory) "夹" else "·",
                            fontSize = 9.sp,
                            color = palette.of(if (item.isDirectory) com.tengwear.tengfilemanager.core.model.FileKind.FOLDER else com.tengwear.tengfilemanager.core.model.FileKind.OTHER),
                            maxLines = 1,
                        )
                        Text(
                            text = item.path,
                            modifier = Modifier.weight(1f),
                            fontSize = 8.5.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(text = formatBytes(item.size), fontSize = 7.5.sp, color = palette.muted, maxLines = 1)
                    }
                }
                if (archive.entries.size >= 300) {
                    item(key = "truncated") {
                        Text(text = "… 仅列出前 300 项", fontSize = 8.sp, color = palette.muted)
                    }
                }
            }
        }
        }
    }
}

/**
 * 安装包信息页。
 *
 * 这一页的核心不是"展示 APK 详情"，而是**把安装风险说清楚**：
 * 签名是否与已安装版本一致、是否降级、是否需要分包、系统会不会拒绝。
 * 用户看到"系统会拒绝覆盖安装"就不会反复点安装键。
 */
@Composable
fun ApkScreen(vm: FileManagerViewModel, entry: FileEntry) {
    val palette = LocalTengPalette.current
    val info by vm.apkInfo.collectAsStateWithLifecycle()
    val loading by vm.apkLoading.collectAsStateWithLifecycle()
    val state = rememberScalingLazyListState()

    LaunchedEffect(entry.key) { vm.inspectApk(entry) }

    ScreenScaffold(scrollState = state, edgeButton = {}) { _ ->
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 44.dp),
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
        item(key = "gap") { com.tengwear.tengfilemanager.presentation.ui.common.ClockGap(28.dp) }
        item(key = "head") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = info?.label ?: entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
                Text(
                    text = if (loading) "解析中…" else (info?.packageName ?: "无法解析"),
                    fontSize = 8.sp,
                    color = palette.muted,
                    maxLines = 1,
                )
            }
        }

        val apk = info
        if (apk != null) {
            item(key = "version") {
                InfoRow("版本", "${apk.versionName ?: "—"}（${apk.versionCode}）")
            }
            item(key = "size") { InfoRow("大小", formatBytes(apk.sizeBytes)) }
            item(key = "sdk") {
                InfoRow("系统要求", "Android API ${apk.minSdk}+（当前 ${android.os.Build.VERSION.SDK_INT}）")
            }
            item(key = "sign") {
                InfoRow(
                    "签名",
                    apk.signatureShort,
                    if (apk.signatureMatchesInstalled) palette.success else palette.danger,
                )
            }
            apk.installedVersionName?.let { installed ->
                item(key = "installed") { InfoRow("已安装", installed) }
            }
            if (apk.splitNames.isNotEmpty()) {
                item(key = "splits") { InfoRow("分包", "${apk.splitNames.size} 个 split") }
            }
            if (apk.permissions.isNotEmpty()) {
                item(key = "perms") { InfoRow("权限", apk.permissions.take(3).joinToString("、")) }
            }

            apk.warnings.forEachIndexed { index, warning ->
                item(key = "warn_$index") {
                    Text(
                        text = "⚠ $warning",
                        modifier = Modifier.widthIn(max = 158.dp),
                        fontSize = 8.sp,
                        color = palette.warning,
                        textAlign = TextAlign.Center,
                        lineHeight = 11.sp,
                    )
                }
            }

            apk.blockReason?.let { reason ->
                item(key = "blocked") {
                    Text(
                        text = reason,
                        modifier = Modifier.widthIn(max = 158.dp),
                        fontSize = 9.sp,
                        color = palette.danger,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                    )
                }
            }

            item(key = "install") {
                Button(
                    onClick = { vm.requestInstall() },
                    enabled = apk.canInstall,
                    modifier = Modifier.fillMaxWidth(0.5f),
                ) {
                    Text(
                        text = if (apk.canInstall) "安装" else "处理问题后安装",
                        maxLines = 1,
                    )
                }
            }
            item(key = "note") {
                Text(
                    text = "安装由系统安装器完成，本应用不会静默安装。",
                    fontSize = 7.5.sp,
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }
        }
        }
    }
}
