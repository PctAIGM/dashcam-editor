package com.dashcam.editor.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dashcam.editor.media.MediaLibrary
import com.dashcam.editor.util.Tc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class LibVideo(
    val uri: android.net.Uri,
    val durationMs: Long,
    val dateAdded: Long,
    val displayName: String = "",
)

/** 首页：本机视频网格（相册式），点开即进入编辑 */
@Composable
fun LibraryScreen(app: AppModel, shareUris: androidx.compose.runtime.MutableState<List<android.net.Uri>>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var granted by remember { mutableStateOf(hasVideoPermission(context)) }
    var importing by remember { mutableStateOf(false) }
    var importLabel by remember { mutableStateOf("") }
    var videos by remember { mutableStateOf<List<LibVideo>>(emptyList()) }

    fun neededPermission(): String = when {
        Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_VIDEO
        Build.VERSION.SDK_INT >= 29 -> Manifest.permission.READ_EXTERNAL_STORAGE
        else -> Manifest.permission.WRITE_EXTERNAL_STORAGE
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok || hasVideoPermission(context)
        if (granted) scope.launch { videos = loadVideos(context) }
    }

    LaunchedEffect(granted) {
        if (granted) videos = loadVideos(context)
    }

    fun openEditor(uris: List<android.net.Uri>) {
        if (importing) return
        importing = true
        importLabel = ""
        scope.launch {
            val list = MediaLibrary.import(
                context,
                uris,
                onProgress = { d, t, n -> importLabel = "加载 $d/$t $n" },
                onError = { e -> scope.launch { snackbar.showSnackbar(e) } },
            )
            importing = false
            if (list.isNotEmpty()) {
                app.clips = list
                app.validateRange()
                app.shots = emptyList()
                app.screen = Screen.Edit
            }
        }
    }

    // 从文件选择（SAF）：目录不被媒体库收录时的兜底，也支持多选拼接
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) openEditor(uris)
    }

    // 系统"分享到本应用"：导入后直接进编辑
    LaunchedEffect(shareUris.value) {
        val uris = shareUris.value
        if (uris.isNotEmpty()) {
            shareUris.value = emptyList()
            openEditor(uris)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Ios.Background,
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Ios.Gutter, end = 8.dp, top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("视频", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { filePicker.launch(arrayOf("video/*")) }) {
                    Icon(Icons.Filled.FolderOpen, "从文件选择（可多选拼接）", tint = Ios.Blue)
                }
            }

            if (!granted) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("需要访问视频权限", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "用于浏览和剪辑本机视频",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ios.SecondaryLabel,
                        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                    )
                    IosFilledButton("授权") { permLauncher.launch(neededPermission()) }
                }
            } else {
                if (importing) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            importLabel.ifBlank { "加载中…" },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (videos.isEmpty() && !importing) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("没有找到视频", color = Ios.SecondaryLabel)
                    }
                } else {
                    val groups = remember(videos) { groupByDay(videos) }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        groups.forEach { (dayLabel, dayVideos) ->
                            item(key = "h_$dayLabel", span = { GridItemSpan(3) }) {
                                Text(
                                    dayLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Ios.Background)
                                        .padding(start = Ios.Gutter, end = Ios.Gutter, top = 12.dp, bottom = 6.dp),
                                )
                            }
                            items(dayVideos, key = { it.uri.toString() }) { v ->
                                VideoCell(video = v) { openEditor(listOf(v.uri)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 按天分组（新→旧），标签：今天 / 昨天 / M月d日 / yyyy年M月d日 */
internal fun groupByDay(videos: List<LibVideo>): List<Pair<String, List<LibVideo>>> {
    val cal: (Long) -> Long = { epochSec ->
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = epochSec * 1000
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        c.timeInMillis / 1000
    }
    val now = java.util.Calendar.getInstance()
    val today = cal(now.timeInMillis / 1000)
    val yesterday = cal(now.timeInMillis / 1000 - 86_400)
    val fmtThisYear = java.text.SimpleDateFormat("M月d日", Locale.getDefault())
    val fmtOld = java.text.SimpleDateFormat("yyyy年M月d日", Locale.getDefault())

    return videos
        .groupBy { cal(it.dateAdded) }
        .toSortedMap(compareByDescending { it })
        .map { (daySec, list) ->
            val label = when (daySec) {
                today -> "今天"
                yesterday -> "昨天"
                else -> {
                    val c = java.util.Calendar.getInstance()
                    c.timeInMillis = daySec * 1000
                    if (c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)) {
                        fmtThisYear.format(c.time)
                    } else {
                        fmtOld.format(c.time)
                    }
                }
            }
            label to list
        }
}

@Composable
internal fun VideoCell(video: LibVideo, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember(video.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(video.uri) {
        thumb = loadThumbnail(context, video.uri)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Ios.GroupedBackground)
            .clickable(onClick = onClick),
    ) {
        thumb?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            Tc.formatShort(video.durationMs),
            color = Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(5.dp)
                .clip(RoundedCornerShape(Ios.RPill))
                .background(Ios.Scrim)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

internal fun hasVideoPermission(context: Context): Boolean {
    fun ok(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 34 ->
            ok(Manifest.permission.READ_MEDIA_VIDEO) ||
                ok("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        Build.VERSION.SDK_INT >= 33 -> ok(Manifest.permission.READ_MEDIA_VIDEO)
        Build.VERSION.SDK_INT >= 29 -> ok(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> ok(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

internal suspend fun loadVideos(context: Context): List<LibVideo> = withContext(Dispatchers.IO) {
    runCatching {
        val list = ArrayList<LibVideo>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DISPLAY_NAME),
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                list.add(
                    LibVideo(
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        durationMs = cursor.getLong(durCol),
                        dateAdded = cursor.getLong(dateCol),
                        displayName = cursor.getString(nameCol) ?: "视频",
                    ),
                )
            }
        }
        android.util.Log.d("DashcamLib", "videos=${list.size}")
        list
    }.onFailure { android.util.Log.e("DashcamLib", "loadVideos failed", it) }
        .getOrDefault(emptyList())
}

private suspend fun loadThumbnail(context: Context, uri: android.net.Uri): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bmp = if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
            } else {
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    ContentUris.parseId(uri),
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null,
                )
            }
            bmp?.asImageBitmap()
        }.getOrNull()
    }
