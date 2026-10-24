package com.tengwear.tengfilemanager.presentation.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.compose.ui.res.painterResource
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.R
import com.tengwear.tengfilemanager.core.model.FileKind
import com.tengwear.tengfilemanager.presentation.theme.LocalTengPalette

/**
 * M3E 补齐层。
 *
 * 这一层只保留 `androidx.wear.compose.material3`（官方 Material 3 Expressive for Wear）
 * 确实没有对应原语的三样东西，其余 UI 一律直接调用 M3E 组件：
 *
 *  1. [KindBadge] —— 文件类型徽标。M3E 没有"类型徽标"这个概念；
 *  2. [FileCard] —— 文件行。区间多选要知道"跨过了几行"，只能挂在行外层；
 *  3. [SelectionScrim] —— 选择工具条的底部压暗，列表会从工具条下面滚过。
 *
 * 关于类型徽标为什么不用图标：material-icons-extended 有 35MB，
 * 而 debug 构建不开 R8，为了 8 个类型图标把包体撑大一倍不划算。
 * 圆屏 12sp 的单字在 227dp 屏上比 16dp 的线性图标更易辨认。
 */

/** 文件类型 → 官方 Material 图标资源（见 res/drawable/ic_kind_*.xml）。 */
private fun FileKind.iconRes(): Int = when (this) {
    FileKind.FOLDER -> R.drawable.ic_kind_folder
    FileKind.IMAGE -> R.drawable.ic_kind_image
    FileKind.VIDEO -> R.drawable.ic_kind_video
    FileKind.AUDIO -> R.drawable.ic_kind_audio
    FileKind.DOCUMENT -> R.drawable.ic_kind_document
    FileKind.ARCHIVE -> R.drawable.ic_kind_archive
    FileKind.APK -> R.drawable.ic_kind_apk
    FileKind.OTHER -> R.drawable.ic_kind_other
}

/**
 * 文件类型徽标。
 *
 * 图标用官方 Material 图标（路径数据逐字复制自 material-icons-extended，
 * 见 res/drawable/ 的注释），颜色来自语义色板，容器是 M3E 的圆形 token。
 * 不再用汉字徽标——那是上一版"AI 味"的主要来源之一。
 */
@Composable
fun KindBadge(
    kind: FileKind,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 28.dp,
) {
    val palette = LocalTengPalette.current
    val color = palette.of(kind)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.20f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(kind.iconRes()),
            contentDescription = kind.label,
            tint = color,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}

/**
 * 文件行。
 *
 * 直接包一层 M3E [Card]：点击、长按、按下形变、无障碍全部由官方组件负责。
 * 自己只加两件事：
 *  - 选中态换 `surfaceContainerHigh`；
 *  - 右滑跨行区间多选（M3E 没有这个原语，且手表上逐个点选 30 个文件不可接受）。
 */
@Composable
fun FileCard(
    entry: FileEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    selecting: Boolean = false,
    secondary: String? = null,
    onRangeSelect: ((Int) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = LocalTengPalette.current
    val haptic = LocalHapticFeedback.current
    // 手势期间用本地位移量，避免每帧重组 Card
    var dragAccum by remember { mutableFloatStateOf(0f) }
    var lastRow by remember { mutableIntStateOf(0) }
    // 拖拽时整行跟着手指横移一点（最多 12dp），松手回弹——
    // 区间多选原来完全没有视觉反馈，用户不知道"跨过了几行"
    val dragOffset by animateFloatAsState(
        targetValue = dragAccum.coerceIn(-60f, 60f) * 0.2f,
        animationSpec = spring(dampingRatio = 0.7f),
        label = "rowDrag",
    )
    // 选中/取消的背景色平滑过渡，而不是瞬间跳色
    val container by animateColorAsState(
        targetValue = if (selected) scheme.surfaceContainerHigh else scheme.surfaceContainer,
        animationSpec = tween(180),
        label = "rowSelect",
    )

    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = true,
        colors = CardDefaults.cardColors(containerColor = container),
        // M3E 默认内边距是"每屏 2~3 项"的密度，文件列表要 3~4 项，所以压到 6dp。
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = modifier
            .fillMaxWidth(0.86f)
            // M3E Card 的官方最小高度是 64dp（CardTokens.ContainerMinHeight），
            // 那是给"每屏 2~3 项"的卡片设计的。文件列表要 4~5 项，
            // 所以显式压到 48dp（与 CompactButton 同档）。
            // height() 会覆盖 defaultMinSize，组件本身的按下形变/无障碍不受影响。
            .height(48.dp)
            .graphicsLayer { translationX = dragOffset }
            .pointerInput(entry.key, selecting) {
                if (onRangeSelect == null || !selecting) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragAccum = 0f },
                    onDragEnd = { dragAccum = 0f },
                    onDragCancel = { dragAccum = 0f },
                ) { _, dragAmount ->
                    dragAccum += dragAmount
                    val rows = (dragAccum / ROW_STEP_PX).toInt()
                    if (rows != lastRow) {
                        lastRow = rows
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRangeSelect(rows)
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KindBadge(entry.kind)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
                Text(
                    text = secondary ?: entry.secondaryLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }
            if (selecting) {
                // 选中态：圆点 + 勾选图标缩放弹入，比"瞬间出现"更容易看清点中了没有
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) scheme.primary else scheme.surfaceContainerHigh
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    SelectionCheck(selected = selected)
                }
            }
        }
    }
}

