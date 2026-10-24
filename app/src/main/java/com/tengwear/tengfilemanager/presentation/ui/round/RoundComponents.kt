package com.tengwear.tengfilemanager.presentation.ui.round

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.model.FileKind
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 圆屏专用小部件集合。
 *
 * 原则：能用 Wear M3 组件的地方一律用（Text / Icon / Button / ScalingLazyColumn），
 * 只有当"圆形几何"是功能本身（进度弧、曲线标题、边缘滚动轨、类型徽标）时才自己画，
 * 因为 M3 没有对应槽位，硬套矩形组件反而会做出贴边/裁切的效果。
 */

private const val RING_GAP_DEG = 96f
private const val RING_SWEEP_DEG = 360f - RING_GAP_DEG
private const val RING_START_DEG = 270f + RING_GAP_DEG / 2f

/** 进度弧：贴安全圆内沿走，顶部留 96° 给标题，避免"通栏进度条"在圆上被切角。 */
@Composable
fun RingArc(
    progress: Float,
    modifier: Modifier = Modifier,
    radiusRatio: Float = 0.97f,
    strokeWidth: Dp = 2.5.dp,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    indeterminate: Boolean = false,
    indeterminateRotation: Float = 0f,
) {
    val progressColor = if (color == Color.Unspecified) androidx.wear.compose.material3.MaterialTheme.colorScheme.primary else color
    val track = if (trackColor == Color.Unspecified) progressColor.copy(alpha = 0.14f) else trackColor
    val safeRadius = LocalRoundMetrics.current.safeRadius
    val r = safeRadius * radiusRatio

    Canvas(modifier = modifier.fillMaxSize()) {
        val radiusPx = r.toPx()
        if (radiusPx <= 0f) return@Canvas
        val topLeft = Offset(center.x - radiusPx, center.y - radiusPx)
        val arcSize = Size(radiusPx * 2f, radiusPx * 2f)
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)

        drawArc(
            color = track,
            startAngle = RING_START_DEG,
            sweepAngle = RING_SWEEP_DEG,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        if (indeterminate) {
            drawArc(
                color = progressColor,
                startAngle = RING_START_DEG + indeterminateRotation % RING_SWEEP_DEG,
                sweepAngle = 48f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        } else {
            drawArc(
                color = progressColor,
                startAngle = RING_START_DEG,
                sweepAngle = RING_SWEEP_DEG * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
    }
}

/**
 * 沿圆弧排版的文本（用于"当前位置"这类顶部标题）。
 * 用 drawTextOnPath 而不是给 Text 加 padding：后者在圆顶会顶出安全区。
 */
@Composable
fun CurvedText(
    text: String,
    modifier: Modifier = Modifier,
    radiusRatio: Float = 0.80f,
    fontSize: TextUnit = 12.sp,
    color: Color = Color.Unspecified,
    centerAngleDeg: Float = 270f,
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.toPx() }
    val textColor = if (color == Color.Unspecified) androidx.wear.compose.material3.MaterialTheme.colorScheme.onBackground else color
    val safeRadius = LocalRoundMetrics.current.safeRadius
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.LEFT
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val r = (safeRadius * radiusRatio).toPx()
        if (r <= 0f) return@Canvas
        paint.textSize = fontSizePx
        paint.color = textColor.toArgb()

        val textWidth = paint.measureText(text)
        val circumference = 2f * PI.toFloat() * r
        val halfSweep = (textWidth / circumference) * 360f / 2f
        val path = Path().apply {
            addArc(
                oval = Rect(center.x - r, center.y - r, center.x + r, center.y + r),
                startAngleDegrees = centerAngleDeg - halfSweep,
                sweepAngleDegrees = halfSweep * 2f,
            )
        }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawTextOnPath(text, path.asAndroidPath(), 0f, 0f, paint)
        }
    }
}

/** 文件类型徽标：单字 + 语义色。比 24dp 图标省内存，且 12sp 下仍可辨认。 */
@Composable
fun KindBadge(
    kind: FileKind,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    dimmed: Boolean = false,
) {
    val palette = LocalTengPalette.current
    val color = palette.of(kind)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (dimmed) color.copy(alpha = 0.18f) else color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = kind.badge,
            fontSize = with(LocalDensity.current) { (size.value * 0.42f).sp },
            color = if (dimmed) color.copy(alpha = 0.6f) else color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 圆形图标动作按钮。
 * Wear M3 的 IconButton 最小尺寸 48dp，在 96dp 宽的底部动作区放三个会溢出，
 * 所以这里用 Box + clip + clickable 组合出 40dp 圆点，仍复用 M3 的 Icon 与语义 Role。
 */
@Composable
fun RoundIconAction(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    container: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val palette = LocalTengPalette.current
    val bg = if (container == Color.Unspecified) palette.card else container
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 文字胶囊按钮（"粘贴""压缩"这类动作）。 */
@Composable
fun RoundTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    val palette = LocalTengPalette.current
    val scheme = androidx.wear.compose.material3.MaterialTheme.colorScheme
    val bg = when {
        !enabled -> palette.card.copy(alpha = 0.5f)
        container == Color.Unspecified -> scheme.secondaryContainer
        else -> container
    }
    val fg = when {
        !enabled -> palette.muted
        contentColor == Color.Unspecified -> scheme.onSecondaryContainer
        else -> contentColor
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 12.sp, color = fg, maxLines = 1)
    }
}

