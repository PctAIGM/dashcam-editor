package com.dashcam.editor.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dashcam.editor.media.MediaLibrary
import com.dashcam.editor.util.Tc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class LibVideo(
    val uri: android.net.Uri,
    val durationMs: Long,
    val dateAdded: Long,
    val displayName: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
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
    var infoVideo by remember { mutableStateOf<LibVideo?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<android.net.Uri>>(emptySet()) }
    var batchBusy by remember { mutableStateOf(false) }
    var showBatchConfirm by remember { mutableStateOf(false) }
    var consentResult by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val batchConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        consentResult?.complete(result.resultCode == android.app.Activity.RESULT_OK)
        consentResult = null
    }
    BackHandler(selecting || batchBusy) {
        if (!batchBusy) { selecting = false; selected = emptySet() }
    }

    fun deleteSelected() {
        if (batchBusy || selected.isEmpty()) return
        val targets = selected.toList()
        batchBusy = true
        showBatchConfirm = false
        scope.launch {
            val removed = mutableSetOf<android.net.Uri>()
            var cancelled = false
            var failure: String? = null
            suspend fun confirm(sender: android.content.IntentSender): Boolean {
                val deferred = CompletableDeferred<Boolean>()
                consentResult = deferred
                batchConsent.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
                return deferred.await()
            }
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    // 系统批量删除，一次确认一组；分组避免超出平台 URI 数量限制。
                    for (group in targets.chunked(200)) {
                        val sender = withContext(Dispatchers.IO) {
                            MediaStore.createDeleteRequest(context.contentResolver, group).intentSender
                        }
                        if (!confirm(sender)) { cancelled = true; break }
                        for (uri in group) {
                            val result = withContext(Dispatchers.IO) { completeVideoDeletion(context, uri, false) }
                            if (result is VideoDeleteResult.Ok) removed += uri
                            else failure = (result as VideoDeleteResult.Error).message
                        }
                    }
                } else {
                    // Android 10 及以下按项删除；遇到授权只等待当前项，不并发弹窗。
                    for (uri in targets) {
                        var result = withContext(Dispatchers.IO) { deleteVideo(context, uri) }
                        if (result is VideoDeleteResult.Confirm) {
                            val request = result
                            if (!confirm(request.sender)) { cancelled = true; break }
                            result = withContext(Dispatchers.IO) {
                                completeVideoDeletion(context, uri, request.retryAfterConsent)
                            }
                        }
                        if (result is VideoDeleteResult.Ok) removed += uri
                        else failure = (result as VideoDeleteResult.Error).message
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = "删除失败：${e.message}"
            } finally {
                consentResult = null
                selected = selected - removed
                videos = videos.filterNot { it.uri in removed }
                batchBusy = false
            }
            val remaining = targets.size - removed.size
            snackbar.showSnackbar(when {
                remaining == 0 -> "已删除 ${removed.size} 个视频"
                cancelled -> "已取消，已删除 ${removed.size} 个，剩余 $remaining 个保留选择"
                else -> "已删除 ${removed.size} 个，$remaining 个未完成。${failure.orEmpty()}"
            })
        }
    }

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
                app.loadClips(list)
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

    // 删除：可直接删的立即删；系统文件弹出系统确认框，确认后整表刷新
    var deleteTarget by remember { mutableStateOf<android.net.Uri?>(null) }
    var retryDeleteAfterConsent by remember { mutableStateOf(false) }
    val deleteConfirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val target = deleteTarget
        val retry = retryDeleteAfterConsent
        deleteTarget = null
        if (res.resultCode == android.app.Activity.RESULT_OK && target != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { completeVideoDeletion(context, target, retry) }
                if (result is VideoDeleteResult.Ok) selected = selected - target
                videos = loadVideos(context)
                snackbar.showSnackbar(if (result is VideoDeleteResult.Ok) "已删除" else
                    (result as VideoDeleteResult.Error).message)
            }
        }
    }

    fun requestDelete(video: LibVideo) {
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { deleteVideo(context, video.uri) }) {
                is VideoDeleteResult.Ok -> {
                    videos = videos.filterNot { it.uri == video.uri }
                    selected = selected - video.uri
                    infoVideo = null
                    snackbar.showSnackbar("已删除")
                }
                is VideoDeleteResult.Confirm -> {
                    deleteTarget = video.uri
                    retryDeleteAfterConsent = r.retryAfterConsent
                    infoVideo = null
                    deleteConfirm.launch(androidx.activity.result.IntentSenderRequest.Builder(r.sender).build())
                }
                is VideoDeleteResult.Error -> snackbar.showSnackbar(r.message)
            }
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
                    .padding(horizontal = Ios.Gutter, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (selecting) "已选 ${selected.size} 项" else "视频",
                        fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (selecting) "轻点视频以选择" else "${videos.size} 个视频 · 长按选择",
                        fontSize = 12.sp, lineHeight = 18.sp, color = Ios.SecondaryLabel)
                }
                IosAction(if (selecting) "完成" else "选择", fontSize = 14.sp,
                    color = if (selecting) Ios.Blue else Ios.Label,
                    modifier = Modifier.widthIn(min = 64.dp).clip(RoundedCornerShape(Ios.RPill)).background(if (selecting) Ios.AccentFill else Ios.Fill),
                    enabled = granted && !importing && !batchBusy) {
                    selecting = !selecting
                    selected = emptySet()
                }
                if (!selecting) IconButton(modifier = Modifier.padding(start = 6.dp),
                    enabled = !batchBusy && !importing, onClick = { filePicker.launch(arrayOf("video/*")) }) {
                    Icon(Icons.Filled.FolderOpen, "从文件选择（可多选拼接）", tint = Ios.SecondaryLabel, modifier = Modifier.size(22.dp))
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
                        columns = GridCells.Adaptive(104.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = Ios.Gutter, end = Ios.Gutter, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        groups.forEach { (dayLabel, dayVideos) ->
                            item(key = "h_$dayLabel", span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    dayLabel,
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ios.SecondaryLabel,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Ios.Background)
                                        .padding(top = 8.dp, bottom = 2.dp),
                                )
                            }
                            items(dayVideos, key = { it.uri.toString() }) { v ->
                                var menuOpen by remember(v.uri) { mutableStateOf(false) }
                                Column {
                                    Box(Modifier.clip(RoundedCornerShape(Ios.RControl))) {
                                        VideoCell(video = v, onClick = {
                                            if (!batchBusy) {
                                                if (selecting) selected = if (v.uri in selected) selected - v.uri else selected + v.uri
                                                else openEditor(listOf(v.uri))
                                            }
                                        }, onLongClick = {
                                            if (!batchBusy && !importing) {
                                                selecting = true
                                                selected = selected + v.uri
                                            }
                                        })
                                        Box(Modifier.fillMaxWidth().height(36.dp).background(
                                            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.26f), Color.Transparent))))
                                        if (selecting) Box(
                                            Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp)
                                                .clip(RoundedCornerShape(Ios.RPill))
                                                .background(if (v.uri in selected) Ios.Blue else Ios.SubtleScrim)
                                                .border(if (v.uri in selected) 0.dp else 1.5.dp, Color.White, RoundedCornerShape(Ios.RPill)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (v.uri in selected) Icon(Icons.Filled.Check, "已选择",
                                                tint = Color.White, modifier = Modifier.size(15.dp))
                                        }
                                        Box(Modifier.align(Alignment.TopEnd)) {
                                            Box(Modifier.size(48.dp).clickable(
                                                enabled = !batchBusy && !importing,
                                                role = androidx.compose.ui.semantics.Role.Button,
                                                onClick = { menuOpen = true },
                                            )) {
                                                Icon(Icons.Filled.MoreHoriz, "${v.displayName} 的更多操作",
                                                    tint = Color.White, modifier = Modifier.align(Alignment.TopEnd)
                                                        .padding(top = 2.dp, end = 2.dp).size(18.dp))
                                            }
                                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                                                shape = RoundedCornerShape(Ios.RCard), containerColor = Ios.Background,
                                                tonalElevation = 0.dp, shadowElevation = 4.dp,
                                                modifier = Modifier.widthIn(min = 152.dp)) {
                                                DropdownMenuItem(text = {
                                                    Text("视频信息", fontSize = 14.sp, lineHeight = 20.sp,
                                                        letterSpacing = 0.sp, fontWeight = FontWeight.Normal, color = Ios.Label)
                                                }, leadingIcon = {
                                                    Icon(Icons.Outlined.Info, null, tint = Ios.SecondaryLabel, modifier = Modifier.size(18.dp))
                                                }, modifier = Modifier.height(44.dp), contentPadding = PaddingValues(horizontal = 14.dp),
                                                    enabled = !batchBusy && !importing, onClick = {
                                                    menuOpen = false
                                                    infoVideo = v
                                                })
                                            }
                                        }
                                    }
                                    Text(v.displayName, modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
                                        fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal, color = Ios.SecondaryLabel,
                                        minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            if (selecting) {
                IosBar {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Ios.Gutter, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IosAction(if (videos.isNotEmpty() && selected.size == videos.size) "取消全选" else "全选",
                            fontSize = 14.sp, color = Ios.Label, enabled = !batchBusy) {
                            selected = if (selected.size == videos.size) emptySet() else videos.map { it.uri }.toSet()
                        }
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.clip(RoundedCornerShape(Ios.RPill)).background(Ios.Red.copy(alpha = 0.08f))
                            .clickable(enabled = selected.isNotEmpty() && !batchBusy) { showBatchConfirm = true }
                            .padding(horizontal = 18.dp).height(44.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val deleteColor = if (selected.isNotEmpty() && !batchBusy) Ios.Red else Ios.TertiaryLabel
                            Icon(Icons.Outlined.Delete, null, tint = deleteColor, modifier = Modifier.size(18.dp))
                            Text(if (batchBusy) "删除中…" else "删除 (${selected.size})", fontSize = 14.sp,
                                fontWeight = FontWeight.Medium, color = deleteColor)
                        }
                    }
                }
            }
        }
    }

    infoVideo?.let { v ->
        VideoInfoSheet(video = v, onDismiss = { infoVideo = null }, onDelete = { requestDelete(v) })
    }
    if (showBatchConfirm) AlertDialog(
        onDismissRequest = { showBatchConfirm = false },
        title = { Text("删除 ${selected.size} 个视频？") },
        text = { Text("将从设备中删除所选视频，无法撤销。") },
        confirmButton = { TextButton(onClick = { deleteSelected() }) { Text("删除", color = Ios.Red) } },
        dismissButton = { TextButton(onClick = { showBatchConfirm = false }) { Text("取消") } },
    )
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun VideoCell(video: LibVideo, onClick: (() -> Unit)?, onLongClick: (() -> Unit)? = null, showDuration: Boolean = true) {
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
            .let { if (onClick != null) it.combinedClickable(onClick = onClick, onLongClick = onLongClick) else it },
    ) {
        thumb?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showDuration) Text(
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
            arrayOf(
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.SIZE,
            ),
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                list.add(
                    LibVideo(
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        durationMs = cursor.getLong(durCol),
                        dateAdded = cursor.getLong(dateCol),
                        displayName = cursor.getString(nameCol) ?: "视频",
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol),
                        sizeBytes = cursor.getLong(sizeCol),
                    ),
                )
            }
        }
        android.util.Log.d("DashcamLib", "videos=${list.size}")
        list
    }.onFailure { android.util.Log.e("DashcamLib", "loadVideos failed", it) }
        .getOrDefault(emptyList())
}

