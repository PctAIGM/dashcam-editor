package com.dashcam.editor.timeline

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.dashcam.editor.media.ClipInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 后台生成时间轴胶片缩略图：每个片段按 ~3 秒一张，数量限制 8..60 */
object Filmstrip {

    suspend fun generate(
        clips: List<ClipInfo>,
        targetHeightPx: Int = 128,
    ): Map<Int, List<Pair<Long, ImageBitmap>>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Int, List<Pair<Long, ImageBitmap>>>()
        clips.forEachIndexed { index, clip ->
            val thumbs = ArrayList<Pair<Long, ImageBitmap>>()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(clip.filePath)
                val durationSec = clip.durationMs / 1000.0
                // 约 0.5 秒一格，裁剪显示，保证每格足够窄且清晰
                val count = (durationSec * 2).toInt().coerceIn(8, 96)
                for (i in 0 until count) {
                    val tMs = clip.durationMs * i / count
                    val frame = runCatching {
                        retriever.getFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }.getOrNull() ?: continue
                    val scaled = scaleDown(frame, targetHeightPx)
                    thumbs.add(tMs to scaled.asImageBitmap())
                }
            } catch (_: Exception) {
                // 单个片段失败不影响其它片段
            } finally {
                runCatching { retriever.release() }
            }
            result[index] = thumbs
        }
        result
    }

    private fun scaleDown(src: Bitmap, targetH: Int): Bitmap {
        val h = src.height
        if (h <= targetH) return src
        val w = src.width * targetH / h
        val scaled = Bitmap.createScaledBitmap(src, w, targetH, true)
        if (scaled != src) src.recycle()
        return scaled
    }
}
