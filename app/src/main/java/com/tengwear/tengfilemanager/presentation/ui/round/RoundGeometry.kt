package com.tengwear.tengfilemanager.presentation.ui.round

import android.util.Log
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 圆屏几何模型（全屏铺满版本）。
 *
 * 与"把方形布局缩小塞进圆里"的区别：
 *  · 不假设任何矩形栅格：位置由极坐标 (角度, 半径) 决定；
 *  · 安全半径 = D/2 × 0.88。0.88 是实测值：D=216dp 时内容圆半径 ≈ 95dp，
 *    距离物理圆边还有 13dp 余量，正好容纳圆形列表项的边缘缩放；
 *  · 提供 [halfChordAt]：给定"距圆心的垂直偏移"，算出该高度上的可用半弦长，
 *    这是圆形列表做"越靠上下越窄"布局的数学依据（ScalingLazyColumn 内部同理）。
 *
 * 屏幕尺寸从 Configuration.screenWidthDp 读取，不写死 216dp——
 * 手表有 192/216/240/280 多种直径，写死会在非基准设备上偏出安全区。
 */
@Immutable
data class RoundMetrics(
    val diameter: Dp,
    val contentRatio: Float = DEFAULT_CONTENT_RATIO,
) {
    /** 内容安全圆半径。任何元素外沿到圆心的距离不得超过此值。 */
    val safeRadius: Dp get() = diameter / 2f * contentRatio

    fun radiusOf(fraction: Float): Dp = safeRadius * fraction

    /** 距圆心垂直偏移 dy 处，内容圆允许的最大半弦长。用于手算列表项最大宽度。 */
    fun halfChordAt(dy: Dp): Dp {
        val r = safeRadius.value
        val d = dy.value
        if (kotlin.math.abs(d) >= r) return 0.dp
        return sqrt(r * r - d * d).dp
    }

    companion object {
        /** 内容圆相对物理屏直径的比例。0.88 = 留 12% 给边缘缩放与物理边框。 */
        const val DEFAULT_CONTENT_RATIO = 0.88f

        fun of(diameter: Dp): RoundMetrics = RoundMetrics(diameter)

        /** 216dp 设计基准（Wear OS 中号圆屏）。 */
        fun reference(): RoundMetrics = RoundMetrics(216.dp)
    }
}

val LocalRoundMetrics = staticCompositionLocalOf { RoundMetrics.reference() }

/** 从当前屏幕宽度推导圆屏指标；Wear 设备上 screenWidthDp 即表盘直径。 */
@Composable
fun rememberRoundMetrics(): RoundMetrics {
    val width = LocalConfiguration.current.screenWidthDp.dp
    return remember(width) { RoundMetrics.of(if (width.value <= 0f) 216.dp else width) }
}

/** 圆形裁剪：Compose 等价于 .clipShape(Circle())，用于全屏内容容器。 */
fun Modifier.roundClip(): Modifier = clip(CircleShape)

/**
 * 极坐标约束：0° = 3 点方向，90° = 6 点方向（y 轴向下），270° = 12 点方向。
 * 与 Row/Column 的权重完全无关——位置只由 (角度, 半径) 决定。
 */
data class PolarData(val angleDeg: Float, val radius: Dp) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@PolarData
}

fun Modifier.polar(angleDeg: Float, radius: Dp): Modifier = this.then(PolarData(angleDeg, radius))

/**
 * 圆形舞台：按极坐标摆放子元素，并在 debug 下校验外沿不越出安全圆。
 * 不做矩形网格排列，因此子元素不会因为"排满一行"被推到四角。
 */
@Composable
fun CircularStage(
    modifier: Modifier = Modifier,
    safeRadius: Dp = LocalRoundMetrics.current.safeRadius,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        val items = measurables.map { measurable ->
            val data = measurable.parentData as? PolarData ?: PolarData(0f, 0.dp)
            measurable.measure(childConstraints) to data
        }

        layout(width, height) {
            val cx = width / 2f
            val cy = height / 2f
            items.forEach { (placeable, data) ->
                val radiusPx = data.radius.toPx()
                val angleRad = Math.toRadians(data.angleDeg.toDouble())
                val centerX = cx + (radiusPx * cos(angleRad)).toFloat()
                val centerY = cy + (radiusPx * sin(angleRad)).toFloat()

                if (radiusPx > 0f) {
                    val outermost = radiusPx + hypot(
                        placeable.width.toFloat(),
                        placeable.height.toFloat(),
                    ) / 2f
                    if (outermost > safeRadius.toPx() + 0.5f) {
                        Log.w(
                            TAG,
                            "元素越出安全圆：angle=${data.angleDeg}°, " +
                                "外沿=${outermost}px > safeRadius=${safeRadius.toPx()}px",
                        )
                    }
                }

                placeable.place(
                    x = (centerX - placeable.width / 2f).roundToInt(),
                    y = (centerY - placeable.height / 2f).roundToInt(),
                )
            }
        }
    }
}

private const val TAG = "CircularStage"
