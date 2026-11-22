package com.tengwear.tengfilemanager.presentation.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
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
import com.tengwear.tengfilemanager.core.theme.TonalPalette

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
 *  · 其余种子色由 [TonalPalette] 按 tone 推导，彩度分配沿用 Material 3 的比例关系
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
 * 文件类型功能色与语义强调色。
 *
 * Wear M3 的 colorScheme 槽位有限，文件类型需要 8 种可区分色，硬塞进
 * primary/secondary/tertiary 会让主题语义崩塌，所以单独维护一份。
 *
 * 这些颜色**不使用手挑的十六进制值**：每一种都由官方 HCT 算法
 * （[TonalPalette.fromSeed]）从 Google 官方品牌种子色在 tone 75 推导——
 * tone 75 在深色背景上的对比度达到 M3 无障碍要求。它们不随主题种子色变：
 * 文件类型的可辨识度比"跟着主题走"更重要，用户换主题色时
 * 不应该认不出哪个是压缩包。
 *
 * 语义色中的"危险"不在这里：删除等破坏性操作一律用官方槽位
 * `MaterialTheme.colorScheme.error`。
 */
@Immutable
data class KindColors(
    val folder: Color = brand(0xFFF9AB00L),   // Google 黄
    val image: Color = brand(0xFF12B5CBL),    // Google 青
    val video: Color = brand(0xFFA142F4L),    // Google 紫
    val audio: Color = brand(0xFFF43FA9L),    // Material 粉
    val document: Color = brand(0xFF1A73E8L), // Google 蓝
    val archive: Color = brand(0xFFE8710AL),  // Google 橙
    val apk: Color = brand(0xFF34A853L),      // Google 绿
    val other: Color = brand(0xFF9AA0A6L),    // Google 灰
    val success: Color = brand(0xFF34A853L),  // Google 绿
    val warning: Color = brand(0xFFF9AB00L),  // Google 黄
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

/** 从官方品牌种子色按 HCT 算法取 tone 75（深色背景下的高对比档）。 */
private fun brand(seedArgb: Long): Color = Color(TonalPalette.fromSeed(seedArgb).tone(75))

/** HCT 推导有计算成本，默认实例只算一次。 */
private val DefaultKindColors by lazy { KindColors() }

val LocalKindColors = staticCompositionLocalOf { DefaultKindColors }

@Composable
fun TENG文件管理器Theme(
    seed: SeedTheme = FALLBACK,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = buildScheme(seed, context)
    CompositionLocalProvider(LocalKindColors provides DefaultKindColors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
