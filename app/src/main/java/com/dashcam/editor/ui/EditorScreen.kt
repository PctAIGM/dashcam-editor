package com.dashcam.editor.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.dashcam.editor.export.ExportEngine
import com.dashcam.editor.media.MediaLibrary
import com.dashcam.editor.media.PlayerController
import com.dashcam.editor.shot.FrameShot
import com.dashcam.editor.shot.ViewportCrop
import com.dashcam.editor.timeline.Filmstrip
import com.dashcam.editor.timeline.TimelineBar
import com.dashcam.editor.util.Tc
import com.dashcam.editor.zoom.ZoomState
import com.dashcam.editor.zoom.zoomGesture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
@Composable
fun EditorScreen(app: AppModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(app.clips) { PlayerController(context, app.clips) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var posMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
    val zoomState = remember { ZoomState() }
    val timelineScale = 1f
    var thumbs by remember { mutableStateOf<Map<Int, List<Pair<Long, ImageBitmap>>>>(emptyMap()) }
    val hw = remember { mutableStateOf(booleanArrayOf(false, false, false, false)) }
    var showExport by remember { mutableStateOf(false) }
    var showFragments by remember { mutableStateOf(false) }
    var galleryOpen by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var shotBusy by remember { mutableStateOf(false) }
    var appending by remember { mutableStateOf(false) }
    var showInsertPicker by remember { mutableStateOf(false) }
    var insertProgress by remember { mutableStateOf("") }
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    val activity = context as Activity
    val entryConfiguration = LocalConfiguration.current
    val entryOrientation = remember {
        if (entryConfiguration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    DisposableEffect(Unit) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        onDispose {
            activity.requestedOrientation = entryOrientation
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(fullScreen) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullScreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            // Explicitly request the orientation from before fullscreen. Waiting for
            // composable disposal alone can leave the activity stuck in landscape.
            activity.requestedOrientation = entryOrientation
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun fpsAt(ms: Long): Double = controller.clipAtGlobal(ms).fps.takeIf { it > 1.0 } ?: 30.0

    LaunchedEffect(controller) {
        pendingSeek?.let { controller.seekSettle(it) }
        pendingSeek = null
        while (true) {
            posMs = controller.globalPositionMs()
            isPlaying = controller.player.isPlaying
            delay(50)
        }
    }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val rot = videoSize.unappliedRotationDegrees
                val w = if (rot % 180 == 90) videoSize.height else videoSize.width
                val h = if (rot % 180 == 90) videoSize.width else videoSize.height
                if (w > 0 && h > 0) videoAspect = w.toFloat() / h
            }
        }
        controller.player.addListener(listener)
        onDispose { controller.player.removeListener(listener) }
    }

    LaunchedEffect(app.clips) { thumbs = Filmstrip.generate(app.clips) }
    LaunchedEffect(Unit) { hw.value = ExportEngine.detectCodecs() }

    BackHandler { if (fullScreen) fullScreen = false else onBack() }
    fun takeShot() {
        if (shotBusy) return
        controller.player.pause()
        shotBusy = true
        val clip = controller.currentClip()
        val idx = controller.player.currentMediaItemIndex
        val shotPosition = controller.globalPositionMs()
        val localMs = (shotPosition - controller.offsetOf(idx)).coerceAtMost((clip.durationMs - 1000.0 / clip.fps).toLong().coerceAtLeast(0))
        val crop = ViewportCrop(zoomState.scale, zoomState.offsetX / zoomState.viewW, zoomState.offsetY / zoomState.viewH)
        val frameNo = Tc.msToFrame(localMs, clip.fps)
        val label = Tc.format(shotPosition, clip.fps)
        val clipName = clip.displayName
        scope.launch {
            FrameShot.capture(context, clip, localMs, frameNo, crop)
                .onSuccess { (uri, name) ->
                    scope.launch { snackbar.showSnackbar("已按当前可见区域保存截图") }
                    app.shots = app.shots + ShotItem(
                        uri = uri,
                        displayName = name,
                        clipName = clipName,
                        timeLabel = label,
                        frameNo = frameNo,
                        thumb = decodeImageBitmap(context, uri, 256),
                    )
                }
                .onFailure {
                    scope.launch { snackbar.showSnackbar(it.message ?: "截图失败") }
                }
            shotBusy = false
        }
    }

    if (showInsertPicker) {
        InsertVideoPicker(
            clips = app.clips,
            busy = appending,
            progress = insertProgress,
            onDismiss = { showInsertPicker = false },
            onInsert = { index, uris ->
                if (!appending) {
                    appending = true
                    scope.launch {
                        try {
                            val list = MediaLibrary.import(
                                context, uris,
                                onProgress = { done, total, name -> insertProgress = "加载 $done/$total $name" },
                                onError = { error -> scope.launch { snackbar.showSnackbar(error) } },
                            )
                            if (list.isNotEmpty()) {
                                val boundary = app.clips.take(index).sumOf { it.durationMs }
                                val added = list.sumOf { it.durationMs }
                                val current = controller.globalPositionMs()
                                pendingSeek = if (current >= boundary) current + added else current
                                app.insertClips(index, list)
                                showInsertPicker = false
                            }
                        } finally {
                            appending = false
                        }
                    }
                }
            },
        )
    }

    // 入出点只用于粗剪，找清晰帧由下方播放头、逐帧按钮和截图按钮完成。
    val markIn = { app.inMs = posMs.coerceIn(0, app.outMs - 1) }
    val markOut = { app.outMs = posMs.coerceIn(app.inMs + 1, controller.totalDurationMs) }

    fun seek(ms: Long) {
        controller.player.pause()
        controller.seekSettle(ms)
        posMs = controller.globalPositionMs()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Ios.GroupedBackground,
    ) { pad ->
        Column((if (fullScreen) Modifier.safeDrawingPadding() else Modifier.padding(pad)).fillMaxSize()) {
            if (!fullScreen) {
                IosNavBar(
                    title = "剪辑",
                    subtitle = "${app.clips.size} 段 · ${Tc.formatShort(app.totalMs)}",
                    onTitleClick = { showFragments = true },
                    leading = {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(Ios.RControl))
                                .clickable(onClick = onBack)
                                .heightIn(min = 44.dp)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.ChevronLeft, "返回", tint = Ios.Blue, modifier = Modifier.size(26.dp))
                            Text("视频", fontSize = 17.sp, color = Ios.Blue)
                        }
                    },
                    trailing = {
                        BadgedBox(badge = { if (app.shots.isNotEmpty()) Badge { Text("${app.shots.size}") } }) {
                            IconButton(onClick = { galleryOpen = true }) {
                                Icon(Icons.Filled.PhotoLibrary, "截图库", tint = Ios.Blue, modifier = Modifier.size(22.dp))
                            }
                        }
                        IosAction("导出", strong = true) { showExport = true }
                    },
                )
                IosSeparator(inset = 0.dp)
            }

            // 找帧时视频是主区域，所有裁剪标记都留在时间轴上，不会被误拖动。
            Box(Modifier.weight(1f).fillMaxWidth().background(Ios.Stage)) {
                PlayerArea(Modifier.fillMaxSize(), controller, zoomState, posMs, fpsAt(posMs), { controller.togglePlay() }, videoAspect)
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(Ios.RPill))
                        .background(Ios.Scrim),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StageAction("−") { zoomState.zoomBy(1f / 1.5f) }
                    StageAction("%.1f×".format(zoomState.scale)) { zoomState.reset() }
                    StageAction("＋") { zoomState.zoomBy(1.5f) }
                }
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(Ios.RPill))
                        .background(Ios.Scrim),
                ) {
                    StageAction(if (fullScreen) "退出全屏" else "全屏") { fullScreen = !fullScreen }
                }
            }

            if (!fullScreen) {
                IosBar {
                    SelectionRow(
                        inMs = app.inMs,
                        outMs = app.outMs,
                        onMarkIn = markIn,
                        onMarkOut = markOut,
                        onReset = { app.inMs = 0; app.outMs = app.totalMs },
                    )
                    IosSeparator()
                    Row(
                        Modifier.fillMaxWidth().height(80.dp).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TimelineBar(
                            modifier = Modifier.weight(1f),
                            clips = app.clips,
                            thumbs = thumbs,
                            totalMs = app.totalMs,
                            inMs = app.inMs,
                            outMs = app.outMs,
                            playheadMs = posMs,
                            timelineScale = timelineScale,
                            rangeEditable = false,
                            fpsAt = { fpsAt(it) },
                            onScrub = {
                                controller.player.pause()
                                controller.seekScrub(it)
                            },
                            onScrubEnd = { controller.seekSettle(it) },
                            onInChange = { app.inMs = it },
                            onOutChange = { app.outMs = it },
                        )
                        Column(
                            Modifier.width(56.dp).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Ios.Fill)
                                    .clickable(enabled = !appending) { controller.player.pause(); showInsertPicker = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (appending) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ios.Blue)
                                } else {
                                    Icon(Icons.Filled.Add, "选择视频和拼接位置", tint = Ios.Blue)
                                }
                            }
                            Text("拼接", fontSize = 11.sp, color = Ios.SecondaryLabel, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            } else {
                IosBar {
                    PrecisionScrubber(
                        positionMs = posMs,
                        totalMs = app.totalMs,
                        fpsAt = { fpsAt(it) },
                        onScrub = { target, precise ->
                            controller.player.pause()
                            if (precise) controller.seekSettle(target) else controller.seekScrub(target)
                        },
                        onSettle = { seek(it) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
            }

            IosBar {
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IosAction("−1秒", fontSize = 15.sp) { seek(posMs - 1000) }
                    IconButton(onClick = { controller.stepFrames(-1) }) {
                        Icon(Icons.Filled.ChevronLeft, "上一帧", tint = Ios.Blue, modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = { controller.togglePlay() }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            "播放/暂停",
                            tint = Ios.Blue,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    IconButton(onClick = { controller.stepFrames(1) }) {
                        Icon(Icons.Filled.ChevronRight, "下一帧", tint = Ios.Blue, modifier = Modifier.size(30.dp))
                    }
                    IosAction("+1秒", fontSize = 15.sp) { seek(posMs + 1000) }
                    IconButton(onClick = { takeShot() }, enabled = !shotBusy) {
                        if (shotBusy) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.PhotoCamera, "按当前画面截图", tint = Ios.Blue, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
    }

    if (showExport) {
        ExportSheet(
            app = app,
            controller = controller,
            hw = hw.value,
            snackbar = snackbar,
            onDismiss = { showExport = false },
        )
    }
    if (showFragments) {
        FragmentSheet(app = app, onDismiss = { showFragments = false })
    }
    if (galleryOpen) {
        GalleryDialog(
            app = app,
            onOpenViewer = { viewerIndex = it },
            onDismiss = { galleryOpen = false },
        )
    }
    viewerIndex?.let { idx ->
        ShotViewerDialog(app = app, index = idx, onDismiss = { viewerIndex = null })
    }
}

/** 悬浮在画面上的胶囊按钮内的一项 */
@Composable
private fun StageAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(Ios.RPill))
            .clickable(onClick = onClick)
            .heightIn(min = 34.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 选区一行：左侧读数，右侧打点动作，全部平铺在同一条白色工具条里 */
@Composable
private fun SelectionRow(
    inMs: Long,
    outMs: Long,
    onMarkIn: () -> Unit,
    onMarkOut: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(start = Ios.Gutter, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("选区 ${Tc.formatShort(outMs - inMs)}", style = MaterialTheme.typography.titleSmall)
            Text(
                "入 ${Tc.formatShort(inMs)}  出 ${Tc.formatShort(outMs)}",
                fontSize = 11.sp,
                color = Ios.SecondaryLabel,
                maxLines = 1,
            )
        }
        IosAction("设入点", fontSize = 15.sp, onClick = onMarkIn)
        IosAction("设出点", fontSize = 15.sp, onClick = onMarkOut)
        IosAction("重置", fontSize = 15.sp, onClick = onReset)
    }
}

/** 片段管理：顺序即拼接顺序，可移除 */
@Composable
private fun FragmentSheet(app: AppModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("片段（${app.clips.size}）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "按以下顺序拼接，时间轴「＋」可选择插入位置",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ios.SecondaryLabel,
                )
                app.clips.forEachIndexed { i, clip ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", color = Ios.Blue, fontWeight = FontWeight.SemiBold)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(clip.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                "${Tc.formatShort(clip.durationMs)} · ${clip.displayWidth}×${clip.displayHeight}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ios.SecondaryLabel,
                            )
                        }
                        if (app.clips.size > 1) {
                            IconButton(onClick = {
                                app.clips = app.clips.filterIndexed { idx, _ -> idx != i }
                                app.validateRange()
                            }) { Icon(Icons.Filled.Close, "移除", tint = Ios.SecondaryLabel) }
                        }
                    }
                }
            }
        },
    )
}

