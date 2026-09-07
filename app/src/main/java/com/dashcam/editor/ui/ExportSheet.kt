package com.dashcam.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashcam.editor.export.ExportEngine
import com.dashcam.editor.export.ExportOptions
import com.dashcam.editor.export.ExportResult
import com.dashcam.editor.export.Quality
import com.dashcam.editor.media.PlayerController
import com.dashcam.editor.util.Tc
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private val RES_OPTIONS = listOf<Int?>(null, 2160, 1440, 1080, 720, 480)
private val RES_LABELS = listOf("原始", "4K", "1440p", "1080p", "720p", "480p")
private val FPS_OPTIONS = listOf<Int?>(null, 60, 50, 30, 25, 24, 15)
private val FPS_LABELS = listOf("原始", "60", "50", "30", "25", "24", "15")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    app: AppModel,
    controller: PlayerController,
    hw: BooleanArray,
    snackbar: androidx.compose.material3.SnackbarHostState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    var resSel by remember { mutableIntStateOf(0) }
    var fpsSel by remember { mutableIntStateOf(0) }
    var quality by remember { mutableStateOf(Quality.MEDIUM) }
    var hevc by remember { mutableStateOf(false) }
    var fastCopy by remember { mutableStateOf(false) }
    var useHw by remember { mutableStateOf(true) }
    var hwDecode by remember { mutableStateOf(true) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableDoubleStateOf(0.0) }
    var stage by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<ExportResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val entriesCount = ExportEngine.selectionEntries(app.clips, app.inMs, app.outMs).size
    val srcHeight = app.clips.firstOrNull()?.displayHeight ?: 0
    val selDurMs = app.outMs - app.inMs
    val hwEncoderAvailable = hw.getOrElse(0) { false } || hw.getOrElse(1) { false }
    val hwDecoderAvailable = hw.getOrElse(2) { false } || hw.getOrElse(3) { false }

    ModalBottomSheet(
        onDismissRequest = { if (!exporting) onDismiss() },
        sheetState = sheetState,
        containerColor = Ios.GroupedBackground,
    ) {
        Column(
            Modifier.padding(horizontal = Ios.Gutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text("导出", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "选区 ${Tc.formatShort(app.inMs)} → ${Tc.formatShort(app.outMs)} · " +
                        "时长 ${Tc.formatShort(selDurMs)} · 跨 $entriesCount 段",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ios.SecondaryLabel,
                )
            }
            IosGroup {
                PickerCell("分辨率", RES_LABELS, resSel, enabled = !fastCopy) { resSel = it }
                IosSeparator()
                PickerCell("帧率", FPS_LABELS, fpsSel, enabled = !fastCopy) { fpsSel = it }
                IosSeparator()
                PickerCell("画质", listOf("高", "中", "低"), quality.ordinal, enabled = !fastCopy) {
                    quality = Quality.entries[it]
                }
            }

            Column {
                IosGroupLabel("编码")
                IosGroup {
                    SwitchCell(
                        title = "快速模式（流复制）",
                        detail = "秒级完成，但剪切点会对齐到最近关键帧，分辨率与帧率跟随原始",
                        checked = fastCopy,
                        onCheckedChange = { fastCopy = it },
                    )
                    IosSeparator()
                    SwitchCell(
                        title = "硬件解码",
                        detail = if (hwDecoderAvailable) "4K 片源提速最明显；失败会自动退回软解" else "本机 ffmpeg 无 MediaCodec 解码器",
                        checked = hwDecode && hwDecoderAvailable && !fastCopy,
                        enabled = hwDecoderAvailable && !fastCopy,
                        onCheckedChange = { hwDecode = it },
                    )
                    IosSeparator()
                    SwitchCell(
                        title = "硬件编码",
                        detail = if (hwEncoderAvailable) "MediaCodec 编码，失败会自动退回 x264" else "本机 ffmpeg 无 MediaCodec 编码器",
                        checked = useHw && hwEncoderAvailable && !fastCopy,
                        enabled = hwEncoderAvailable && !fastCopy,
                        onCheckedChange = { useHw = it },
                    )
                    IosSeparator()
                    SwitchCell(
                        title = "HEVC（更省体积）",
                        detail = "需要硬件编码，部分播放器兼容性较差",
                        checked = hevc && useHw && hw.getOrElse(1) { false } && !fastCopy,
                        enabled = useHw && hw.getOrElse(1) { false } && !fastCopy,
                        onCheckedChange = { hevc = it },
                    )
                }
            }

            if (!fastCopy && resSel == 0 && srcHeight >= 2000) {
                Text(
                    "原始分辨率（${srcHeight}p）重编码最慢。举报取证选 1080p 通常快 3～4 倍，车牌依然可辨；要原画质细节则用截图。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ios.Orange,
                )
            }
            if (exporting) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(Ios.RPill)),
                        trackColor = Ios.Fill,
                        color = Ios.Blue,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildProgressLabel(stage, progress, speed, selDurMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ios.SecondaryLabel,
                            modifier = Modifier.weight(1f),
                        )
                        IosAction("取消", fontSize = 15.sp, color = Ios.Red) {
                            job?.cancel()
                            ExportEngine.cancelAll()
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(2.dp))
                IosFilledButton("开始导出") {
                    exporting = true
                    progress = 0f
                    speed = 0.0
                    stage = ""
                    error = null
                    job = scope.launch {
                        try {
                            ExportEngine.export(
                                context,
                                app.clips,
                                app.inMs,
                                app.outMs,
                                ExportOptions(
                                    targetHeight = RES_OPTIONS[resSel],
                                    targetFps = FPS_OPTIONS[fpsSel],
                                    quality = quality,
                                    useHw = useHw,
                                    hevc = hevc,
                                    fastCopy = fastCopy,
                                    hwDecode = hwDecode,
                                ),
                                hw,
                            ) { st ->
                                progress = st.progress
                                speed = st.speed
                                stage = st.stage
                            }
                                .onSuccess { result = it }
                                .onFailure { error = it.message ?: "导出失败" }
                        } finally {
                            exporting = false
                        }
                    }
                }
            }

            error?.let {
                Text(it, color = Ios.Red, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    result?.let { r ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text("导出完成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已保存到 相册/Movies/dashcam-editor")
                    Text(r.displayName, style = MaterialTheme.typography.bodySmall)
                    Text(
                        String.format(Locale.US, "%.1f MB", r.sizeBytes / 1048576.0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { result = null }) { Text("完成") }
            },
            dismissButton = {
                TextButton(onClick = { shareUri(context, r.uri, "video/mp4") }) { Text("分享") }
            },
        )
    }
}