/**
 * 边缘滚动轨（右侧弧线）。
 *
 * 它同时承担两个职责：
 *  1. 位置指示：让用户知道"我在这个目录的第几项"；
 *  2. **无表冠设备的替代交互**：点按/拖动轨道任意位置直接跳转，
 *     弥补没有旋转输入时"只能一格格滑"的低效。
 */
@Composable
fun EdgeScrollRail(
    itemCount: Int,
    currentIndex: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    if (!visible || itemCount <= 1) return
    val palette = LocalTengPalette.current
    val safeRadius = LocalRoundMetrics.current.safeRadius
    val radiusPx = with(LocalDensity.current) { safeRadius.toPx() }
    val current by rememberUpdatedState(currentIndex)
    val count by rememberUpdatedState(itemCount)
    val seek by rememberUpdatedState(onSeek)

    val span = 124f      // 轨道覆盖的角度范围（-62° ~ +62°）
    val ticks = itemCount.coerceAtMost(24)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(itemCount) {
                detectTapGestures { offset ->
                    val angle = angleOf(offset, size.width / 2f, size.height / 2f)
                    if (angle in -span / 2f..span / 2f) {
                        seek(angleToIndex(angle, span, count))
                    }
                }
            }
            .pointerInput(itemCount) {
                detectVerticalDragGestures { change, _ ->
                    val angle = angleOf(change.position, size.width / 2f, size.height / 2f)
                    if (angle in -span / 2f..span / 2f) {
                        seek(angleToIndex(angle, span, count))
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            for (i in 0 until ticks) {
                val t = if (ticks == 1) 0.5f else i / (ticks - 1f)
                val angle = -span / 2f + span * t
                val rad = Math.toRadians(angle.toDouble())
                val x = cx + (radiusPx * cos(rad)).toFloat()
                val y = cy + (radiusPx * sin(rad)).toFloat()
                val active = i == current.coerceIn(0, ticks - 1)
                drawCircle(
                    color = if (active) Color.White else palette.muted.copy(alpha = 0.35f),
                    radius = if (active) 3.2f.dp.toPx() else 1.6f.dp.toPx(),
                    center = Offset(x, y),
                )
            }
        }
    }
}

private fun angleOf(offset: Offset, cx: Float, cy: Float): Float {
    val deg = Math.toDegrees(atan2((offset.y - cy).toDouble(), (offset.x - cx).toDouble())).toFloat()
    return deg
}

private fun angleToIndex(angle: Float, span: Float, count: Int): Int {
    val t = ((angle + span / 2f) / span).coerceIn(0f, 1f)
    return (t * (count - 1).coerceAtLeast(0)).roundToInt()
}

/** 顶部居中标题（非弧形场景），保持与曲线标题一致的视觉重心。 */
@Composable
fun CenterLabel(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val palette = LocalTengPalette.current
    Text(
        text = text,
        modifier = modifier,
        fontSize = 11.sp,
        color = if (color == Color.Unspecified) palette.muted else color,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/** 空态占位：圆屏中间放一行字 + 一行说明，不用插图（省内存）。 */
@Composable
fun EmptyState(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val palette = LocalTengPalette.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, fontSize = 13.sp, color = palette.muted, textAlign = TextAlign.Center, maxLines = 2)
        subtitle?.let {
            Text(text = it, fontSize = 10.sp, color = palette.muted.copy(alpha = 0.7f), textAlign = TextAlign.Center, maxLines = 3)
        }
    }
}

@Composable
fun MoreDots(color: Color = Color.Unspecified) {
    Icon(
        imageVector = Icons.Filled.MoreVert,
        contentDescription = "更多",
        tint = if (color == Color.Unspecified) LocalTengPalette.current.muted else color,
        modifier = Modifier.size(18.dp),
    )
}

/**
 * 通用圆形列表行：28dp 徽标 + 标题 + 副标题 + 可选尾注。
 *
 * 宽度只占 0.78：圆屏上贴边的内容在斜视角度下会被表壳遮住。
 * 227dp 屏实测：0.86 时行两端几乎咬住表壳，0.78 才留出约 12dp 呼吸位。
 */
@Composable
fun RoundListRow(
    glyph: String,
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    selected: Boolean = false,
    widthFraction: Float = 0.78f,
    onClick: () -> Unit,
) {
    val palette = LocalTengPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .clip(RoundedCornerShape(50))
            .background(if (selected) palette.cardHighlight else palette.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = glyph, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = androidx.wear.compose.material3.MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                textAlign = TextAlign.Start,
            )
            subtitle?.let {
                Text(text = it, fontSize = 8.5.sp, color = palette.muted, maxLines = 1, textAlign = TextAlign.Start)
            }
        }
        trailing?.let {
            Text(text = it, fontSize = 10.sp, color = palette.muted, maxLines = 1)
        }
    }
}
