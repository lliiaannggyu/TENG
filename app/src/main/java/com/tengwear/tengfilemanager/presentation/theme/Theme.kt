package com.tengwear.tengfilemanager.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ColorScheme
import com.tengwear.tengfilemanager.core.model.FileKind

/**
 * 配色。
 *
 * 为什么只做深色：手表以 OLED 为主，纯黑像素不发光。
 * 一个文件列表页的亮色背景会让待机功耗翻倍，这不是审美选择而是功耗约束。
 * 主色刻意压低饱和度——圆屏上高饱和色块会在边缘产生明显的光晕/锯齿。
 */
private val TengColorScheme = ColorScheme(
    primary = Color(0xFF8FD0FF),
    onPrimary = Color(0xFF00344E),
    primaryContainer = Color(0xFF114A68),
    onPrimaryContainer = Color(0xFFCDE7FF),
    secondary = Color(0xFFB7C9D6),
    onSecondary = Color(0xFF22323C),
    secondaryContainer = Color(0xFF2B3A44),
    onSecondaryContainer = Color(0xFFD3E4F1),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE4EAEF),
    // Wear M3 没有单一 surface 槽位，卡片面用三级容器色表达
    surfaceContainerLow = Color(0xFF0E1216),
    surfaceContainer = Color(0xFF171D23),
    surfaceContainerHigh = Color(0xFF1F2A32),
    onSurface = Color(0xFFE4EAEF),
    onSurfaceVariant = Color(0xFF93A2AE),
    outline = Color(0xFF2A343C),
    outlineVariant = Color(0xFF1B2127),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * 语义色板。Wear M3 的 colorScheme 槽位有限，文件类型需要 8 种可区分色，
 * 硬塞进 primary/secondary/tertiary 会让主题语义崩塌，所以单独维护一份。
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
fun TENG文件管理器Theme(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalTengPalette provides TengPalette()) {
        MaterialTheme(colorScheme = TengColorScheme, content = content)
    }
}
