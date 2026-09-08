package com.dashcam.editor.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 一段已导入的视频（已复制到应用缓存目录，有真实文件路径） */
data class ClipInfo(
    val filePath: String,
    val displayName: String,
    val durationMs: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    val fps: Double,
    val hasAudio: Boolean,
    /** Rotation metadata applied by the player; screenshots normalize it before cropping. */
    val rotationDegrees: Int = 0,
    /** 视频轨 MIME（video/avc、video/hevc…），导出时用来挑 MediaCodec 硬解码器 */
    val videoMime: String = "",
)

/**
 * 视频导入与信息探测。
 * 注意：ffmpeg-kit 维护分支未打包 ffprobe 原生库，FFprobeKit 会永久挂起，
 * 因此探测全部使用系统 MediaMetadataRetriever + MediaExtractor。
 */
object MediaLibrary {

    /** 把 SAF Uri 指向的视频复制进缓存并探测信息；返回成功导入的列表 */
    suspend fun import(
        context: Context,
        uris: List<Uri>,
        onProgress: (done: Int, total: Int, name: String) -> Unit,
        onError: (String) -> Unit,
    ): List<ClipInfo> = withContext(Dispatchers.IO) {
        val result = ArrayList<ClipInfo>()
        uris.forEachIndexed { index, uri ->
            val name = queryDisplayName(context, uri) ?: "video_${index + 1}"
            onProgress(index, uris.size, name)
            try {
                val copied = copyToCache(context, uri, name)
                val info = probe(copied.absolutePath, name)
                if (info != null && info.durationMs > 0) {
                    result.add(info)
                } else {
                    onError("无法读取视频信息：$name")
                }
            } catch (e: Exception) {
                onError("导入失败 $name：${e.message}")
            }
        }
        onProgress(uris.size, uris.size, "")
        result
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()

    private fun copyToCache(context: Context, uri: Uri, displayName: String): File {
        val dir = File(context.cacheDir, "imports").apply { mkdirs() }
        val ext = displayName.substringAfterLast('.', "mp4").take(5).ifBlank { "mp4" }
        val target = File(dir, "${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
        } ?: throw IllegalStateException("无法打开所选文件")
        return target
    }

    /** 系统 API 探测时长/分辨率/旋转/音轨；帧率取自 MediaFormat（优先浮点精确值） */
    fun probe(path: String, displayName: String): ClipInfo? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            return buildInfo(retriever, path, displayName, readVideoTrack { it.setDataSource(path) })
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 直接探测 MediaStore Uri（不复制文件），供信息查看用；阻塞 IO，调用方自行切线程 */
    fun probeUri(context: Context, uri: Uri, displayName: String): ClipInfo? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            return buildInfo(retriever, "", displayName, readVideoTrack { it.setDataSource(context, uri, null) })
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun buildInfo(
        retriever: MediaMetadataRetriever,
        filePath: String,
        displayName: String,
        track: Pair<Double, String>,
    ): ClipInfo? {
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"

        if (width <= 0 || height <= 0 || durationMs <= 0L) return null
        val effFps = if (track.first in 1.0..240.0) track.first else 30.0
        val swap = rotation == 90 || rotation == 270
        return ClipInfo(
            filePath = filePath,
            displayName = displayName,
            durationMs = durationMs,
            displayWidth = if (swap) height else width,
            displayHeight = if (swap) width else height,
            fps = effFps,
            hasAudio = hasAudio,
            rotationDegrees = rotation,
            videoMime = track.second,
        )
    }

    /** 返回 (帧率, 视频轨 MIME)；帧率优先浮点（29.97/59.94 精确），回退整数 */
    private fun readVideoTrack(setSource: (MediaExtractor) -> Unit): Pair<Double, String> {
        val extractor = MediaExtractor()
        try {
            setSource(extractor)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                var fps = 0.0
                runCatching {
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
                }
                if (fps <= 0.0) {
                    runCatching {
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                    }
                }
                return fps to mime
            }
        } catch (_: Exception) {
        } finally {
            runCatching { extractor.release() }
        }
        return 0.0 to ""
    }
}
