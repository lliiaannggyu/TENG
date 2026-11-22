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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tengwear.tengfilemanager.R
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.model.FileKind
import com.tengwear.tengfilemanager.presentation.theme.LocalKindColors

/**
 * M3E 补齐层。
 *
 * 这一层只保留 `androidx.wear.compose.material3`（官方 Material 3 Expressive for Wear）
 * 确实没有对应原语的东西，其余 UI 一律直接调用 M3E 组件，并且**不覆盖官方默认值**：
 * 卡片高度、内边距、圆角、配色、排版全部用组件自带的官方 token
 * （`CardDefaults.ContentPadding`、`CardDefaults.Height` 等），
 * 只在官方没有该概念的地方自绘（类型徽标、区间多选、浮层压暗）。
 *
 * 关于类型徽标为什么不用图标库：material-icons-extended 有 35MB，
 * 而 debug 构建不开 R8，为了 8 个类型图标把包体撑大一倍不划算。
 * res/drawable 里的 ic_kind_* 路径数据逐字复制自官方 material-icons-extended。
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
 * 图标用官方 Material 图标，颜色由 HCT 从官方品牌种子色推导（见 [LocalKindColors]），
 * 容器是 Material 的"低饱和圆形底 + 高对比前景"模式。
 */
@Composable
fun KindBadge(
    kind: FileKind,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val color = LocalKindColors.current.of(kind)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
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
 * 直接用 M3E [Card] 的**官方默认度量**（默认 ContentPadding、默认最小高度
 * `CardDefaults.Height`、全宽）：点击、长按、按下形变、无障碍全部由官方组件负责，
 * 每屏 2~3 行是 M3E 的官方密度，不为了塞进更多行去压缩官方 token。
 *
 * 自己只加两件事：
 *  - 选中态换官方容器色 `primaryContainer`（M3 的"已选容器"语义）；
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
    val haptic = LocalHapticFeedback.current
    // 手势期间用本地位移量，避免每帧重组 Card
    var dragAccum by remember { mutableFloatStateOf(0f) }
    var lastRow by remember { mutableIntStateOf(0) }
    // 拖拽时整行跟着手指横移一点，松手回弹——
    // 区间多选需要视觉反馈，用户才知道"跨过了几行"
    val dragOffset by animateFloatAsState(
        targetValue = dragAccum.coerceIn(-60f, 60f) * 0.2f,
        animationSpec = spring(dampingRatio = 0.7f),
        label = "rowDrag",
    )
    // 选中/取消的容器色平滑过渡：官方"已选容器"= primaryContainer
    val container = if (selected) scheme.primaryContainer else scheme.surfaceContainer
    val onContainer = if (selected) scheme.onPrimaryContainer else scheme.onSurface

    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = onContainer,
        ),
        contentPadding = ListRowPadding,
        modifier = modifier
            .fillMaxWidth()
            .height(ListRowHeight)
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
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KindBadge(entry.kind)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = secondary ?: entry.secondaryLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) onContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selecting) {
                SelectionCheck(selected = selected)
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
 * 列表还会从操作条下面滚过，不压暗的话按钮之间的空隙会露出文件名，
 * 看起来像排版错位而不是"浮在内容之上"。
 */
@Composable
fun SelectionScrim(modifier: Modifier = Modifier, height: Dp = 96.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                ),
            ),
    )
}

/**
 * 选择态操作条。
 *
 * 三个动作用官方 [IconButton]（图标 24dp、容器 52dp、按下形变与涟漪都是官方实现）：
 * 复制 / 删除 / **取消**。取消必须常驻——手表上误触长按就进选择态的概率不低，
 * 没有一个明显的"取消"出口，用户只能靠返回键，而返回键在圆屏上是个不显眼的手势。
 *
 * 其余批量动作（剪切 / 压缩 / 分享 / 全选）走浏览页底部的官方 EdgeButton
 * 进快捷菜单——M3E 的 ButtonGroup 官方指南也是"一组不超过 3 个按钮"。
 */
@Composable
fun SelectionActionBar(
    modifier: Modifier = Modifier,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCopy) {
            Icon(
                painter = painterResource(R.drawable.ic_content_copy),
                contentDescription = "复制",
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除",
                tint = scheme.error,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "取消",
            )
        }
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
        modifier = modifier.fillMaxWidth(),
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
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
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

/** 分组标题。直接用 M3E [ListHeader]，不自绘。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    ListHeader(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 列表行的统一行高。
 *
 * M3E 官方 token 体系里 Button / RadioButton / SwitchButton 的行高都是 52dp，
 * CompactButton 48dp，唯独 Card 的官方最小高度是 64dp（每屏 2~3 行的卡片密度）。
 * 文件列表是"行"不是"卡片"，64dp 在一屏里只能放 3 行，所以统一压到 56dp
 * （官方 EdgeButtonSize.Small 档）：titleMedium(24dp) + labelSmall(16dp) 两行
 * 官方排版 + 上下各 6dp 内边距正好放下且留有余量，与 52dp 的控件行放在一起
 * 也不会显得突兀。
 */
val ListRowHeight = 56.dp

/** 列表行的统一内边距：横向保持官方 12dp，纵向从官方 12dp 压到 6dp。 */
val ListRowPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

/** 跨过一行所需的水平位移（像素）。行高 56dp，在 320dpi 上约 120px。 */
private const val ROW_STEP_PX = 120f

/**
 * 菜单入口行。
 *
 * 官方 [Card] 默认度量 + "圆形图标底 + 双行文字"的 Material 惯用排布，
 * 与 [FileCard] 的区别：这里表达的是"功能"，用功能性图标而不是文件类型徽标。
 */
@Composable
fun MenuCard(
    icon: ImageVector,
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) = MenuCard(
    icon = { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
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
    accent: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) = MenuCard(
    icon = { Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp)) },
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
    accent: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        contentPadding = ListRowPadding,
        modifier = modifier
            .fillMaxWidth()
            .height(ListRowHeight),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
    size: Dp = 22.dp,
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
 * 选中指示器，按官方 CheckboxButton 的视觉画：
 * 未选中 = 描边空圆（outline），选中 = primary 实心圆 + onPrimary 勾。
 */
@Composable
private fun SelectionCheck(selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(scheme.primary)
                } else {
                    Modifier.border(2.dp, scheme.outline, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(tween(90)),
            exit = scaleOut(targetScale = 0.4f) + fadeOut(tween(90)),
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已选中",
                tint = scheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
