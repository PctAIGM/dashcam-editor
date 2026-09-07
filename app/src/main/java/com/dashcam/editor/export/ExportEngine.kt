package com.dashcam.editor.export

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import com.dashcam.editor.media.ClipInfo
import com.dashcam.editor.media.FfExec
import com.dashcam.editor.util.Tc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs

enum class Quality { HIGH, MEDIUM, LOW }

data class ExportOptions(
    val targetHeight: Int?,   // null = 原始分辨率
    val targetFps: Int?,      // null = 原始帧率
    val quality: Quality = Quality.MEDIUM,
    val useHw: Boolean = true,
    val hevc: Boolean = false,
    val fastCopy: Boolean = false,
    /** MediaCodec 硬解码：4K 片源解码本身就很吃 CPU，开启后由 GPU/DSP 解 */
    val hwDecode: Boolean = true,
)

data class ExportResult(val uri: Uri, val displayName: String, val sizeBytes: Long)

/** 导出实时状态：进度、相对实时的编码速度（0.5 表示 1 秒素材要 2 秒）、当前使用的编解码路径 */
data class ExportStats(val progress: Float, val speed: Double, val stage: String)

data class SelectionEntry(val clip: ClipInfo, val inMs: Long, val outMs: Long)

/**
 * 导出引擎：
 * - 单段裁剪：输入侧 -ss 预滚到目标前 [PREROLL_MS]，再用输出侧 -ss 丢掉预滚，
 *   既保持帧精确，又不必从文件头把前面所有帧解一遍（4K 片源的主要耗时来源）
 * - 跨文件裁剪：concat demuxer + inpoint/outpoint 一次完成
 * - 解码：MediaCodec 硬解优先；编码：MediaCodec 硬编优先，逐级回退到 libx264
 * - 快速模式：输入侧 seek + 流复制，秒出，关键帧对齐
 */
object ExportEngine {

    /** 输入侧 seek 的预滚时长：够覆盖常见 1～2s GOP，又不会白解太多帧 */
    private const val PREROLL_MS = 2_000L

    /** [h264 硬编, hevc 硬编, h264 硬解, hevc 硬解] */
    suspend fun detectCodecs(): BooleanArray = withContext(Dispatchers.IO) {
        runCatching {
            val encSession = FfExec.ffmpegText("-hide_banner -encoders")
            val enc = if (encSession != null && ReturnCode.isSuccess(encSession.returnCode)) encSession.output else ""
            val decSession = FfExec.ffmpegText("-hide_banner -decoders")
            val dec = if (decSession != null && ReturnCode.isSuccess(decSession.returnCode)) decSession.output else ""
            booleanArrayOf(
                enc.contains("h264_mediacodec"),
                enc.contains("hevc_mediacodec"),
                dec.contains("h264_mediacodec"),
                dec.contains("hevc_mediacodec"),
            )
        }.getOrDefault(booleanArrayOf(false, false, false, false))
    }

    fun selectionEntries(clips: List<ClipInfo>, startMs: Long, endMs: Long): List<SelectionEntry> {
        val entries = ArrayList<SelectionEntry>()
        var acc = 0L
        for (c in clips) {
            val s = maxOf(acc, startMs)
            val e = minOf(acc + c.durationMs, endMs)
            if (e - s > 0) entries.add(SelectionEntry(c, s - acc, e - acc))
            acc += c.durationMs
        }
        return entries
    }

    suspend fun export(
        context: Context,
        clips: List<ClipInfo>,
        startMs: Long,
        endMs: Long,
        options: ExportOptions,
        hw: BooleanArray,
        onProgress: (ExportStats) -> Unit,
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        val entries = selectionEntries(clips, startMs, endMs)
        if (entries.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("选中区间无效"))
        }
        val selDurMs = endMs - startMs
        val srcH = entries.first().clip.displayHeight
        val srcFps = entries.first().clip.fps
        val anyAudio = entries.any { it.clip.hasAudio }

        val scaleH = options.targetHeight
            ?.let { minOf(it, srcH) }          // 不放大，只缩小
            ?.let { if (it % 2 == 1) it - 1 else it }
        val needScale = scaleH != null && scaleH < srcH
        val needFps = options.targetFps != null && abs(options.targetFps!! - srcFps) > 0.05

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outName = "dashcam_$stamp.mp4"
        val outFile = File(context.cacheDir, outName)

