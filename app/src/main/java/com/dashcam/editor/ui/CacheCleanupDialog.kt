package com.dashcam.editor.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dashcam.editor.media.CacheMaintenance
import com.dashcam.editor.media.CacheUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CacheCleanupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<CacheUsage?>(null) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    LaunchedEffect(Unit) {
        try {
            usage = withContext(Dispatchers.IO) { CacheMaintenance.storage.usage() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            message = "无法读取缓存，请稍后重试"
        } finally {
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("缓存清理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Ios.Blue, strokeWidth = 2.dp)
                usage?.let {
                    Text("缓存占用：${size(it.totalBytes)}")
                    Text("可清理：${size(it.reclaimableBytes)}", color = Ios.Blue)
                    if (it.inUseBytes > 0) Text("正在使用：${size(it.inUseBytes)}", color = Ios.SecondaryLabel)
                }
                Text("自动清理已开启：每天检查，删除超过 24 小时未使用的缓存，打开应用时也会检查。",
                    style = MaterialTheme.typography.bodySmall, color = Ios.SecondaryLabel)
                Text("手动清理会删除所有未使用的导入副本和临时文件，保留正在处理的文件、相册原视频及已保存成品。",
                    style = MaterialTheme.typography.bodySmall, color = Ios.SecondaryLabel)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            IosAction("清理未使用缓存", fontSize = 15.sp,
                enabled = !busy && (usage?.reclaimableBytes ?: 0L) > 0) {
                busy = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { CacheMaintenance.storage.clean(expiredOnly = false) }
                        usage = withContext(Dispatchers.IO) { CacheMaintenance.storage.usage() }
                        message = when {
                            result.busy -> "正在导入、截图或导出，请结束后再清理"
                            result.failedFiles > 0 -> "已释放 ${size(result.freedBytes)}，${result.failedFiles} 个文件未能删除，可稍后重试"
                            else -> "已释放 ${size(result.freedBytes)}"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        message = "清理失败，请稍后重试"
                    } finally {
                        busy = false
                    }
                }
            }
        },
        dismissButton = { IosAction("完成", fontSize = 15.sp, enabled = !busy, onClick = onDismiss) },
    )
}
