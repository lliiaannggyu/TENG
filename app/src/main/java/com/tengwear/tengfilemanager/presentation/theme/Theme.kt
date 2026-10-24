package com.tengwear.tengfilemanager.presentation.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme
import com.tengwear.tengfilemanager.core.model.FileKind
import com.tengwear.tengfilemanager.core.theme.SeedPalettes
import com.tengwear.tengfilemanager.core.theme.SeedTheme

/**
 * 配色。
 *
 * 为什么只做深色：手表以 OLED 为主，纯黑像素不发光。
 * 一个文件列表页的亮色背景会让待机功耗翻倍，这不是审美选择而是功耗约束。
 *
 * ## 主题色（种子色）
 * 颜色全部来自 Material 3 的 **tone 体系**而不是手挑的十六进制值：
 *  · [SeedTheme.SYSTEM] 用 `dynamicColorScheme(context)`，即安卓 Material You
 *    从壁纸生成的官方色阶（Google 官方 HCT 算法，零近似）；
 *  · 其余种子色由 `TonalPalette` 按 tone 推导，彩度分配沿用 Material 3 的比例关系
 *    （主色阶保留种子彩度，次级约 1/3，三级再降一档，中性接近灰）。
 *
 * tone 映射用的是 Material 3 深色方案的对应关系：
 * primary=P80 / onPrimary=P20 / primaryContainer=P30 / onPrimaryContainer=P90 …
 */
private val FALLBACK = SeedTheme.BASELINE

@Composable
private fun buildScheme(theme: SeedTheme, context: Context): ColorScheme {
    // 系统色阶优先：这是谷歌官方色阶，且会跟随壁纸/表盘变化
    if (theme == SeedTheme.SYSTEM) {
        dynamicColorScheme(context)?.let { return it }
    }
    val palettes = remember(theme) { SeedPalettes.fromSeed(theme.seedArgb) }
    return remember(palettes) { darkSchemeFrom(palettes) }
}

/** Material 3 深色方案的 tone 映射。 */
private fun darkSchemeFrom(p: SeedPalettes): ColorScheme = ColorScheme(
    primary = Color(p.primary.tone(80)),
    onPrimary = Color(p.primary.tone(20)),
    primaryContainer = Color(p.primary.tone(30)),
    onPrimaryContainer = Color(p.primary.tone(90)),
    secondary = Color(p.secondary.tone(80)),
    onSecondary = Color(p.secondary.tone(20)),
    secondaryContainer = Color(p.secondary.tone(30)),
    onSecondaryContainer = Color(p.secondary.tone(90)),
    background = Color(p.neutral.tone(6)),
    onBackground = Color(p.neutral.tone(90)),
    surfaceContainerLow = Color(p.neutral.tone(12)),
    surfaceContainer = Color(p.neutral.tone(17)),
    surfaceContainerHigh = Color(p.neutral.tone(22)),
    onSurface = Color(p.neutral.tone(90)),
    onSurfaceVariant = Color(p.neutralVariant.tone(80)),
    outline = Color(p.neutralVariant.tone(60)),
    outlineVariant = Color(p.neutralVariant.tone(30)),
    error = Color(p.error.tone(80)),
    onError = Color(p.error.tone(20)),
    errorContainer = Color(p.error.tone(30)),
    onErrorContainer = Color(p.error.tone(90)),
)

/**
 * 语义色板。Wear M3 的 colorScheme 槽位有限，文件类型需要 8 种可区分色，
 * 硬塞进 primary/secondary/tertiary 会让主题语义崩塌，所以单独维护一份。
 *
 * 这 8 个色**不随种子色变**：文件类型的可辨识度比"跟着主题走"更重要，
 * 用户换主题色时不应该认不出哪个是压缩包。
 */
@Immutable
data class TengPalette(
    val card: Color = Color(0xFF171D23),
    val cardHighlight: Color = Color(0xFF1F2A32),
    val divider: Color = Color(0xFF2A343C),
    val muted: Color = Color(0xFF93A2AE),
    val danger: Color = Color(0xFFFF6B6B),
    val success: Color = Color(0xFF6BD98A),
    val warning: Color = Color(0xFFFFC66B),
    val folder: Color = Color(0xFFFFC66B),
    val image: Color = Color(0xFF5FD6BE),
    val video: Color = Color(0xFFB69CFF),
    val audio: Color = Color(0xFFFF9AC4),
    val document: Color = Color(0xFF7FB6FF),
    val archive: Color = Color(0xFFFFA45C),
    val apk: Color = Color(0xFF8BE07A),
    val other: Color = Color(0xFF93A2AE),
) {
    fun of(kind: FileKind): Color = when (kind) {
        FileKind.FOLDER -> folder
        FileKind.IMAGE -> image
        FileKind.VIDEO -> video
        FileKind.AUDIO -> audio
        FileKind.DOCUMENT -> document
        FileKind.ARCHIVE -> archive
        FileKind.APK -> apk
        FileKind.OTHER -> other
    }
}

val LocalTengPalette = staticCompositionLocalOf { TengPalette() }

@Composable
fun TENG文件管理器Theme(
    seed: SeedTheme = FALLBACK,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = buildScheme(seed, context)
    CompositionLocalProvider(LocalTengPalette provides TengPalette()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
