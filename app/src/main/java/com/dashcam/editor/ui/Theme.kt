package com.dashcam.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS 18 语义色板 + 统一圆角档位。
 *
 * 圆角只有这几档，且**同一块视觉区域内不嵌套圆角**：全宽的工具条/时间轴一律直角 + 发丝分隔线，
 * 只有真正浮在内容之上的东西（弹层、卡片、胶囊按钮）才带圆角。这是之前界面显得割裂的根因。
 */
object Ios {
    val Blue = Color(0xFF007AFF)
    val Green = Color(0xFF34C759)
    val Orange = Color(0xFFFF9500)
    val Red = Color(0xFFFF3B30)

    val Label = Color(0xFF000000)
    val SecondaryLabel = Color(0xFF3C3C43).copy(alpha = 0.60f)
    val TertiaryLabel = Color(0xFF3C3C43).copy(alpha = 0.30f)
    val Separator = Color(0xFFC6C6C8)
    val Fill = Color(0xFF787880).copy(alpha = 0.12f)

    val Background = Color.White
    val GroupedBackground = Color(0xFFF2F2F7)

    /** 媒体舞台：视频信箱区用纯黑，和系统「照片」一致 */
    val Stage = Color(0xFF000000)
    /** 悬浮在画面上的控件底色 */
    val Scrim = Color(0xFF1C1C1E).copy(alpha = 0.55f)
    val SubtleScrim = Color(0xFF1C1C1E).copy(alpha = 0.32f)
    val AccentFill = Blue.copy(alpha = 0.12f)

    /** 弹层 / 分组卡片 */
    val RCard = 12.dp
    /** 控件：按钮、缩略图、角标 */
    val RControl = 10.dp
    /** 分段控件外框与内部滑块 */
    val RSegment = 9.dp
    val RSegmentThumb = 7.dp
    /** 胶囊（悬浮控件、小徽标） */
    val RPill = 100.dp

    val Gutter = 16.dp
    val Hairline = 0.5.dp
}

private val LightColors = lightColorScheme(
    primary = Ios.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1EDFF),
    onPrimaryContainer = Color(0xFF00376E),
    secondary = Ios.Green,
    onSecondary = Color.White,
    tertiary = Ios.Orange,
    onTertiary = Color.White,
    error = Ios.Red,
    onError = Color.White,
    background = Ios.GroupedBackground,
    onBackground = Ios.Label,
    surface = Ios.Background,
    onSurface = Ios.Label,
    surfaceVariant = Ios.GroupedBackground,
    onSurfaceVariant = Ios.SecondaryLabel,
    surfaceContainer = Ios.Background,
    surfaceContainerLow = Ios.Background,
    surfaceContainerHigh = Color(0xFFEFEFF4),
    outlineVariant = Ios.Separator,
    outline = Ios.TertiaryLabel,
)

/** iOS 文字级别：Large Title 34 / Title2 22 / Headline 17 / Body 17 / Subhead 15 / Footnote 13 */
private val IosTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium),
)

private val IosShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(Ios.RControl),
    medium = RoundedCornerShape(Ios.RCard),
    large = RoundedCornerShape(Ios.RCard),
    extraLarge = RoundedCornerShape(Ios.RCard),
)

@Composable
fun DashcamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = IosTypography,
        shapes = IosShapes,
        content = content,
    )
}

/** 发丝分隔线：iOS 列表内 leading inset，块与块之间 inset = 0 */
@Composable
fun IosSeparator(inset: Dp = Ios.Gutter, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(Ios.Hairline)
            .background(Ios.Separator),
    )
}

/** 全宽白色区块：直角 + 上下发丝线，用于工具条堆叠，不产生嵌套圆角 */
@Composable
fun IosBar(
    modifier: Modifier = Modifier,
    topDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().background(Ios.Background)) {
        if (topDivider) IosSeparator(inset = 0.dp)
        content()
    }
}

/** 分组卡片：整块一个圆角，内部行只用发丝线分隔（Settings 式） */
@Composable
fun IosGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ios.RCard))
            .background(Ios.Background),
        content = content,
    )
}

/** 分组小标题 */
@Composable
fun IosGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Ios.SecondaryLabel,
        modifier = modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

/** UISegmentedControl：外框一个圆角，选中项是内嵌白滑块，取代大小不一的 Chip 群 */
@Composable
fun IosSegmented(
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(Ios.RSegment))
            .background(Ios.Fill)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Ios.RSegmentThumb))
                    .background(if (on) Ios.Background else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (!enabled) Ios.TertiaryLabel else Ios.Label,
                )
            }
        }
    }
}

/** 开关式独立选项（iOS 里的 toggle chip），选中填浅蓝，未选中填灰 */
@Composable
fun IosToggleChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val bg = when {
        !enabled -> Ios.Fill
        selected -> Ios.Blue
        else -> Ios.Fill
    }
    val fg = when {
        !enabled -> Ios.TertiaryLabel
        selected -> Color.White
        else -> Ios.Label
    }
    Box(
        modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(Ios.RSegment))
            .background(bg)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1, color = fg, fontWeight = FontWeight.Medium)
    }
}

/** iOS 主按钮：填充蓝、12 圆角、50 高 */
@Composable
fun IosFilledButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(Ios.RCard))
            .background(if (enabled) Ios.Blue else Ios.Fill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Color.White else Ios.TertiaryLabel,
        )
    }
}

/** 导航栏/工具条上的文字动作，iOS 一律是蓝色纯文字，没有背景块 */
@Composable
fun IosAction(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    strong: Boolean = false,
    fontSize: TextUnit = 17.sp,
    color: Color = Ios.Blue,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(Ios.RControl))
            .clickable(enabled = enabled, onClick = onClick)
            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = fontSize,
            maxLines = 1,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            color = if (enabled) color else Ios.TertiaryLabel,
        )
    }
}

/** 导航栏：左右动作贴边，标题居中（iOS 标准 44pt 高） */
@Composable
fun IosNavBar(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Box(modifier.fillMaxWidth().background(Ios.Background).heightIn(min = 44.dp)) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 72.dp)
                .let { if (onTitleClick != null) it.clip(RoundedCornerShape(Ios.RControl)).clickable(onClick = onTitleClick).padding(horizontal = 6.dp) else it },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Ios.SecondaryLabel, maxLines = 1)
            }
        }
        Row(Modifier.align(Alignment.CenterStart).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, content = leading)
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}