        var ok = false
        var lastError = ""

        // 校验输出实际时长（部分设备的 mediacodec 会"成功"地输出 0 帧，必须实测）
        fun outputValid(): Boolean {
            if (!outFile.exists() || outFile.length() < 1024) return false
            val retriever = android.media.MediaMetadataRetriever()
            return try {
                retriever.setDataSource(outFile.absolutePath)
                val dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                dur >= selDurMs * 8 / 10
            } catch (_: Exception) {
                false
            } finally {
                runCatching { retriever.release() }
            }
        }

        if (options.fastCopy) {
            val args = buildArgs(entries, scaleH, needScale, needFps, anyAudio, options, null, null, outFile)
            ok = runFfmpeg(args, selDurMs, "流复制", onProgress) && outputValid()
            if (!ok) lastError = "快速模式失败（可能不在关键帧上，请用精确模式）"
        } else {
            val encoder: String? = when {
                !options.useHw -> null
                options.hevc && hw.getOrElse(1) { false } -> "hevc_mediacodec"
                !options.hevc && hw.getOrElse(0) { false } -> "h264_mediacodec"
                else -> null
            }
            val decoder = hwDecoderOf(entries, options, hw)
            // 从最快组合逐级回退，每级都用实际输出时长验证，避免个别设备静默输出 0 帧
            val plan = ArrayList<Triple<String, String?, String?>>()
            if (encoder != null && decoder != null) plan += Triple("硬解 + 硬编", encoder, decoder)
            if (encoder != null) plan += Triple("软解 + 硬编", encoder, null)
            if (decoder != null) plan += Triple("硬解 + x264", null, decoder)
            plan += Triple("x264 软件编码", null, null)

            for ((stage, enc, dec) in plan) {
                outFile.delete()
                onProgress(ExportStats(0f, 0.0, stage))
                val args = buildArgs(entries, scaleH, needScale, needFps, anyAudio, options, enc, dec, outFile)
                ok = runFfmpeg(args, selDurMs, stage, onProgress) && outputValid()
                if (ok) break
            }
            if (!ok) lastError = "导出失败（硬编与软件编码均未成功）"
        }

        if (!ok) {
            outFile.delete()
            return@withContext Result.failure(IllegalStateException(lastError.ifBlank { "导出失败" }))
        }

