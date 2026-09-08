package com.dashcam.editor.timeline

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashcam.editor.media.ClipInfo
import com.dashcam.editor.ui.Ios
import com.dashcam.editor.util.Tc
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class DragMode { None, Scrub, In, Out }

/**
 * 拼接时间轴：胶片缩略图（裁剪不压扁）+ 选区外压暗 + 入/出点大热区手柄 + 播放头。
 * 单指拖：抓到手柄（48dp 热区）拖选点，否则拖播放头；拖动中实时预览，松手精确。
 */
@Composable
fun TimelineBar(
    clips: List<ClipInfo>,
    thumbs: Map<Int, List<Pair<Long, ImageBitmap>>>,
    totalMs: Long,
    inMs: Long,
    outMs: Long,
    playheadMs: Long,
    timelineScale: Float,
    fpsAt: (Long) -> Double,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    onInChange: (Long) -> Unit,
    onOutChange: (Long) -> Unit,
    rangeEditable: Boolean = false,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onPrecisionScrub: (Long) -> Unit = onScrubEnd,
    onPrecisionChange: (Boolean) -> Unit = {},
) {
    if (totalMs <= 0) return
    var viewWpx by remember { mutableFloatStateOf(0f) }
    var scrollPx by remember { mutableFloatStateOf(0f) }
    var dragMode by remember { mutableStateOf(DragMode.None) }
    var lastDragMs by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    // 手势 lambda 只组合一次，参数必须经 rememberUpdatedState 才能读到最新值
    val canEditRange by rememberUpdatedState(rangeEditable)
    val curIn by rememberUpdatedState(inMs)
    val curOut by rememberUpdatedState(outMs)
    val curScale by rememberUpdatedState(timelineScale)
    val curTotal by rememberUpdatedState(totalMs)
    val currentScrub by rememberUpdatedState(onScrub)
    val currentScrubEnd by rememberUpdatedState(onScrubEnd)
    val currentInChange by rememberUpdatedState(onInChange)
    val currentOutChange by rememberUpdatedState(onOutChange)
    val currentFpsAt by rememberUpdatedState(fpsAt)
    val currentPrecisionScrub by rememberUpdatedState(onPrecisionScrub)
    val currentPrecisionChange by rememberUpdatedState(onPrecisionChange)
    DisposableEffect(Unit) { onDispose { currentPrecisionChange(false) } }

    // 左右内缩：手柄不贴屏幕边缘，避免拖入点时触发系统返回手势
    val insetPx = with(density) { 16.dp.toPx() }
    val usableWpx = (viewWpx - insetPx * 2).coerceAtLeast(1f)
    val contentWpx = usableWpx * timelineScale
    val pxPerMs = if (totalMs > 0) contentWpx / totalMs else 0f
    val stripH = if (compact) 24.dp else 48.dp
    val barH = if (compact) 44.dp else 72.dp

    fun xOf(ms: Long): Float = insetPx + ms * pxPerMs
    fun msOf(x: Float): Long = ((x - insetPx) / pxPerMs).toLong().coerceIn(0, totalMs)

    fun follow(x: Float) {
        if (viewWpx <= 0f) return
        val rel = x - scrollPx
        if (rel < viewWpx * 0.1f || rel > viewWpx * 0.9f) {
            scrollPx = (x - viewWpx / 2f).coerceIn(0f, (contentWpx - usableWpx).coerceAtLeast(0f))
        }
    }

    LaunchedEffect(playheadMs, timelineScale, viewWpx) {
        if (dragMode == DragMode.None) follow(xOf(playheadMs))
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(barH)
            .systemGestureExclusion()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { viewWpx = it.width.toFloat() }
            .pointerInput(Unit) {
                // 映射在手势内按最新值重算（闭包外的局部函数捕获的是组合时的旧值）
                val perMs = { (viewWpx - insetPx * 2).coerceAtLeast(1f) * curScale / curTotal }
                fun xOfNow(ms: Long): Float = insetPx + ms * perMs()
                fun msOfNow(x: Float): Long = ((x - insetPx) / perMs()).toLong().coerceIn(0, curTotal)
                detectTapGestures { offset ->
                    val x = offset.x + scrollPx
                    val touch = 24.dp.toPx()
                    val dIn = abs(x - xOfNow(curIn))
                    val dOut = abs(x - xOfNow(curOut))
                    if (!canEditRange || (dIn >= touch && dOut >= touch)) {
                        val ms = msOfNow(x)
                        currentScrub(ms)
                        currentScrubEnd(ms)
                    }
                }
            }
            .pointerInput(Unit) {
                var precise = false
                var baseX = 0f
                var baseY = 0f
                var baseMs = 0L
                val perMs = { (viewWpx - insetPx * 2).coerceAtLeast(1f) * curScale / curTotal }
                fun xOfNow(ms: Long): Float = insetPx + ms * perMs()
                fun msOfNow(x: Float): Long = ((x - insetPx) / perMs()).toLong().coerceIn(0, curTotal)
                fun followTo(x: Float) {
                    if (viewWpx <= 0f) return
                    val cw = (viewWpx - insetPx * 2).coerceAtLeast(1f) * curScale
                    val uw = (viewWpx - insetPx * 2).coerceAtLeast(1f)
                    val rel = x - scrollPx
                    if (rel < viewWpx * 0.1f || rel > viewWpx * 0.9f) {
                        scrollPx = (x - viewWpx / 2f).coerceIn(0f, (cw - uw).coerceAtLeast(0f))
                    }
                }
                detectDragGestures(
                    onDragStart = { offset ->
                        precise = false
                        baseX = offset.x
                        baseY = offset.y
                        currentPrecisionChange(false)
                        val x = offset.x + scrollPx
                        val dIn = abs(x - xOfNow(curIn))
                        val dOut = abs(x - xOfNow(curOut))
                        // 两旗靠近时热区各收窄一半，保证能分别抓到；否则抓更近的
                        val near = (dIn + dOut) < 96.dp.toPx()
                        val touch = (if (near) 24.dp else 48.dp).toPx()
                        lastDragMs = if (dIn <= dOut) curIn else curOut
                        dragMode = when {
                            canEditRange && offset.y < 24.dp.toPx() && dIn < touch && dIn <= dOut -> DragMode.In
                            canEditRange && offset.y < 24.dp.toPx() && dOut < touch -> DragMode.Out
                            else -> {
                                val ms = msOfNow(x)
                                lastDragMs = ms
                                currentScrub(ms)
                                DragMode.Scrub
                            }
                        }
                    },
                    onDragEnd = {
                        if (dragMode != DragMode.None) currentScrubEnd(lastDragMs)
                        dragMode = DragMode.None
                        currentPrecisionChange(false)
                    },
                    onDragCancel = {
                        if (dragMode != DragMode.None) currentScrubEnd(lastDragMs)
                        dragMode = DragMode.None
                        currentPrecisionChange(false)
                    },
                ) { change, _ ->
                    change.consume()
                    val x = change.position.x + scrollPx
                    if (dragMode == DragMode.Scrub && !precise && baseY - change.position.y >= 30.dp.toPx()) {
                        precise = true
                        baseX = change.position.x
                        baseMs = lastDragMs
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentPrecisionChange(true)
                    }
                    val ms = if (dragMode == DragMode.Scrub && precise) {
                        (baseMs + ((change.position.x - baseX) / perMs() * 0.12f).toLong()).coerceIn(0, curTotal)
                    } else msOfNow(x)
                    lastDragMs = ms
                    val fps = currentFpsAt(ms).takeIf { it > 1.0 } ?: 30.0
                    val frameMs = (1000.0 / fps).toLong().coerceAtLeast(1L)
                    when (dragMode) {
                        DragMode.In -> {
                            val limit = (curOut - frameMs).coerceAtLeast(0L)
                            val v = Tc.frameToMs(Tc.msToFrame(ms.coerceIn(0, limit), fps), fps).coerceIn(0, limit)
                            lastDragMs = v
                            currentInChange(v)
                            currentScrub(v) // 拖动实时预览
                            followTo(x)
                        }
                        DragMode.Out -> {
                            val limit = (curIn + frameMs).coerceAtMost(curTotal)
                            val v = Tc.frameToMs(Tc.msToFrame(ms.coerceIn(limit, curTotal), fps), fps).coerceIn(limit, curTotal)
                            lastDragMs = v
                            currentOutChange(v)
                            currentScrub(v)
                            followTo(x)
                        }
                        DragMode.Scrub -> {
                            if (precise) currentPrecisionScrub(ms) else currentScrub(ms)
                            followTo(xOfNow(ms))
                        }
                        DragMode.None -> Unit
                    }
                }
            },
    ) {
        if (contentWpx <= 0f) return
        val contentWdp = with(density) { contentWpx.toDp() }
        Box(
            Modifier
                .width(contentWdp)
                .fillMaxHeight()
                .offset { IntOffset((insetPx - scrollPx).roundToInt(), 0) },
        ) {
            // 胶片：各片段按时长占比分宽，缩略图裁剪不压扁
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(stripH),
            ) {
                clips.forEachIndexed { i, clip ->
                    Box(
                        Modifier
                            .weight(clip.durationMs.toFloat())
                            .fillMaxHeight()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        val list = thumbs[i]
                        if (list.isNullOrEmpty()) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                        } else {
                            Row(Modifier.fillMaxSize()) {
                                list.forEach { (_, bmp) ->
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 选区外压暗
            val shade = Color.Black.copy(alpha = 0.55f)
            val leftW = with(density) { (inMs * pxPerMs).coerceAtLeast(0f).toDp() }
            val rightX = with(density) { (outMs * pxPerMs).toDp() }
            val rightW = with(density) { (contentWpx - (outMs * pxPerMs)).coerceAtLeast(0f).toDp() }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .width(leftW)
                    .height(stripH)
                    .background(shade),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(rightX, 0.dp)
                    .width(rightW)
                    .height(stripH)
                    .background(shade),
            )

            // 入/出点手柄：顶部实色标签 + 竖线（拖拽热区见手势判定）；贴边时标签翻到另一侧防裁切
            HandleFlag(
                x = (inMs * pxPerMs),
                label = "入",
                color = MaterialTheme.colorScheme.primary,
                labelOnLeft = inMs > totalMs / 25,
            )
            HandleFlag(
                x = (outMs * pxPerMs),
                label = "出",
                color = MaterialTheme.colorScheme.tertiary,
                labelOnLeft = outMs > totalMs / 25,
            )

            // 播放头：只表示当前找帧位置。入出点处于锁定状态时，拖动这里不会改变裁剪范围。
            Box(
                Modifier
                    .offset { IntOffset((playheadMs * pxPerMs).roundToInt() - 2, 0) }
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * 入/出点旗：竖线精确位于打点 x（与播放头同一坐标语义），
 * 入点标签向线左侧展开、出点标签向线右侧展开（Premiere 式），互不遮挡。
 */
@Composable
private fun HandleFlag(x: Float, label: String, color: Color, labelOnLeft: Boolean) {
    val labelWidth = with(LocalDensity.current) { 26.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset { IntOffset(x.roundToInt() - 1, 0) }.width(3.dp).fillMaxHeight().background(color))
        Box(
            Modifier.offset { IntOffset((x - if (labelOnLeft) labelWidth else 0f).roundToInt(), 0) }
                .width(26.dp).height(22.dp).clip(RoundedCornerShape(Ios.RSegmentThumb)).background(color),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1) }
    }
}