internal suspend fun loadThumbnail(context: Context, uri: android.net.Uri): ImageBitmap? =
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

/** 删除 MediaStore 视频的结果：直接成功 / 需要系统确认框 / 失败 */
internal sealed interface VideoDeleteResult {
    data class Ok(val count: Int) : VideoDeleteResult
    data class Confirm(val sender: android.content.IntentSender, val retryAfterConsent: Boolean) : VideoDeleteResult
    data class Error(val message: String) : VideoDeleteResult
}

/**
 * 删除一条视频：应用自建的文件可直接删；其他应用的文件在 Scoped Storage 下需要
 * 系统确认（RecoverableSecurityException 的 IntentSender，或 API 30+ 的批量删除请求）。
 */
internal fun deleteVideo(context: Context, uri: android.net.Uri): VideoDeleteResult {
    return try {
        val n = context.contentResolver.delete(uri, null, null)
        if (n > 0) verifyVideoDeleted(context, uri) else VideoDeleteResult.Error("未找到该视频，可能已被删除")
    } catch (e: android.app.RecoverableSecurityException) {
        // 此弹窗只授权，RESULT_OK 后仍须再次调用 delete。
        VideoDeleteResult.Confirm(e.userAction.actionIntent.intentSender, retryAfterConsent = true)
    } catch (_: SecurityException) {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                VideoDeleteResult.Confirm(MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender,
                    retryAfterConsent = false)
            }.getOrElse { VideoDeleteResult.Error("无法删除：${it.message}") }
        } else {
            VideoDeleteResult.Error("没有删除该视频的权限")
        }
    } catch (e: Exception) {
        VideoDeleteResult.Error("删除失败：${e.message}")
    }
}

/** 授权弹窗需要补做删除；createDeleteRequest 已由系统删除，只检查结果。 */
internal fun completeVideoDeletion(context: Context, uri: android.net.Uri, retryAfterConsent: Boolean): VideoDeleteResult {
    if (!retryAfterConsent) return verifyVideoDeleted(context, uri)
    return when (val result = deleteVideo(context, uri)) {
        is VideoDeleteResult.Confirm -> VideoDeleteResult.Error("删除未完成：系统仍未授予删除权限，请重试")
        else -> result
    }
}

private fun verifyVideoDeleted(context: Context, uri: android.net.Uri): VideoDeleteResult = try {
    val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
    if (cursor == null) {
        VideoDeleteResult.Error("无法确认删除结果，请刷新后重试")
    } else cursor.use {
        if (it.moveToFirst()) VideoDeleteResult.Error("删除未完成，视频仍在媒体库中")
        else VideoDeleteResult.Ok(1)
    }
} catch (e: Exception) {
    VideoDeleteResult.Error("无法确认删除结果：${e.message}")
}
