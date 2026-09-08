package com.dashcam.editor.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dashcam.editor.zoom.ZoomState
import com.dashcam.editor.zoom.zoomGesture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 解码 MediaStore 图片为 ImageBitmap，限制最大边长 */
fun decodeImageBitmap(context: Context, uri: Uri, maxEdge: Int): ImageBitmap? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (
        (bounds.outWidth / (sample * 2) >= maxEdge) ||
        (bounds.outHeight / (sample * 2) >= maxEdge)
    ) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
}.getOrNull()

fun shareUri(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "分享"))
    }
}

@Composable
fun ShotThumb(shot: ShotItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(Ios.RControl))
            .background(Ios.GroupedBackground)
            .clickable(onClick = onClick),
    ) {
        shot.thumb?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = shot.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            shot.timeLabel,
            color = Color.White,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(Ios.RPill))
                .background(Ios.Scrim)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** 截图库：网格浏览 */
@Composable
fun GalleryDialog(
    app: AppModel,
    onOpenViewer: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(Ios.RCard),
            color = Ios.GroupedBackground,
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("截图（${app.shots.size}）", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "关闭", tint = Ios.Blue) }
                }
                Text(
                    "截图为原始分辨率，保存于 相册/Pictures/dashcam-editor",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ios.SecondaryLabel,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                if (app.shots.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "还没有截图\n在编辑页点【截图】抓取当前帧（原画质）",
                            color = Ios.SecondaryLabel,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(app.shots) { i, shot ->
                            ShotThumb(
                                shot = shot,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                onClick = { onOpenViewer(i) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShotViewerDialog(app: AppModel, index: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val shot = app.shots.getOrNull(index) ?: return
    var full by remember(shot.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(shot.uri) {
        full = withContext(Dispatchers.IO) { decodeImageBitmap(context, shot.uri, 2048) }
    }
    val zoom = remember { ZoomState() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged {
                        zoom.viewW = it.width.toFloat()
                        zoom.viewH = it.height.toFloat()
                    }
                    .zoomGesture(zoom),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = full
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = shot.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom.scale
                                scaleY = zoom.scale
                                translationX = zoom.offsetX
                                translationY = zoom.offsetY
                            },
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
            Text(
                "${shot.clipName} @ ${shot.timeLabel} · 帧${shot.frameNo}",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IosAction("分享") { shareUri(context, shot.uri, "image/jpeg") }
                IosAction("删除", color = Ios.Red) {
                    runCatching { context.contentResolver.delete(shot.uri, null, null) }
                    app.shots = app.shots.filterIndexed { i, _ -> i != index }
                    onDismiss()
                }
                IosAction("关闭", onClick = onDismiss)
            }
        }
    }
}
