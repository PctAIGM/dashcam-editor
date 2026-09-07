package com.dashcam.editor.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 导出视频存 Movies/dashcam-editor，截图存 Pictures/dashcam-editor（相册可见） */
object MediaStoreSaver {

    fun saveVideo(context: Context, src: File, displayName: String): Uri? =
        save(context, src, displayName, "video/mp4", Environment.DIRECTORY_MOVIES, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    fun saveImage(context: Context, src: File, displayName: String): Uri? =
        save(context, src, displayName, "image/jpeg", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)

    private fun save(
        context: Context,
        src: File,
        displayName: String,
        mime: String,
        parentDir: String,
        collection: Uri,
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
                    src.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("无法写入")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            }.getOrElse {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(parentDir), "dashcam-editor")
            if (!dir.exists()) dir.mkdirs()
            val dst = File(dir, displayName)
            runCatching {
                src.copyTo(dst, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(dst.absolutePath), arrayOf(mime), null)
                Uri.fromFile(dst)
            }.getOrNull()
        }
    }
}