/** 列表项副标题：目录显示项数，文件显示"大小 · 时间"。 */
fun FileEntry.secondaryLabel(): String {
    val size = com.tengwear.tengfilemanager.core.util.formatBytes(this.size)
    val time = com.tengwear.tengfilemanager.core.util.formatRelativeTime(this.lastModified)
    return if (isDirectory) "文件夹" else "$size · $time"
}

/**
 * 选择态底部压暗层。
 *
 * 列表还会从工具条下面滚过，不压暗的话圆按钮之间的空隙会露出文件名，
 * 看起来像排版错位而不是"浮在内容之上"。
 */
@Composable
fun SelectionScrim(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 92.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .alpha(0.96f)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(
                        androidx.compose.ui.graphics.Color.Transparent,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
    )
}

/**
 * 选择态工具条。
 *
 * 四个动作：复制 / 删除 / 更多 / **取消**。取消必须常驻——
 * 手表上误触长按就进选择态的概率不低，没有一个明显的"取消"出口，
 * 用户只能靠返回键，而返回键在圆屏上是个不显眼的手势。
 *
 * 用 M3E [CompactButton] + 两字中文标签，而不是图标按钮：
 *  · `material-icons-core` 没有"复制"图标，硬上 extended 图标库要 35MB；
 *  · 圆屏底部 32dp 处的弦宽约 170dp，四个 52dp 的 IconButton 放不下；
 *  · 两字中文标签在 227dp 屏上比 20dp 线性图标更易读。
 */
@Composable
fun SelectionActionBar(
    modifier: Modifier = Modifier,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = LocalTengPalette.current
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            // 高一点、粗一点：底部工具条是浮层，容器内边距给到 8dp，
            // 按钮用 CompactButton 的 48dp 档，整条约 64dp 高，
            // 在圆屏底部不会显得单薄
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionChip(label = "复制", color = scheme.onSurface, onClick = onCopy)
        SelectionChip(label = "删除", color = palette.danger, onClick = onDelete)
        SelectionChip(label = "更多", color = scheme.onSurface, onClick = onMore)
        SelectionChip(label = "取消", color = scheme.onSurfaceVariant, onClick = onCancel)
    }
}

@Composable
private fun SelectionChip(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    CompactButton(onClick = onClick) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

/** 空态。用 M3E 的排版档位，不自己画插画——手表上空态的插画只会挤占列表空间。 */
@Composable
fun EmptyState(
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth(0.86f)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 信息行（键值对）。M3E 没有这种原语，手表上又确实需要展示详情。 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth(0.86f)
            .clip(CircleShape)
            .background(scheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 分组标题。直接用 M3E ListHeader，不再自绘。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    androidx.wear.compose.material3.ListHeader(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一段垂直留白，用于给顶部时钟带让位。 */
@Composable
fun ClockGap(height: androidx.compose.ui.unit.Dp) {
    Spacer(modifier = Modifier.height(height))
}

/** 跨过一行所需的水平位移（像素）。行高 52dp，在 320dpi 上约 110px。 */
private const val ROW_STEP_PX = 110f

/**
 * 菜单入口行。
 *
 * 图标优先用官方 Material 图标（`res/drawable/ic_*`，路径数据逐字复制自
 * material-icons-extended），`material-icons-core` 里有的就用 ImageVector 重载。
 * 和 [FileCard] 的区别：这里表达的是"功能"，所以用功能性图标而不是文件类型徽标。
 */
@Composable
fun MenuCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) = MenuCard(
    icon = { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(17.dp)) },
    title = title,
    accent = accent,
    modifier = modifier,
    subtitle = subtitle,
    onClick = onClick,
)

@Composable
fun MenuCard(
    @androidx.annotation.DrawableRes icon: Int,
    title: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) = MenuCard(
    icon = { Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(17.dp)) },
    title = title,
    accent = accent,
    modifier = modifier,
    subtitle = subtitle,
    onClick = onClick,
)

@Composable
private fun MenuCard(
    icon: @Composable () -> Unit,
    title: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = modifier
            .fillMaxWidth(0.86f)
            // 同 FileCard：Card 默认 64dp 对菜单行也太高，压到 52dp
            .height(52.dp),
    ) {
        // 内容整体居中：圆屏上没有"左对齐"这回事，贴边的文字在斜视角度下会被表壳遮住，
        // 而且居中的图标+文字在 227dp 圆屏上比左对齐更接近 Wear 官方卡片的样子。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 种子色预览圆点。
 *
 * 用 `TonalPalette` 生成"该种子色在 Material 3 深色方案下的主色 + 容器色"两段，
 * 让用户在设置里一眼看出每个选项大致长什么样，而不是只看一个裸种子色
 * （裸种子色是 tone 40 的浅色档位，和深色方案里实际用的 tone 80 差很远）。
 */
@Composable
fun SeedSwatch(
    seedArgb: Long,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 22.dp,
) {
    val palettes = remember(seedArgb) {
        com.tengwear.tengfilemanager.core.theme.SeedPalettes.fromSeed(seedArgb)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(palettes.primary.tone(30))),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.52f)
                .clip(CircleShape)
                .background(Color(palettes.primary.tone(80))),
        )
    }
}

/**
 * 选中指示器。
 *
 * 抽成独立函数而不是内联在 Card 的 Row 里：`AnimatedVisibility` 在 RowScope 下
 * 会解析到 Row 版重载，那个版本没有 enter/exit 参数，直接编译不过。
 */
@Composable
private fun SelectionCheck(selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(tween(90)),
            exit = scaleOut(targetScale = 0.4f) + fadeOut(tween(90)),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "已选中",
                tint = scheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
