package com.dashcam.editor.shot

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.ReturnCode
import com.dashcam.editor.export.MediaStoreSaver
import com.dashcam.editor.media.ClipInfo
import com.dashcam.editor.media.FfExec
import com.dashcam.editor.util.Tc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 原画质抽帧截图：输入侧预跳到目标前2秒的关键帧（快），copyts 保留原时间戳，
 * 按时间戳取第一个 ≥ 目标时刻的帧（帧级精确）；长视频也接近秒出。
 */
object FrameShot {

    /** 返回 (相册 Uri, 文件名) */
    suspend fun capture(
        context: Context,
        clip: ClipInfo,
        localMs: Long,
        frameNo: Long,
        crop: ViewportCrop = ViewportCrop(),
    ): Result<Pair<Uri, String>> = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "shot_${stamp}_f$frameNo.jpg"
        val out = File(context.cacheDir, name)

        val targetSec = localMs.coerceIn(0, clip.durationMs) / 1000.0
        val preSec = (targetSec - 2.0).coerceAtLeast(0.0)
        val args = mutableListOf("-y", "-hide_banner")
        if (preSec > 0.001) args += listOf("-ss", Tc.sec((preSec * 1000).toLong()))
        val region = crop.pixels(clip.displayWidth, clip.displayHeight)
        val filters = buildList {
            add("select='gte(t,${Tc.sec((targetSec * 1000).toLong())})'")
            when ((clip.rotationDegrees % 360 + 360) % 360) {
                90 -> add("transpose=clock")
                270 -> add("transpose=cclock")
            }
            add("crop=${region.width}:${region.height}:${region.x}:${region.y}")
        }.joinToString(",")
        args += listOf(
            "-copyts",
            "-i", clip.filePath,
            "-vf", filters,
            "-fps_mode", "vfr",
            "-frames:v", "1",
            "-q:v", "2",
            out.absolutePath,
        )

        val session = FfExec.ffmpegArgs(args.toTypedArray(), timeoutMs = 30_000)
        if (session == null || !ReturnCode.isSuccess(session.returnCode) || !out.exists() || out.length() == 0L) {
            out.delete()
            return@withContext Result.failure(IllegalStateException("截图失败"))
        }

        val uri = MediaStoreSaver.saveImage(context, out, name)
        out.delete()
        if (uri == null) {
            Result.failure(IllegalStateException("保存截图到相册失败"))
        } else {
            Result.success(uri to name)
        }
    }
}
