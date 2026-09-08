package com.dashcam.editor.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dashcam.editor.media.ClipInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reuses the home library and thumbnails, with ordered selection and an insertion boundary. */
@Composable
fun InsertVideoPicker(
    clips: List<ClipInfo>,
    busy: Boolean,
    progress: String,
    onDismiss: () -> Unit,
    onInsert: (Int, List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasVideoPermission(context)) }
    var videos by remember { mutableStateOf<List<LibVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var fileVideos by remember { mutableStateOf<List<LibVideo>>(emptyList()) }
    var insertion by remember { mutableIntStateOf(clips.size) }
    var menuOpen by remember { mutableStateOf(false) }
    var infoVideo by remember { mutableStateOf<LibVideo?>(null) }
    var deleteTarget by remember { mutableStateOf<Uri?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = hasVideoPermission(context)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val name = runCatching {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                    }.getOrNull() ?: "文件视频"
                    LibVideo(uri, 0L, 0L, name)
                }
            }
            fileVideos = (fileVideos + added).distinctBy { it.uri }
            selected = (selected + uris).distinct()
        }
    }
    val deleteConfirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val target = deleteTarget
        deleteTarget = null
        if (res.resultCode == android.app.Activity.RESULT_OK && target != null) {
            selected = selected - target
            scope.launch {
                videos = loadVideos(context)
                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
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
                }
                is VideoDeleteResult.Confirm -> {
                    deleteTarget = video.uri
                    infoVideo = null
                    deleteConfirm.launch(androidx.activity.result.IntentSenderRequest.Builder(r.sender).build())
                }
                is VideoDeleteResult.Error -> Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(granted) {
        loading = true
        videos = if (granted) loadVideos(context) else emptyList()
        loading = false
    }
    fun positionLabel(index: Int): String = when (index) {
        0 -> "最前面（第 1 段之前）"
        clips.size -> "最后面（第 ${clips.size} 段之后）"
        else -> "第 $index 段之后 · ${clips[index - 1].displayName}"
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ios.Background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                IosNavBar(
                    title = "选择拼接视频",
                    leading = { IosAction("取消", enabled = !busy, onClick = onDismiss) },
                    trailing = { IosAction("文件", enabled = !busy) { files.launch(arrayOf("video/*")) } },
                )
                IosSeparator(inset = 0.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { menuOpen = true }
                        .padding(horizontal = Ios.Gutter, vertical = 10.dp),
                ) {
                    if (!landscape) Text("插入位置", style = MaterialTheme.typography.bodySmall, color = Ios.SecondaryLabel)
                    Text(
                        "${positionLabel(insertion)} ▾",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ios.Blue,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        (0..clips.size).forEach { index ->
                            DropdownMenuItem(text = { Text(positionLabel(index), maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = {
                                insertion = index
                                menuOpen = false
                            })
                        }
                    }
                }
                IosSeparator(inset = 0.dp)
                if (!landscape) Text(
                    "按点选顺序拼接，可多选；再次点击取消选择",
                    Modifier.padding(horizontal = Ios.Gutter, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ios.SecondaryLabel,
                )
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = Ios.Blue, trackColor = Ios.Fill)
                    Text(
                        progress.ifBlank { "正在加载视频…" },
                        Modifier.padding(horizontal = Ios.Gutter, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                }
                if (!granted) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("授权后可像首页一样浏览视频，也可从文件选择", style = MaterialTheme.typography.bodyMedium, color = Ios.SecondaryLabel)
                        Spacer(Modifier.height(16.dp))
                        IosFilledButton("授权浏览视频", enabled = !busy) {
                            permission.launch(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else if (Build.VERSION.SDK_INT >= 29) Manifest.permission.READ_EXTERNAL_STORAGE else Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                } else if (loading || videos.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator(color = Ios.Blue) else Text("没有找到视频，可通过右上角「文件」选择", color = Ios.SecondaryLabel)
                    }
                } else {
                    val groups = remember(videos) { groupByDay(videos) }
                    LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.forEach { (day, items) ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(day, Modifier.padding(start = Ios.Gutter, top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
                            }
                            items(items, key = { it.uri.toString() }) { video ->
                                Column {
                                    Box {
                                        VideoCell(video, onClick = {
                                            if (!busy) selected = if (video.uri in selected) selected - video.uri else selected + video.uri
                                        }, onLongClick = { if (!busy) infoVideo = video })
                                        val order = selected.indexOf(video.uri)
                                        if (order >= 0) {
                                            Box(
                                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                                                    .clip(CircleShape).background(Ios.Blue),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text("${order + 1}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                    Text(video.displayName, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = Ios.SecondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (selected.isNotEmpty()) {
                    IosSeparator(inset = 0.dp)
                    if (!landscape) Row(Modifier.fillMaxWidth().padding(start = Ios.Gutter), verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selected.size} 段 · 按下方顺序插入", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Ios.SecondaryLabel)
                        IosAction("清空", fontSize = 13.sp, enabled = !busy) { selected = emptyList() }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = Ios.Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(selected, key = { _, uri -> uri.toString() }) { index, uri ->
                            val video = (videos + fileVideos).firstOrNull { it.uri == uri } ?: LibVideo(uri, 0L, 0L, "视频")
                            IosGroup(Modifier.width(168.dp).border(Ios.Hairline, Ios.Separator, RoundedCornerShape(Ios.RCard))) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(44.dp)) { VideoCell(video, onClick = null, showDuration = false) }
                                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                                        Text("第 ${index + 1} 段", style = MaterialTheme.typography.labelSmall, color = Ios.Blue)
                                        Text(video.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    IconButton(enabled = !busy && index > 0, onClick = {
                                        selected = selected.toMutableList().apply { add(index - 1, removeAt(index)) }
                                    }) { Icon(Icons.Filled.ChevronLeft, "前移第 ${index + 1} 段") }
                                    IconButton(enabled = !busy, onClick = { selected = selected - uri }) { Icon(Icons.Filled.Close, "取消第 ${index + 1} 段") }
                                    IconButton(enabled = !busy && index < selected.lastIndex, onClick = {
                                        selected = selected.toMutableList().apply { add(index + 1, removeAt(index)) }
                                    }) { Icon(Icons.Filled.ChevronRight, "后移第 ${index + 1} 段") }
                                }
                            }
                        }
                        if (landscape) item { IosAction("清空", enabled = !busy, fontSize = 13.sp) { selected = emptyList() } }
                    }
                }
                IosSeparator(inset = 0.dp)
                Box(Modifier.padding(horizontal = Ios.Gutter, vertical = 8.dp)) {
                    IosFilledButton(
                        if (busy) "正在拼接…" else "插入已选 ${selected.size} 段视频",
                        enabled = selected.isNotEmpty() && !busy,
                    ) { onInsert(insertion, selected) }
                }
            }
        }
    }

    infoVideo?.let { v ->
        VideoInfoSheet(video = v, onDismiss = { infoVideo = null }, onDelete = { requestDelete(v) })
    }
}