/** 「当前路径 · 42% · 1.8× 实时 · 约剩 11s」 */
private fun buildProgressLabel(stage: String, progress: Float, speed: Double, selDurMs: Long): String {
    val parts = ArrayList<String>()
    if (stage.isNotBlank()) parts += stage
    parts += "${(progress * 100).roundToInt()}%"
    if (speed > 0.01) {
        parts += String.format(Locale.US, "%.2f× 实时", speed)
        val remainSec = (1f - progress) * (selDurMs / 1000.0) / speed
        if (remainSec.isFinite() && remainSec > 0) parts += "约剩 ${remainSec.roundToInt()}s"
    }
    return parts.joinToString(" · ")
}

/** 分组内的分段选择单元：标题 + 全宽分段控件 */
@Composable
private fun PickerCell(
    title: String,
    labels: List<String>,
    selected: Int,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Ios.Gutter, vertical = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) Ios.SecondaryLabel else Ios.TertiaryLabel,
        )
        Spacer(Modifier.height(6.dp))
        IosSegmented(labels, selected, enabled = enabled, onSelect = onSelect)
    }
}
/** iOS 设置项：左标题+说明，右开关 */
@Composable
private fun SwitchCell(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = Ios.Gutter, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) Ios.Label else Ios.TertiaryLabel,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Ios.SecondaryLabel)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Ios.Green,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Ios.Fill,
                uncheckedBorderColor = Color.Transparent,
                disabledCheckedTrackColor = Ios.Fill,
                disabledUncheckedTrackColor = Ios.Fill,
            ),
        )
    }
}