        val uri = MediaStoreSaver.saveVideo(context, outFile, outName)
        val size = outFile.length()
        outFile.delete()
        if (uri == null) {
            Result.failure(IllegalStateException("保存到相册失败"))
        } else {
            Result.success(ExportResult(uri, outName, size))
        }
    }

    /** 片源编码一致且 ffmpeg 带对应 MediaCodec 解码器时才硬解 */
    private fun hwDecoderOf(entries: List<SelectionEntry>, options: ExportOptions, hw: BooleanArray): String? {
        if (!options.hwDecode) return null
        val mimes = entries.map { it.clip.videoMime }.distinct()
        if (mimes.size != 1) return null
        return when (mimes[0]) {
            "video/avc" -> if (hw.getOrElse(2) { false }) "h264_mediacodec" else null
            "video/hevc" -> if (hw.getOrElse(3) { false }) "hevc_mediacodec" else null
            else -> null
        }
    }

    private fun buildArgs(
        entries: List<SelectionEntry>,
        scaleH: Int?,
        needScale: Boolean,
        needFps: Boolean,
        anyAudio: Boolean,
        options: ExportOptions,
        hwCodec: String?,
        hwDecoder: String?,
        outFile: File,
    ): Array<String> {
        val args = ArrayList<String>()
        args += listOf("-y", "-hide_banner")

        if (entries.size == 1) {
            val e = entries[0]
            // 输入侧只预滚 PREROLL_MS，其余交给输出侧 -ss。
            // 纯输出侧 -ss 会把入点之前的所有帧都解一遍，4K 片源上这是最大的耗时来源；
            // 实测（ffmpeg 9，64s/4K60 源，入点 55s）两种写法选中的帧 framemd5 完全一致，
            // 耗时 12.6s → 4.4s；流复制模式下输出同样是 2.03s/122 帧，语义不变。
            // 入点靠近文件头时 pre=0，退化成原来的纯输出侧写法（此时输入侧 seek 可能是 no-op）。
            val pre = (e.inMs - PREROLL_MS).coerceAtLeast(0L)
            if (pre > 0) args += listOf("-ss", Tc.sec(pre))
            if (hwDecoder != null && !options.fastCopy) args += listOf("-c:v", hwDecoder)
            args += listOf("-i", e.clip.filePath, "-ss", Tc.sec(e.inMs - pre), "-t", Tc.sec(e.outMs - e.inMs))
        } else {
            val listFile = File(outFile.parentFile, outFile.nameWithoutExtension + ".txt")
            listFile.writeText(
                entries.joinToString("\n") { e ->
                    buildString {
                        append("file '").append(e.clip.filePath).append("'\n")
                        if (e.inMs > 0) append("inpoint ").append(Tc.sec(e.inMs)).append('\n')
                        if (e.outMs < e.clip.durationMs - 5) append("outpoint ").append(Tc.sec(e.outMs)).append('\n')
                    }
                },
            )
            if (hwDecoder != null && !options.fastCopy) args += listOf("-c:v", hwDecoder)
            args += listOf("-f", "concat", "-safe", "0", "-i", listFile.absolutePath)
        }

        if (options.fastCopy) {
            args += listOf("-c", "copy", "-avoid_negative_ts", "make_zero")
        } else {
            if (needScale && scaleH != null) args += listOf("-vf", "scale=-2:$scaleH")
            if (needFps && options.targetFps != null) args += listOf("-r", options.targetFps.toString())
            val outH = scaleH ?: entries.first().clip.displayHeight
            if (hwCodec != null) {
                // nv12 + gop：规范输入帧格式并设置关键帧间隔，部分 MediaCodec 实现必需
                args += listOf(
                    "-c:v", hwCodec,
                    "-pix_fmt", "nv12",
                    "-g", "60",
                    "-b:v", "${bitrateMbps(outH, options.quality)}M",
                )
            } else {
                args += listOf("-c:v", "libx264", "-preset", x264Preset(outH), "-crf", crfOf(options.quality).toString())
            }
            if (anyAudio) args += listOf("-c:a", "aac", "-b:a", "128k")
        }

        args += listOf("-movflags", "+faststart", outFile.absolutePath)
        return args.toTypedArray()
    }

    /** 手机上 x264 编 4K 极慢，分辨率越高越要往快档走，否则一段 20s 选区能跑几分钟 */
    private fun x264Preset(h: Int): String = when {
        h >= 2000 -> "ultrafast"
        h >= 1300 -> "superfast"
        else -> "veryfast"
    }

    private fun bitrateMbps(h: Int, q: Quality): Int = when {
        h >= 2000 -> if (q == Quality.HIGH) 60 else if (q == Quality.MEDIUM) 40 else 20
        h >= 1300 -> if (q == Quality.HIGH) 40 else if (q == Quality.MEDIUM) 25 else 15
        h >= 1000 -> if (q == Quality.HIGH) 20 else if (q == Quality.MEDIUM) 12 else 6
        h >= 700 -> if (q == Quality.HIGH) 10 else if (q == Quality.MEDIUM) 6 else 3
        else -> if (q == Quality.HIGH) 5 else if (q == Quality.MEDIUM) 3 else 2
    }

    private fun crfOf(q: Quality): Int = when (q) {
        Quality.HIGH -> 18
        Quality.MEDIUM -> 23
        Quality.LOW -> 28
    }

    private suspend fun runFfmpeg(
        args: Array<String>,
        durationMs: Long,
        stage: String,
        onProgress: (ExportStats) -> Unit,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            FFmpegSessionCompleteCallback { s ->
                if (cont.isActive) cont.resume(ReturnCode.isSuccess(s.returnCode))
            },
            null,
            StatisticsCallback { st ->
                val t = st.time
                if (durationMs > 0 && t >= 0) {
                    val p = (t / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
                    onProgress(ExportStats(p, st.speed, stage))
                }
            },
        )
        cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }

    fun cancelAll() {
        FFmpegKit.cancel()
    }
}
