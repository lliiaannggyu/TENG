package com.tengwear.tengfilemanager.core.theme

/**
 * 可选的种子色主题。
 *
 * 第一个 [SYSTEM] 走 `dynamicColorScheme(context)`——直接读安卓系统的
 * `android.R.color.system_*_dark` 资源，那是 Google Material You 用官方 HCT
 * 从壁纸种子色生成的**完整官方色阶**，零近似。其余为固定种子色，
 * 色阶由 [TonalPalette] 按 Material 3 的 tone 体系推导。
 *
 * 种子色取自 Google 官方品牌色板 + Material 3 基准种子色。
 */
enum class SeedTheme(
    val id: String,
    val label: String,
    val seedArgb: Long,
) {
    /** 跟随系统（Material You）：安卓 12+ 用官方 HCT 从壁纸生成的色阶。 */
    SYSTEM("system", "跟随系统色阶", 0xFF6750A4L),

    /** Material 3 官方基准种子色。 */
    BASELINE("baseline", "Material 基准紫", 0xFF6750A4L),

    GOOGLE_BLUE("g_blue", "谷歌蓝", 0xFF1A73E8L),
    GOOGLE_RED("g_red", "谷歌红", 0xFFEA4335L),
    GOOGLE_YELLOW("g_yellow", "谷歌黄", 0xFFF9AB00L),
    GOOGLE_GREEN("g_green", "谷歌绿", 0xFF34A853L),
    GOOGLE_CYAN("g_cyan", "谷歌青", 0xFF12B5CBL),
    GRAPHITE("graphite", "石墨灰", 0xFF6E7A8AL),
    ;

    companion object {
        fun fromId(id: String?): SeedTheme = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}
