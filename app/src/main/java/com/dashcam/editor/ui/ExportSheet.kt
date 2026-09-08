package com.dashcam.editor.ui

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
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
    val preferences = remember { context.getSharedPreferences("export_options", Context.MODE_PRIVATE) }

    var resSel by remember { mutableIntStateOf(preferences.getInt("resolution", 0).coerceIn(RES_OPTIONS.indices)) }
    var fpsSel by remember { mutableIntStateOf(preferences.getInt("fps", 0).coerceIn(FPS_OPTIONS.indices)) }
    var quality by remember { mutableStateOf(Quality.entries[preferences.getInt("quality", Quality.MEDIUM.ordinal).coerceIn(Quality.entries.indices)]) }
    var hevc by remember { mutableStateOf(preferences.getBoolean("hevc", false)) }
    var fastCopy by remember { mutableStateOf(preferences.getBoolean("fastCopy", false)) }
    var useHw by remember { mutableStateOf(preferences.getBoolean("useHw", true)) }
    var hwDecode by remember { mutableStateOf(preferences.getBoolean("hwDecode", true)) }
    var exporting by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !exporting })
    LaunchedEffect(resSel, fpsSel, quality, hevc, fastCopy, useHw, hwDecode) {
        preferences.edit().putInt("resolution", resSel).putInt("fps", fpsSel).putInt("quality", quality.ordinal)
            .putBoolean("hevc", hevc).putBoolean("fastCopy", fastCopy)
            .putBoolean("useHw", useHw).putBoolean("hwDecode", hwDecode).apply()
    }
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
        Column(Modifier.fillMaxHeight(0.9f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Ios.Gutter), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("导出", style = MaterialTheme.typography.headlineSmall)
                    Text("选区 ${Tc.formatShort(app.inMs)} → ${Tc.formatShort(app.outMs)} · ${Tc.formatShort(selDurMs)} · $entriesCount 段",
                        style = MaterialTheme.typography.bodySmall, color = Ios.SecondaryLabel)
                }
                IosAction("关闭", enabled = !exporting, fontSize = 15.sp, onClick = onDismiss)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Ios.Gutter),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IosGroup {
                    PickerCell("裁剪方式", listOf("精确裁剪", "快速裁剪"), if (fastCopy) 1 else 0, enabled = !exporting) { fastCopy = it == 1 }
                    Text(if (fastCopy) "速度快，剪切点按关键帧对齐，保留原始分辨率与帧率。" else "按选区精确裁剪，可调整输出画质。",
                        modifier = Modifier.padding(horizontal = Ios.Gutter).padding(bottom = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = Ios.SecondaryLabel)
                }
                IosGroup {
                    PickerCell("分辨率", RES_LABELS, resSel, enabled = !fastCopy && !exporting) { resSel = it }
                    IosSeparator()
                    PickerCell("帧率", FPS_LABELS, fpsSel, enabled = !fastCopy && !exporting) { fpsSel = it }
                    IosSeparator()
                    PickerCell("画质", listOf("高", "中", "低"), quality.ordinal, enabled = !fastCopy && !exporting) { quality = Quality.entries[it] }
                }
                IosAction(if (advanced) "高级设置 ▴" else "高级设置 ▾", fontSize = 15.sp, enabled = !exporting) { advanced = !advanced }
                if (advanced) {
                    IosGroup {
                        SwitchCell(
                            title = "硬件解码", detail = if (hwDecoderAvailable) "加快高分辨率视频处理，失败时自动切换" else "当前设备不可用",
                            checked = hwDecode && hwDecoderAvailable && !fastCopy,
                            enabled = hwDecoderAvailable && !fastCopy && !exporting, onCheckedChange = { hwDecode = it },
                        )
                        IosSeparator()
                        SwitchCell(
                            title = "硬件编码", detail = if (hwEncoderAvailable) "加快导出速度，失败时自动切换" else "当前设备不可用",
                            checked = useHw && hwEncoderAvailable && !fastCopy,
                            enabled = hwEncoderAvailable && !fastCopy && !exporting, onCheckedChange = { useHw = it },
                        )
                        IosSeparator()
                        SwitchCell(
                            title = "HEVC 编码", detail = "更省体积，部分播放器可能不支持",
                            checked = hevc && useHw && hw.getOrElse(1) { false } && !fastCopy,
                            enabled = useHw && hw.getOrElse(1) { false } && !fastCopy && !exporting, onCheckedChange = { hevc = it },
                        )
                    }
                }
                if (!fastCopy && resSel == 0 && srcHeight >= 2000) {
                    Text("原始分辨率 ${srcHeight}p 导出耗时较长；可选择 1080p 加快处理，导出后检查关键细节是否清晰。",
                        style = MaterialTheme.typography.bodySmall, color = Ios.SecondaryLabel)
                }
                error?.let { Text(it, color = Ios.Red, style = MaterialTheme.typography.bodySmall) }
            }
            IosBar {
                Column(Modifier.padding(horizontal = Ios.Gutter, vertical = 12.dp)) {
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
                        IosFilledButton("导出 ${Tc.formatShort(selDurMs)} 视频") {
                            exporting = true
                            progress = 0f
                            speed = 0.0
                            stage = ""
                            error = null
                            val options = ExportOptions(
                                targetHeight = RES_OPTIONS[resSel], targetFps = FPS_OPTIONS[fpsSel], quality = quality,
                                useHw = useHw && hwEncoderAvailable, hevc = hevc && useHw && hw.getOrElse(1) { false },
                                fastCopy = fastCopy, hwDecode = hwDecode && hwDecoderAvailable,
                            )
                            job = scope.launch {
                                try {
                                    ExportEngine.export(
                                        context,
                                        app.clips,
                                        app.inMs,
                                        app.outMs,
                                        options,
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

                }
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
