package com.tengwear.tengfilemanager.core.util

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// 界面文案本身是中文，时间格式固定用 Locale.CHINA：
// 若跟随 Locale.getDefault()，用户在运行中切换语言会让这些静态 Formatter 停留在旧 locale。
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val FULL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

/** 1024 进制，保留 1 位小数；小于 1KB 直接给 B，避免 "0.0 KB" 这种噪声。 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (value >= 100) "${value.toInt()} ${units[unit]}"
    else String.format(Locale.US, "%.1f %s", value, units[unit])
}

/** 相对时间：手表上"今天 14:23"比 "2026-10-24 14:23" 省一半字符宽度。 */
fun formatRelativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0L) return "—"
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = Instant.ofEpochMilli(millis).atZone(zone).format(TIME_FMT)
    return when {
        date == today -> "今天 $time"
        date == today.minusDays(1) -> "昨天 $time"
        date.year == today.year -> date.format(DATE_FMT)
        else -> date.format(FULL_FMT)
    }
}

fun formatFullTime(millis: Long): String {
    if (millis <= 0L) return "—"
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return "${zoned.format(FULL_FMT)} ${zoned.format(TIME_FMT)}"
}

fun daysBetween(fromMillis: Long, now: Long = System.currentTimeMillis()): Long {
    val zone = ZoneId.systemDefault()
    val a: LocalDate = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
    val b: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return java.time.temporal.ChronoUnit.DAYS.between(a, b)
}

fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** 去掉文件系统与 SAF 都不接受的字符；不改变扩展名。 */
fun sanitizeFileName(raw: String): String {
    val cleaned = raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().trimEnd('.')
    return if (cleaned.isEmpty()) "未命名" else cleaned.take(120)
}

/**
 * 冲突处理：在已存在的名字集合里求一个不冲突的名字（"a.txt" → "a (1).txt"）。
 * 手表端不做"覆盖/跳过/保留两者"三选一弹窗——默认保留两者最安全，覆盖走显式确认。
 */
fun uniqueName(desired: String, exists: (String) -> Boolean): String {
    if (!exists(desired)) return desired
    val dot = desired.lastIndexOf('.')
    val stem = if (dot > 0) desired.substring(0, dot) else desired
    val ext = if (dot > 0) desired.substring(dot) else ""
    var i = 1
    while (i < 1000) {
        val candidate = "$stem ($i)$ext"
        if (!exists(candidate)) return candidate
        i++
    }
    return "$stem (${System.currentTimeMillis()})$ext"
}

fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** 短摘要：手表上只展示 8 位，足够肉眼比对，且不会撑破一行。 */
fun ByteArray.shortDigest(): String = toHex().take(8).uppercase(Locale.US)