/** 视频区：轻触播放/暂停、双指缩放平移（播放中同样生效）、双击复位 */
@Composable
private fun PlayerArea(
    modifier: Modifier,
    controller: PlayerController,
    zoomState: ZoomState,
    posMs: Long,
    fpsNow: Double,
    onTogglePlay: () -> Unit,
    aspect: Float = 16f / 9f,
) {
    BoxWithConstraints(modifier) {
        val ratio = aspect.coerceIn(0.1f, 10f)
        val videoWidth = minOf(maxWidth, maxHeight * ratio)
        Box(
            Modifier
                .width(videoWidth)
                .height(videoWidth / ratio)
                .align(Alignment.Center)
                .clipToBounds()
                .onSizeChanged { zoomState.resize(it.width.toFloat(), it.height.toFloat()) }
                .zoomGesture(zoomState, onTap = onTogglePlay),
        ) {
            AndroidView(
                factory = { TextureView(it) },
                update = { controller.player.setVideoTextureView(it) },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoomState.scale
                    scaleY = zoomState.scale
                    translationX = zoomState.offsetX
                    translationY = zoomState.offsetY
                },
            )
        }
        Text(
            "${Tc.format(posMs, fpsNow)} · 帧 ${Tc.msToFrame(posMs, fpsNow)}",
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(Ios.RPill))
                .background(Ios.Scrim)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/**
 * 小米相册式找帧条：横向拖动用于粗定位，手指按住并向上拖后进入精细模式。
 * 精细模式以进入时的位置为锚点，后续横向移动只按 12% 的速度换算时间，
 * 这样可以在长视频里慢慢比较相邻帧；松手后再做一次精确 seek。
 */
@Composable
private fun PrecisionScrubber(
    positionMs: Long,
    totalMs: Long,
    fpsAt: (Long) -> Double,
    onScrub: (Long, Boolean) -> Unit,
    onSettle: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (totalMs <= 0) return
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var precise by remember { mutableStateOf(false) }
    var dragMs by remember { mutableLongStateOf(positionMs) }
    val currentTotal by rememberUpdatedState(totalMs)
    val currentScrub by rememberUpdatedState(onScrub)
    val currentSettle by rememberUpdatedState(onSettle)
    val currentFpsAt by rememberUpdatedState(fpsAt)

    LaunchedEffect(positionMs) {
        if (!dragging) dragMs = positionMs
    }

    val padPx = with(density) { 12.dp.toPx() }
    val precisionTriggerPx = with(density) { 30.dp.toPx() }
    val trackColor = Ios.Fill
    val progressColor = Ios.Blue.copy(alpha = 0.35f)
    val thumbColor = Ios.Blue
    fun msAtX(x: Float, width: Float): Long {
        val usable = (width - padPx * 2).coerceAtLeast(1f)
        return (((x - padPx) / usable) * currentTotal).toLong().coerceIn(0, currentTotal)
    }

    val shownMs = if (dragging) dragMs else positionMs
    Box(
        modifier
            .height(48.dp)
            .drawBehind {
                val y = size.height / 2f
                val trackHeight = 8.dp.toPx()
                val left = padPx
                val right = size.width - padPx
                val usable = (right - left).coerceAtLeast(1f)
                val progress = (shownMs.toFloat() / currentTotal.toFloat()).coerceIn(0f, 1f)
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, y - trackHeight / 2f),
                    size = Size(usable, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
                drawRoundRect(
                    color = progressColor,
                    topLeft = Offset(left, y - trackHeight / 2f),
                    size = Size(usable * progress, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
                drawCircle(
                    color = thumbColor,
                    radius = 10.dp.toPx(),
                    center = Offset(left + usable * progress, y),
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val target = msAtX(offset.x, size.width.toFloat())
                    currentScrub(target, false)
                    currentSettle(target)
                }
            }
            .pointerInput(Unit) {
                var baseX = 0f
                var baseY = 0f
                var baseMs = 0L
                detectDragGestures(
                    onDragStart = { offset ->
                        baseX = offset.x
                        baseY = offset.y
                        baseMs = msAtX(offset.x, size.width.toFloat())
                        dragMs = baseMs
                        precise = false
                        dragging = true
                        currentScrub(baseMs, false)
                    },
                    onDragEnd = {
                        currentSettle(dragMs)
                        dragging = false
                        precise = false
                    },
                    onDragCancel = {
                        currentSettle(dragMs)
                        dragging = false
                        precise = false
                    },
                ) { change, _ ->
                    change.consume()
                    if (!precise && baseY - change.position.y >= precisionTriggerPx) {
                        // From this point on, keep the current frame as the anchor;
                        // the upward motion itself must not jump the video.
                        precise = true
                        baseX = change.position.x
                        baseMs = dragMs
                    }
                    val sensitivity = if (precise) 0.12f else 1f
                    val usable = (size.width - padPx * 2).coerceAtLeast(1f)
                    val delta = ((change.position.x - baseX) / usable * currentTotal * sensitivity).toLong()
                    dragMs = (baseMs + delta).coerceIn(0, currentTotal)
                    currentScrub(dragMs, precise)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${Tc.format(shownMs, currentFpsAt(shownMs))} · 帧 ${Tc.msToFrame(shownMs, currentFpsAt(shownMs))}${if (precise) "  精细定位" else "  上移进入精细"}",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Ios.Label,
            modifier = Modifier
                .clip(RoundedCornerShape(Ios.RPill))
                .background(Ios.Background)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}














