package com.dashcam.editor.ui

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dashcam.editor.media.ClipInfo
import com.dashcam.editor.media.MediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 视频信息详情弹层：缩略图预览 + 媒体参数 + 删除入口；参数在打开时实时探测 */
@Composable
fun VideoInfoSheet(video: LibVideo, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var probing by remember(video.uri) { mutableStateOf(true) }
    var info by remember(video.uri) { mutableStateOf<ClipInfo?>(null) }
    var thumb by remember(video.uri) { mutableStateOf<ImageBitmap?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(video.uri) {
        withContext(Dispatchers.IO) {
            info = runCatching { MediaLibrary.probeUri(context, video.uri, video.displayName) }.getOrNull()
            thumb = loadThumbnail(context, video.uri)
            probing = false
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Ios.Background,
            title = { Text("删除视频") },
            text = { Text("“${video.displayName}”将从相册中删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = Ios.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Ios.Blue) } },
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Ios.RCard))
                .background(Ios.GroupedBackground),
        ) {
            Box(Modifier.fillMaxWidth().height(150.dp).background(Ios.Stage), contentAlignment = Alignment.Center) {
                thumb?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: CircularProgressIndicator(color = Ios.Blue)
            }
            Text(
                video.displayName,
                Modifier.fillMaxWidth().padding(horizontal = Ios.Gutter, vertical = 12.dp),
                style = MaterialTheme.typography.titleSmall,
                color = Ios.Label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Column(Modifier.padding(horizontal = Ios.Gutter).padding(bottom = 16.dp)) {
                IosGroup {
                    InfoRow("时长", fmtDuration(video.durationMs))
                    IosSeparator(inset = 12.dp)
                    InfoRow("分辨率", resolutionLabel(video, info, probing))
                    IosSeparator(inset = 12.dp)
                    InfoRow("帧率", detailLabel(probing, info?.fps?.takeIf { it > 0 }?.let(::fmtFps)))
                    IosSeparator(inset = 12.dp)
                    InfoRow("编码", detailLabel(probing, info?.videoMime?.let(::mimeLabel)))
                    IosSeparator(inset = 12.dp)
                    InfoRow("音轨", detailLabel(probing, info?.hasAudio?.let { if (it) "有" else "无" }))
                    IosSeparator(inset = 12.dp)
                    InfoRow("大小", if (video.sizeBytes > 0) Formatter.formatShortFileSize(context, video.sizeBytes) else "—")
                    IosSeparator(inset = 12.dp)
                    InfoRow("添加时间", SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(video.dateAdded * 1000)))
                }
                Spacer(Modifier.height(10.dp))
                IosGroup {
                    ActionRow("删除视频", Ios.Red) { confirmDelete = true }
                }
                Spacer(Modifier.height(10.dp))
                IosGroup {
                    ActionRow("关闭", Ios.Blue, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ios.SecondaryLabel)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = Ios.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

/** 组内整行动作：iOS 设置列表式，直角行 + 发丝线，不再嵌套圆角 */
@Composable
private fun ActionRow(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** 探测中显示省略号，失败显示 — */
private fun detailLabel(probing: Boolean, value: String?): String = when {
    probing -> "…"
    value != null -> value
    else -> "—"
}

private fun resolutionLabel(video: LibVideo, info: ClipInfo?, probing: Boolean): String = when {
    info != null -> "${info.displayWidth} × ${info.displayHeight}"
    probing -> "…"
    video.width > 0 && video.height > 0 -> "${video.width} × ${video.height}"
    else -> "—"
}

private fun fmtDuration(ms: Long): String {
    val h = ms / 3_600_000
    val m = ms / 60_000 % 60
    val s = ms / 1000 % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

private fun fmtFps(fps: Double): String =
    if (abs(fps - fps.roundToInt()) < 0.05) "${fps.roundToInt()} fps"
    else String.format(Locale.US, "%.2f fps", fps)

private fun mimeLabel(mime: String): String = when {
    mime.contains("avc") -> "H.264"
    mime.contains("hevc") -> "HEVC"
    mime.isNotBlank() -> mime.removePrefix("video/").uppercase()
    else -> "—"
}
