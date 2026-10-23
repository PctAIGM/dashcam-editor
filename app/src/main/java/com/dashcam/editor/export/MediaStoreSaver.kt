package com.dashcam.editor.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

/** 导出视频存 Movies/dashcam-editor，截图存 Pictures/dashcam-editor（相册可见） */
object MediaStoreSaver {

    fun saveVideo(context: Context, src: File, displayName: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): Uri? =
        save(context, src, displayName, "video/mp4", Environment.DIRECTORY_MOVIES, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, onProgress)

    fun saveImage(context: Context, src: File, displayName: String): Uri? =
        save(context, src, displayName, "image/jpeg", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)

    private fun save(
        context: Context,
        src: File,
        displayName: String,
        mime: String,
        parentDir: String,
        collection: Uri,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$parentDir/dashcam-editor")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return null
            runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    copy(src, out, onProgress)
                } ?: throw IllegalStateException("无法写入")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) > 0) { "无法发布到相册" }
                uri
            }.getOrElse {
                runCatching { resolver.delete(uri, null, null) }
                if (it is CancellationException) throw it
                null
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(parentDir), "dashcam-editor")
            if (!dir.exists()) dir.mkdirs()
            val dst = File(dir, displayName)
            runCatching {
                dst.outputStream().use { copy(src, it, onProgress) }
                MediaScannerConnection.scanFile(context, arrayOf(dst.absolutePath), arrayOf(mime), null)
                Uri.fromFile(dst)
            }.getOrElse {
                dst.delete()
                if (it is CancellationException) throw it
                null
            }
        }
    }

    private fun copy(src: File, out: OutputStream, onProgress: (Long, Long) -> Unit) {
        val total = src.length()
        var copied = 0L
        onProgress(copied, total)
        src.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                copied += read
                onProgress(copied, total)
            }
        }
    }
}
